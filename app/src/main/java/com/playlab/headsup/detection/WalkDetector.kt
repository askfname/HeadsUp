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

/** 检测快照：服务每 2s 刷新一次，UI 直接读取 */
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
 * 所有步伐事件进同一条流，只有「连续 N 步节律一致」才触发。
 * 非走路/上下楼的稳定节律会被打断计数。
 * 加速度峰值另有幅度带（1.4~6.0）+ 剧烈晃动否决（3s 内峰值 >9 则硬件步伐也不计）。
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
    private var lastCounterTime = 0L

    // 加速度计状态
    private val grav = FloatArray(3)
    private var lastPeak = 0L
    private var windowMax = 0f // 近 3s 线性加速度峰值（防剧烈晃动）
    private var windowMaxTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var testMode = false
    private var registered = false

    private val tickTask = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            while (stepTimes.isNotEmpty() && stepTimes.first() < now - 10_000) {
                stepTimes.removeFirst()
            }
            // 停步超 3s → 连贯计数清零；峰值窗口过期清零
            if (lastStepTime > 0 && now - lastStepTime > 3_000) {
                runLen = 0
                runStart = 0L
            }
            if (now - windowMaxTime > 3_000) windowMax = 0f
            pushSnap()
            handler.postDelayed(this, 2_000)
        }
    }

    fun register(sm: SensorManager) {
        if (registered) return
        registered = true
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
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            accelOn = true
        }
        handler.post(tickTask)
    }

    fun unregister(sm: SensorManager) {
        registered = false
        handler.removeCallbacks(tickTask)
        try { sm.unregisterListener(this) } catch (_: Exception) { }
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
                if (e.values[0] == 1f) onStepEvent()
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val v = e.values[0].toLong()
                val now = SystemClock.elapsedRealtime()
                if (lastCounter >= 0 && v > lastCounter) {
                    val d = (v - lastCounter).coerceAtMost(50).toInt()
                    // 批量到达时按到达跨度均匀摊开，避免同时间戳冲散节律
                    val span = (now - lastCounterTime).coerceAtLeast(0)
                    val gap = if (d > 1 && span > 0) span / d else 0L
                    for (i in 1..d) {
                        val at = if (gap >= 250) lastCounterTime + gap * i else now
                        onStepEvent(at = at)
                    }
                }
                lastCounter = v
                lastCounterTime = now
            }
            Sensor.TYPE_ACCELEROMETER -> accelCheck(e.values)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit

    // 加速度计：高通滤波取线性加速度；只记录峰值统计，有硬件计步时不参与计数
    private fun accelCheck(v: FloatArray) {
        val a = 0.8f
        for (i in 0..2) grav[i] = a * grav[i] + (1 - a) * v[i]
        var sum = 0f
        for (i in 0..2) {
            val l = v[i] - grav[i]
            sum += l * l
        }
        val mag = sqrt(sum)
        val now = SystemClock.elapsedRealtime()
        if (mag > windowMax || now - windowMaxTime > 3_000) {
            windowMax = mag
            windowMaxTime = now
        }
        // 幅度带：过小的桌面微振，过大的剧烈晃动等，都不计
        if (mag in 1.4f..6.0f && now - lastPeak > 280) {
            lastPeak = now
            if (!hasStepDetector && !hasStepCounter) onStepEvent(amp = mag)
        }
    }

    /** 融合步伐入口：节律一致性是唯一触发依据 */
    private fun onStepEvent(amp: Float? = null, at: Long = SystemClock.elapsedRealtime()) {
        // 剧烈晃动否决：近 3s 出现大冲击时，硬件步伐也不计（走路不会有这种冲击）
        if (amp == null && windowMax > 9f && at - windowMaxTime < 3_000) return

        stepTimes.addLast(at)
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
