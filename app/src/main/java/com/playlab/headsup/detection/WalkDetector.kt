package com.playlab.headsup.detection

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/** 检测快照：服务慢心跳刷新，有步伐时实时推送，UI 直接读取 */
data class DetectSnapshot(
    val heartbeat: Long = 0L,
    val walking: Boolean = false, // 连贯步数≥档位门限（灵敏3/标准4/严格6）-> 疑似行走中
    val stepsInWindow: Int = 0,
    val runLen: Int = 0, // 当前连续节律一致步数
    val runNeed: Int = 10, // 触发所需连贯步数
    val walkElapsedSec: Int = 0,
    val screenOn: Boolean = true,
    val unlocked: Boolean = true, // 非锁屏（锁屏不提醒）
    val pocketed: Boolean = false, // 距离感应器被遮挡（口袋里不提醒）
    val sensorBackend: String = WalkDetector.SensorBackend.NONE,
    val batched: Boolean = false, // 是否观察到批量投递（投递延迟 >1.5s）
    val gms: String = WalkDetector.GmsStatus.UNKNOWN,
    val lastTriggerAt: Long = 0L, // 上次真实提醒时刻（elapsedRealtime，批量投递下UI同步用）
    val simulating: Boolean = false, // 模拟步行状态
    val indoor: Boolean = false, // GPS 判室内（仅“室内不提醒”开时更新）
    val indoorPending: Boolean = false, // 位置未决、提醒挂起中
    val sats: String = "", // 强星/总数（跨机型对比信号用）
)

object DetectState {
    @Volatile var snap = DetectSnapshot()
}

/**
 * 本机步态检测（不依赖 GMS），使用 STEP_DETECTOR（0x12）
 * 连续 N 步节律一致才触发；节律按硬件事件时间戳计算，ROM 批量投递也不影响
 * 防误触：步伐节律 + 加速度/陀螺仪动作幅度双重确认，原地晃动因旋转过大/幅度超限被打断
 * 优先级：屏幕/锁定 > 遮挡 > 步态；息屏/锁定解注册传感器且步数作废
 */
class WalkDetector(
    private val onTrigger: () -> Unit,
    private val onTick: (DetectSnapshot) -> Unit = {},
) : SensorEventListener {

    object SensorBackend {
        const val NONE = "none"
        const val STEP_DETECTOR = "step_detector"
        const val STEP_COUNTER = "step_counter"
        const val GMS_ACCELEROMETER = "gms_accelerometer"
    }

    var screenOn = true
    var unlocked = true // 锁屏不提醒
    var pocketed = false // 口袋不提醒（仅亮屏已解锁时有效）
    /** 是否可用：优先级最高，息屏/锁定直接不可用 */
    fun isUsable() = screenOn && unlocked
    var requiredSteps = 12 // 默认标准档，服务启动时按灵敏度覆盖
    var gmsStatus = GmsStatus.UNKNOWN

    object GmsStatus {
        const val UNKNOWN = "unknown"
        const val AVAILABLE = "available"
        const val UNAVAILABLE = "unavailable"
        const val NO_PERM = "no_perm"
    }
    // 灵敏度联动：步数越多、节律容差越小、动作门限越严、显示门限越高
    var minInterval = 350L
    var maxInterval = 1700L
    var stableAbsMs = 260L
    var stableRatio = 0.5f
    var gyroMax = 1.4f // 本轮陀螺仪均值上限，超限判为晃动
    var accStdMin = 0.25f // 本轮加速度标准差下限，过静不是持机走路
    var accStdMax = 4.2f // 本轮加速度标准差上限，超限判为晃动
    var candSteps = 4 // 显示/活体门限（灵敏3/标准4/严格6）
    var maxMiss = 1 // 连续不规律步不断链的次数
    var idleResetMs = 3400L // 末步后判停走的延迟
    private var lastTriggerAt = 0L

    private var sensorBackend = SensorBackend.NONE
    private var stepCounterBaseline: Float? = null
    private var stepCounterLastValue: Float? = null
    private var batchObserved = false // 是否观察到批量投递

    // 口袋防抖：短暂遮挡不算口袋
    private var rawNear = false
    private var nearSince = 0L
    private val pocketConfirmTask = Runnable { confirmPocket() }

    private val stepTimes = ArrayDeque<Long>() // 10s 窗口，仅 UI 显示
    private var lastStepTime = 0L
    private val lastIvs = ArrayDeque<Long>() // 最近3个好间隔，取中位数比单步比较抗趔趄
    private var misses = 0 // 连续不规律步计数
    private var runLen = 0
    private var runStart = 0L

    // 动作幅度缓存（8s 窗口）：加速度模长 / 陀螺仪模长，用于区分走路与原地晃动
    private val accWin = ArrayDeque<Pair<Long, Float>>()
    private val gyroWin = ArrayDeque<Pair<Long, Float>>()
    private var hasGyro = false
    private var accelerometerRegistered = false
    private var gyroscopeRegistered = false
    private var proximityRegistered = false
    private var fallbackUntil = 0L
    private var fallbackLastPeakAt = 0L
    private var fallbackPeakArmed = true
    private var fallbackGravity = 0f
    private val sensorThread = HandlerThread("HeadsUpSensors").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    private val handler = Handler(Looper.getMainLooper())
    private var testMode = false
    private var testTriggerDispatched = false
    private val testStepTasks = mutableListOf<Runnable>()
    private var testFinishTask: Runnable? = null
    private var registered = false
    private var sensorManager: SensorManager? = null

    // 慢心跳（10s，Doze 下可被系统合并）：只做窗口裁剪和快照推送
    private val tickTask = object : Runnable {
        override fun run() {
            prune()
            pruneMotion(SystemClock.elapsedRealtime())
            if (sensorBackend == SensorBackend.GMS_ACCELEROMETER &&
                fallbackUntil > 0L && SystemClock.elapsedRealtime() >= fallbackUntil
            ) stopFallbackMotion()
            pushSnap()
            handler.postDelayed(this, 10_000)
        }
    }

    // 精确停走重置：末步后超时无新步伐即清零（不依赖慢心跳，保证 UI 不滞后）
    private val resetTask = Runnable {
        resetRun()
        pushSnap()
    }

    // 优先硬件步伐事件；无硬件时仅在 GMS 行走候选期启用加速度确认
    fun register(sm: SensorManager) {
        if (registered) return
        registered = true
        sensorManager = sm
        batchObserved = false
        sensorBackend = SensorBackend.NONE
        stepCounterBaseline = null
        stepCounterLastValue = null
        hasGyro = false
        accelerometerRegistered = false
        gyroscopeRegistered = false
        proximityRegistered = false
        val detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (detector != null && register(sm, detector, SensorManager.SENSOR_DELAY_NORMAL)) {
            sensorBackend = SensorBackend.STEP_DETECTOR
        } else {
            val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (counter != null && register(sm, counter, SensorManager.SENSOR_DELAY_NORMAL)) {
                sensorBackend = SensorBackend.STEP_COUNTER
            } else if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                sensorBackend = SensorBackend.GMS_ACCELEROMETER
            }
        }
        if (sensorBackend != SensorBackend.GMS_ACCELEROMETER) registerMotionGates(sm)
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            proximityRegistered = register(sm, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        handler.post(tickTask)
        pushSnap()
    }

    private fun register(sm: SensorManager, sensor: Sensor, rate: Int): Boolean = try {
        sm.registerListener(this, sensor, rate, sensorHandler)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Unable to register ${sensor.stringType}", e)
        false
    }

    private fun registerMotionGates(sm: SensorManager) {
        if (!accelerometerRegistered) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                accelerometerRegistered = register(sm, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if (!gyroscopeRegistered) {
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                gyroscopeRegistered = register(sm, it, SensorManager.SENSOR_DELAY_GAME)
                hasGyro = gyroscopeRegistered
            }
        }
    }

    /** GMS 只打开有限的加速度确认窗口，不能单独产生提醒 */
    fun onGmsWalkingHint(sm: SensorManager): Boolean {
        if (sensorBackend != SensorBackend.GMS_ACCELEROMETER) return isWalkingNow()
        if (!isUsable() || pocketed) return false
        fallbackUntil = SystemClock.elapsedRealtime() + FALLBACK_WINDOW_MS
        fallbackLastPeakAt = 0L
        fallbackPeakArmed = true
        fallbackGravity = 0f
        registerMotionGates(sm)
        return false
    }

    private fun stopFallbackMotion() {
        val sm = sensorManager ?: return
        if (accelerometerRegistered || gyroscopeRegistered) {
            try { sm.unregisterListener(this) } catch (_: RuntimeException) { }
            accelerometerRegistered = false
            gyroscopeRegistered = false
            hasGyro = false
            proximityRegistered = false
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
                proximityRegistered = register(sm, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        fallbackUntil = 0L
    }

    /** 重注册：授权后补回传感器事件（register幂等 early-return，执行不到这里） */
    fun reregister(sm: SensorManager, active: Boolean) {
        try { sm.unregisterListener(this) } catch (_: Exception) { }
        registered = false
        handler.removeCallbacks(tickTask)
        handler.removeCallbacks(pocketConfirmTask)
        if (active) register(sm) else pushSnap()
    }

    fun unregister(sm: SensorManager) {
        registered = false
        handler.removeCallbacks(tickTask)
        handler.removeCallbacks(resetTask)
        handler.removeCallbacks(pocketConfirmTask)
        try { sm.unregisterListener(this) } catch (_: Exception) { }
        accelerometerRegistered = false
        gyroscopeRegistered = false
        proximityRegistered = false
        hasGyro = false
        fallbackUntil = 0L
        sensorManager = null
        pushSnap() // 推送最终状态，UI 不显示 stale 快照
    }

    /** 用机状态同步：不可用时步数作废、遮挡清零（优先级：屏幕/锁定 > 遮挡 > 步态） */
    fun setUseState(screenOn: Boolean, unlocked: Boolean) {
        val wasUsable = isUsable()
        this.screenOn = screenOn
        this.unlocked = unlocked
        if (!isUsable()) {
            rawNear = false
            nearSince = 0L
            pocketed = false
            handler.removeCallbacks(pocketConfirmTask)
            resetAll()
            pushSnap()
        } else if (!wasUsable) {
            // 回到可用：从零累计，防解锁后误触发
            resetAll()
            pushSnap()
        }
    }

    /** 对外同步一次当前状态 */
    fun publishState() = pushSnap()

    private fun resetAll() {
        resetRun()
        stepTimes.clear()
        lastStepTime = 0L
        accWin.clear()
        gyroWin.clear()
        handler.removeCallbacks(resetTask)
    }

    /** 灵敏度切换：更新全部阈值并清零旧累计，避免旧步数带入新档位 */
    fun applySensitivity(
        steps: Int,
        minIv: Long = 350L,
        maxIv: Long = 1700L,
        absMs: Long = 260L,
        ratio: Float = 0.5f,
        gyro: Float = 1.4f,
        accMin: Float = 0.25f,
        accMax: Float = 4.2f,
        cand: Int = 4,
        miss: Int = 1,
        idleMs: Long = 3400L,
    ) {
        requiredSteps = steps
        minInterval = minIv
        maxInterval = maxIv
        stableAbsMs = absMs
        stableRatio = ratio
        gyroMax = gyro
        accStdMin = accMin
        accStdMax = accMax
        candSteps = cand
        maxMiss = miss
        idleResetMs = idleMs
        resetRun()
        pushSnap()
    }

    /** 真实提醒后闩锁：批量投递下整串步伐一次处理完，UI 显示“已提醒”而非“静止” */
    fun noteFired() {
        lastTriggerAt = SystemClock.elapsedRealtime()
        pushSnap()
    }

    private fun prune() {
        val cut = SystemClock.elapsedRealtime() - 10_000
        while (stepTimes.isNotEmpty() && stepTimes.first() < cut) stepTimes.removeFirst()
    }

    /**
     * 熔断门：优先级 屏幕/锁定 > 遮挡 > 步态；室内由服务层最后判定
     * 所有提醒触发前必须过此门，防止过期信号（延迟的 GMS 回调等）在静止时触发
     */
    fun isWalkingNow(): Boolean {
        if (!isUsable()) return false
        if (pocketed) return false
        if (runLen < candSteps) return false
        if (testMode) return true
        val now = SystemClock.elapsedRealtime()
        if (now - lastStepTime >= 5_000) return false
        return gaitOk(now)
    }

    /** 模拟步行状态：用于界面反馈 */
    fun isSimulating() = testMode

    /** 模拟步行：走与真实步行相同的提醒及室内判定通路 */
    fun injectTestBurst() {
        if (testMode) return
        testMode = true
        testTriggerDispatched = false
        pushSnap()
        val n = requiredSteps + 4
        for (i in 0 until n) {
            val task = Runnable { onStepEvent() }
            testStepTasks += task
            handler.postDelayed(task, i * 500L)
        }
        testFinishTask = Runnable {
            testMode = false
            testTriggerDispatched = false
            testStepTasks.clear()
            testFinishTask = null
            pushSnap()
        }
        handler.postDelayed(testFinishTask!!, n * 500L + 2_000)
    }

    /** 页面离开时取消尚未执行的模拟步伐 */
    fun cancelTestBurst() {
        testStepTasks.forEach(handler::removeCallbacks)
        testStepTasks.clear()
        testFinishTask?.let(handler::removeCallbacks)
        testFinishTask = null
        if (!testMode) return
        testMode = false
        testTriggerDispatched = false
        handler.removeCallbacks(resetTask)
        resetRun()
        pushSnap()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                if (!isUsable()) return // 息屏/锁定步数作废
                if (e.values[0] != 1f) return
                val at = eventMs(e.timestamp)
                // 批量投递标记：事件硬件时间远早于到达时间（>1.5s）
                if (SystemClock.elapsedRealtime() - at > 1_500) batchObserved = true
                onStepEvent(at = at)
            }
            Sensor.TYPE_STEP_COUNTER -> onStepCounter(e)
            Sensor.TYPE_ACCELEROMETER -> {
                if (!isUsable()) return
                val now = SystemClock.elapsedRealtime()
                val m = kotlin.math.sqrt(
                    (e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble(),
                ).toFloat()
                accWin.addLast(now to m)
                pruneMotion(now)
                if (sensorBackend == SensorBackend.GMS_ACCELEROMETER) onFallbackAcceleration(m, now)
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!isUsable()) return
                val now = SystemClock.elapsedRealtime()
                val m = kotlin.math.sqrt(
                    (e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble(),
                ).toFloat()
                gyroWin.addLast(now to m)
                pruneMotion(now)
            }
            Sensor.TYPE_PROXIMITY -> {
                // 防抖：持续遮挡才算口袋，短暂遮挡忽略
                onProximityChanged(e.values[0] < e.sensor.maximumRange - 0.01f)
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit

    private fun onStepCounter(e: SensorEvent) {
        if (!isUsable() || e.values.isEmpty()) return
        val value = e.values[0]
        val previous = stepCounterLastValue
        if (stepCounterBaseline == null || previous == null || value < previous) {
            stepCounterBaseline = value
            stepCounterLastValue = value
            return
        }
        val delta = (value - previous).toInt().coerceIn(0, MAX_COUNTER_BATCH)
        stepCounterLastValue = value
        if (delta == 0) return
        val at = eventMs(e.timestamp)
        val spacing = medianIv(600L).coerceIn(minInterval, maxInterval)
        repeat(delta) { index -> onStepEvent(at - (delta - index - 1) * spacing) }
    }

    private fun onFallbackAcceleration(magnitude: Float, now: Long) {
        if (now > fallbackUntil) return
        // Remove gravity before peak detection so phone orientation does not change the threshold.
        fallbackGravity = if (fallbackGravity == 0f) magnitude else
            FALLBACK_GRAVITY_ALPHA * fallbackGravity + (1f - FALLBACK_GRAVITY_ALPHA) * magnitude
        val linear = magnitude - fallbackGravity
        if (linear < FALLBACK_REARM) fallbackPeakArmed = true
        if (!fallbackPeakArmed || linear < FALLBACK_PEAK) return
        if (fallbackLastPeakAt > 0 && now - fallbackLastPeakAt !in minInterval..maxInterval) {
            if (now - fallbackLastPeakAt > maxInterval) resetRun()
            return
        }
        fallbackPeakArmed = false
        fallbackLastPeakAt = now
        onStepEvent(now, skipAccelerationGate = true)
    }

    // 硬件事件时间（与 elapsedRealtime 同基，批量投递下依然准确）；HAL 上报 0 则回退投递时间
    private fun eventMs(tsNs: Long) =
        if (tsNs > 0) tsNs / 1_000_000 else SystemClock.elapsedRealtime()

    private fun pruneMotion(now: Long) {
        val cut = now - 8_000
        while (accWin.isNotEmpty() && accWin.first().first < cut) accWin.removeFirst()
        while (gyroWin.isNotEmpty() && gyroWin.first().first < cut) gyroWin.removeFirst()
    }

    // 仅亮屏已解锁时确认口袋
    private fun pocketConfirmMs() = 2500L

    private fun onProximityChanged(near: Boolean) {
        if (!isUsable()) return // 息屏/锁定不使用距离传感器
        val now = SystemClock.elapsedRealtime()
        if (near) {
            if (!rawNear) {
                rawNear = true
                nearSince = now
                handler.removeCallbacks(pocketConfirmTask)
                handler.postDelayed(pocketConfirmTask, pocketConfirmMs())
            }
        } else {
            rawNear = false
            nearSince = 0L
            handler.removeCallbacks(pocketConfirmTask)
            if (pocketed) {
                pocketed = false
                pushSnap()
            }
        }
    }

    private fun confirmPocket() {
        if (!isUsable() || !rawNear || pocketed || nearSince == 0L) return
        val need = pocketConfirmMs()
        val elapsed = SystemClock.elapsedRealtime() - nearSince
        if (elapsed >= need) {
            pocketed = true
            resetRun() // 口袋内步数作废，避免拿出后误触发
            pushSnap()
        } else {
            handler.postDelayed(pocketConfirmTask, need - elapsed)
        }
    }

    private fun resetRun() {
        runLen = 0
        runStart = 0L
        lastIvs.clear()
        misses = 0
    }

    // 最近好间隔的中位数：单次趔趄不带偏基准
    private fun medianIv(fallback: Long): Long {
        if (lastIvs.isEmpty()) return fallback
        val s = lastIvs.sorted()
        return s[s.size / 2]
    }

    /**
     * 动作幅度门：本轮窗口内加速度标准差过大（剧烈晃动）或陀螺仪均值过大（大幅旋转）则判假
     * 样本不足（传感器缺失/刚启动）时放行，避免误杀
     */
    private fun gaitOk(now: Long): Boolean {
        val from = if (runStart > 0) runStart else now - 6_000
        var n = 0
        var mean = 0.0
        var m2 = 0.0
        for ((t, v) in accWin) {
            if (t < from) continue
            n++
            val d = v - mean
            mean += d / n
            m2 += d * (v - mean)
        }
        if (n >= 12) {
            val std = kotlin.math.sqrt(m2 / n)
            if (std < accStdMin || std > accStdMax) return false // 过静或过烈都不是持机走路
        }
        if (hasGyro) {
            var gn = 0
            var gsum = 0.0
            for ((t, v) in gyroWin) {
                if (t < from) continue
                gn++
                gsum += v
            }
            if (gn >= 12 && gsum / gn > gyroMax) return false // 原地晃动旋转远大于走路看屏
        }
        return true
    }

    /** 步伐入口：节律 + 动作幅度双重确认；锁屏/口袋直接丢弃 */
    private fun onStepEvent(at: Long = SystemClock.elapsedRealtime(), skipAccelerationGate: Boolean = false) {
        // 丢弃乱序/重复事件（迟到的批量旧事件不参与节律）
        if (lastStepTime > 0 && at <= lastStepTime) return

        // 非用机状态不累计：避免锁屏/口袋里的步数攒够后一拿出来就误报
        if (!testMode && (!screenOn || !unlocked || pocketed)) {
            resetRun()
            lastStepTime = at
            pushSnap()
            return
        }

        stepTimes.addLast(at)
        prune()
        if (lastStepTime > 0) {
            val iv = at - lastStepTime
            val inRange = iv in minInterval..maxInterval // 人类步频：走路/上下楼均在此带
            val ref = medianIv(iv)
            val stable = runLen < 2 ||
                abs(iv - ref) <= max(stableAbsMs, (ref * stableRatio).toLong())
            if (inRange && stable) {
                runLen++
                misses = 0
                lastIvs.addLast(iv)
                if (lastIvs.size > 3) lastIvs.removeFirst()
            } else if (runLen >= 2 && misses < maxMiss) {
                misses++ // 单次不规律不断链（迟钝硬件漏步/慢走趔趄），基准保持旧中位数
            } else {
                runLen = 1
                runStart = at
                misses = 0
                lastIvs.clear()
            }
        } else {
            runLen = 1
            runStart = at
            misses = 0
        }
        lastStepTime = at
        // 攒够显示门限后每步都验动作幅度，晃动直接打断，不让它攒到触发线
        if (!testMode && !skipAccelerationGate && runLen >= candSteps && !gaitOk(at)) {
            runLen = 1
            runStart = at
            misses = 0
            lastIvs.clear()
            pushSnap()
            handler.removeCallbacks(resetTask)
            handler.postDelayed(resetTask, idleResetMs)
            return
        }
        // 触发线：达连贯步数即回调（不清零：抑制/挂起不消耗步数，冷却防重发，显示不断链）
        if (runLen >= requiredSteps && (testMode || (screenOn && unlocked && !pocketed &&
                (skipAccelerationGate || gaitOk(at)))) &&
            (!testMode || !testTriggerDispatched)
        ) {
            // 不清空 runLen/stepTimes：连续行走保持显示，停走由 resetTask 清零
            if (testMode) testTriggerDispatched = true
            handler.post(onTrigger)
        }
        // 每次步伐后重约定点：超时无后续即判停走（单次延迟任务，开销可忽略）
        handler.removeCallbacks(resetTask)
        handler.postDelayed(resetTask, idleResetMs)
        pushSnap()
    }

    private fun pushSnap() {
        val now = SystemClock.elapsedRealtime()
        // 触发后 8s 内保持 walking：批量投递整串步伐毫秒级处理完，否则 UI 永远看不到行走态
        val latched = lastTriggerAt > 0 && now - lastTriggerAt < 8_000
        val snap = DetectSnapshot(
                heartbeat = now,
                walking = runLen >= candSteps || latched,
                stepsInWindow = stepTimes.size,
                runLen = runLen,
                runNeed = requiredSteps,
                walkElapsedSec = if (runStart == 0L) 0 else ((now - runStart) / 1000).toInt(),
                screenOn = screenOn,
                unlocked = unlocked,
                pocketed = pocketed,
                sensorBackend = sensorBackend,
                batched = batchObserved,
                gms = gmsStatus,
                lastTriggerAt = lastTriggerAt,
                simulating = testMode,
        )
        if (Looper.myLooper() == handler.looper) onTick(snap)
        else handler.post { onTick(snap) }
    }

    fun close() {
        sensorThread.quitSafely()
    }

    private companion object {
        const val TAG = "WalkDetector"
        const val FALLBACK_WINDOW_MS = 10_000L
        const val FALLBACK_GRAVITY_ALPHA = 0.9f
        const val FALLBACK_PEAK = 1.1f
        const val FALLBACK_REARM = 0.35f
        const val MAX_COUNTER_BATCH = 4
    }
}
