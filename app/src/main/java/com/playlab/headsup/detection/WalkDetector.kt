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
import kotlin.math.sqrt

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
    val hasStepCounter: Boolean = false,
    val accelOn: Boolean = false,
    val gms: String = "未知",
)

object DetectState {
    @Volatile var snap = DetectSnapshot()
}

/**
 * 本机步态检测（不依赖 GMS）：
 * 所有步伐事件进同一条流，只有「连续 N 步节律一致」才触发；
 * 节律按硬件事件时间戳计算，ROM 批量投递也不影响；
 * 硬件 45s 无投递则自动启用加速度后备；加速度峰值另有幅度带 + 晃动否决。
 */
class WalkDetector(
    private val onTrigger: () -> Unit,
    private val onTick: (DetectSnapshot) -> Unit = {},
) : SensorEventListener {

    var screenOn = true
    var requiredSteps = 10 // 快速 10（约6~8s），标准 14（约9~12s）
    var gmsStatus = "未知"

    private var hasStepDetector = false
    private var hasStepCounter = false
    private var accelOn = false

    private val stepTimes = ArrayDeque<Long>() // 10s 窗口，仅 UI 显示
    private var lastStepTime = 0L
    private var prevInterval = 0L
    private var runLen = 0
    private var runStart = 0L
    private var runAmpSum = 0f
    private var runAmpN = 0

    private var lastCounter = -1L
    private var lastCounterEventTs = 0L // 上次计步事件的硬件时间戳
    private var smRef: SensorManager? = null
    private var regElapsed = 0L // 本次注册时刻（存活检查用）
    private var gotHwStep = false // 本次注册后是否收到过硬件步伐事件

    // 加速度计状态
    private val grav = FloatArray(3)
    private var lastPeak = 0L
    private var windowMax = 0f // 近 3s 线性加速度峰值（防剧烈晃动）
    private var windowMaxTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var testMode = false
    private var registered = false

    // 慢心跳（10s，Doze 下可被系统合并）：只做窗口裁剪、峰值过期和快照推送
    private val tickTask = object : Runnable {
        override fun run() {
            prune()
            val now = SystemClock.elapsedRealtime()
            if (now - windowMaxTime > 3_000) windowMax = 0f
            // 存活检查：亮屏 45s 仍无硬件事件 → HAL 可能不投递，启用加速度后备
            if (screenOn && !accelOn && (hasStepDetector || hasStepCounter) &&
                !gotHwStep && now - regElapsed > 45_000
            ) {
                enableAccelFallback()
            }
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

    fun register(sm: SensorManager) {
        if (registered) return
        registered = true
        smRef = sm
        regElapsed = SystemClock.elapsedRealtime()
        gotHwStep = false
        accelOn = false
        lastCounter = -1L // 计步器累计值跨会话重建基线，避免巨大差分
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            hasStepDetector = true
        }
        if (!hasStepDetector) {
            sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                hasStepCounter = true
            }
        }
        // 加速度计仅无硬件计步时注册：UI 速率常驻是最大 CPU 开销，有硬件时完全不需要
        if (!hasStepDetector && !hasStepCounter) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                accelOn = true
            }
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
        when (e.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                gotHwStep = true
                if (e.values[0] == 1f) onStepEvent(at = eventMs(e.timestamp))
            }
            Sensor.TYPE_STEP_COUNTER -> {
                gotHwStep = true
                val v = e.values[0].toLong()
                val evTs = eventMs(e.timestamp)
                if (lastCounter >= 0 && v > lastCounter) {
                    val d = (v - lastCounter).coerceAtMost(50).toInt()
                    // 按硬件时间跨度均匀摊开，批量投递下节律依然准确
                    val span = (evTs - lastCounterEventTs).coerceAtLeast(0)
                    val gap = if (d > 1 && span > 0) span / d else 0L
                    for (i in 1..d) {
                        val at = if (gap >= 250) lastCounterEventTs + gap * i else evTs
                        onStepEvent(at = at)
                    }
                }
                lastCounter = v
                lastCounterEventTs = evTs
            }
            Sensor.TYPE_ACCELEROMETER -> accelCheck(e.values, e.timestamp)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit

    // 硬件事件时间（与 elapsedRealtime 同基，批量投递下依然准确）；HAL 上报 0 则回退投递时间
    private fun eventMs(tsNs: Long) =
        if (tsNs > 0) tsNs / 1_000_000 else SystemClock.elapsedRealtime()

    // 加速度后备：硬件疑似不投递时启用（NORMAL 速率，开销小）
    private fun enableAccelFallback() {
        val sm = smRef ?: return
        try {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                if (sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)) {
                    accelOn = true
                    pushSnap()
                }
            }
        } catch (_: Exception) { }
    }

    // 加速度计：高通滤波取线性加速度；只记录峰值统计，有硬件计步时不参与计数
    private fun accelCheck(v: FloatArray, tsNs: Long) {
        val a = 0.8f
        for (i in 0..2) grav[i] = a * grav[i] + (1 - a) * v[i]
        var sum = 0f
        for (i in 0..2) {
            val l = v[i] - grav[i]
            sum += l * l
        }
        val mag = sqrt(sum)
        val evTs = eventMs(tsNs)
        if (mag > windowMax || evTs - windowMaxTime > 3_000) {
            windowMax = mag
            windowMaxTime = evTs
        }
        // 幅度带：过小的桌面微振，过大的剧烈晃动等，都不计
        if (mag in 1.4f..6.0f && evTs - lastPeak > 280) {
            lastPeak = evTs
            if (!hasStepDetector && !hasStepCounter) onStepEvent(amp = mag, at = evTs)
        }
    }

    /** 融合步伐入口：节律一致性是唯一触发依据 */
    private fun onStepEvent(amp: Float? = null, at: Long = SystemClock.elapsedRealtime()) {
        // 剧烈晃动否决：近 3s 出现大冲击时，硬件步伐也不计（走路不会有这种冲击）
        if (amp == null && windowMax > 9f && at - windowMaxTime < 3_000) return
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
                runAmpSum = 0f
                runAmpN = 0
            }
            prevInterval = iv
        } else {
            runLen = 1
            runStart = at
            runAmpSum = 0f
            runAmpN = 0
        }
        lastStepTime = at
        if (amp != null) {
            runAmpSum += amp
            runAmpN++
            // 加速度源本轮平均幅度过大 → 持续晃动，清零
            if (runAmpN >= 4 && runAmpSum / runAmpN > 4.5f) {
                runLen = 0
                runStart = 0L
                runAmpSum = 0f
                runAmpN = 0
            }
        }
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
                hasStepCounter = hasStepCounter,
                accelOn = accelOn,
                gms = gmsStatus,
            ),
        )
    }
}
