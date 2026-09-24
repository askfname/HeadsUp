package com.headsup.app.detection

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

/** 检测快照：服务慢心跳刷新，有步伐时实时推送，UI 直接读取 */
data class DetectSnapshot(
    val heartbeat: Long = 0L,
    val walking: Boolean = false, // 连贯步数≥3，疑似行走中
    val stepsInWindow: Int = 0,
    val runLen: Int = 0, // 当前连续节律一致步数
    val runNeed: Int = 10, // 触发所需连贯步数
    val walkElapsedSec: Int = 0,
    val screenOn: Boolean = true,
    val hasStepDetector: Boolean = false,
    val batched: Boolean = false, // 是否观察到批量投递（投递延迟 >1.5s）
    val gms: String = "未知",
)

object DetectState {
    @Volatile var snap = DetectSnapshot()
}

/**
 * 本机步态检测（不依赖 GMS），使用 STEP_DETECTOR（0x12）。
 * 连续 N 步节律一致才触发；节律按硬件事件时间戳计算，ROM 批量投递也不影响。
 */
class WalkDetector(
    private val onTrigger: () -> Unit,
    private val onTick: (DetectSnapshot) -> Unit = {},
) : SensorEventListener {

    var screenOn = true
    var requiredSteps = 10 // 快速 10（约6~8s），标准 14（约9~12s）
    var gmsStatus = "未知"

    private var hasStepDetector = false
    private var batchObserved = false // 是否观察到批量投递

    private val stepTimes = ArrayDeque<Long>() // 10s 窗口，仅 UI 显示
    private var lastStepTime = 0L
    private var prevInterval = 0L
    private var runLen = 0
    private var runStart = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var testMode = false
    private var registered = false

    // 慢心跳（10s，Doze 下可被系统合并）：只做窗口裁剪和快照推送
    private val tickTask = object : Runnable {
        override fun run() {
            prune()
            pushSnap()
            handler.postDelayed(this, 10_000)
        }
    }

    // 精确停走重置：末步后 3.2s 无新步伐即清零（不依赖慢心跳，保证 UI 不滞后）
    private val resetTask = Runnable {
        runLen = 0
        runStart = 0L
        pushSnap()
    }

    // 注册 STEP_DETECTOR（sensor 0x12 + NORMAL 速率）；无硬件则无法检测
    fun register(sm: SensorManager) {
        if (registered) return
        registered = true
        batchObserved = false
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            hasStepDetector = true
        }
        handler.post(tickTask)
    }

    fun unregister(sm: SensorManager) {
        registered = false
        handler.removeCallbacks(tickTask)
        handler.removeCallbacks(resetTask)
        try { sm.unregisterListener(this) } catch (_: Exception) { }
        pushSnap() // 推送最终状态，UI 不显示 stale 快照
    }

    /** 对外同步一次当前状态 */
    fun publishState() = pushSnap()

    private fun prune() {
        val cut = SystemClock.elapsedRealtime() - 10_000
        while (stepTimes.isNotEmpty() && stepTimes.first() < cut) stepTimes.removeFirst()
    }

    /**
     * 熔断门：此刻是否处于行走状态（连贯步数≥3 且 5s 内有步伐）。
     * 所有提醒触发前必须过此门，防止过期信号（延迟的 GMS 回调等）在静止时触发。
     */
    fun isWalkingNow(): Boolean {
        if (runLen < 3) return false
        return SystemClock.elapsedRealtime() - lastStepTime < 5_000
    }

    /** 模拟步行：走真实检测通路（供设置页端到端测试） */
    fun injectTestBurst() {
        testMode = true
        val n = requiredSteps + 4
        for (i in 0 until n) {
            handler.postDelayed({ onStepEvent() }, i * 500L)
        }
        handler.postDelayed({ testMode = false }, n * 500L + 2_000)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_STEP_DETECTOR || e.values[0] != 1f) return
        val at = eventMs(e.timestamp)
        // 批量投递标记：事件硬件时间远早于到达时间（>1.5s）
        if (SystemClock.elapsedRealtime() - at > 1_500) batchObserved = true
        onStepEvent(at = at)
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit

    // 硬件事件时间（与 elapsedRealtime 同基，批量投递下依然准确）；HAL 上报 0 则回退投递时间
    private fun eventMs(tsNs: Long) =
        if (tsNs > 0) tsNs / 1_000_000 else SystemClock.elapsedRealtime()

    /** 步伐入口：节律一致性是唯一触发依据 */
    private fun onStepEvent(at: Long = SystemClock.elapsedRealtime()) {
        // 丢弃乱序/重复事件（迟到的批量旧事件不参与节律）
        if (lastStepTime > 0 && at <= lastStepTime) return

        stepTimes.addLast(at)
        prune()
        if (lastStepTime > 0) {
            val iv = at - lastStepTime
            val inRange = iv in 350..1500 // 人类步频：走路/上下楼均在此带
            val stable = runLen < 2 ||
                abs(iv - prevInterval) <= max(280L, (prevInterval * 0.5).toLong())
            if (inRange && stable) {
                runLen++
            } else {
                runLen = 1
                runStart = at
            }
            prevInterval = iv
        } else {
            runLen = 1
            runStart = at
        }
        lastStepTime = at
        if (runLen >= requiredSteps && (screenOn || testMode)) {
            runLen = 0
            runStart = 0L
            stepTimes.clear()
            onTrigger()
        }
        // 每次步伐后重约定点：3.2s 无后续即判停走（单次延迟任务，开销可忽略）
        handler.removeCallbacks(resetTask)
        handler.postDelayed(resetTask, 3_200)
        pushSnap()
    }

    private fun pushSnap() {
        val now = SystemClock.elapsedRealtime()
        onTick(
            DetectSnapshot(
                heartbeat = now,
                walking = runLen >= 3,
                stepsInWindow = stepTimes.size,
                runLen = runLen,
                runNeed = requiredSteps,
                walkElapsedSec = if (runStart == 0L) 0 else ((now - runStart) / 1000).toInt(),
                screenOn = screenOn,
                hasStepDetector = hasStepDetector,
                batched = batchObserved,
                gms = gmsStatus,
            ),
        )
    }
}
