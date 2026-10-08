package com.playlab.headsup.service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.app.KeyguardManager
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.playlab.headsup.MainActivity
import com.playlab.headsup.R
import com.playlab.headsup.data.Prefs
import com.playlab.headsup.detection.DetectSnapshot
import com.playlab.headsup.detection.DetectState
import com.playlab.headsup.detection.WalkDetector
import com.playlab.headsup.reminder.ReminderManager
import com.playlab.headsup.util.IndoorDetector
import com.playlab.headsup.util.PermissionHelper

/**
 * 步行检测前台服务：
 * 本机 STEP_DETECTOR 为主链路；GMS Activity Recognition 仅作加速提示
 */
class HeadsUpService : Service() {

    private lateinit var detector: WalkDetector
    private var lastGuardText = ""
    private var sensorMgr: SensorManager? = null
    private var lastSensSig = "" // 灵敏度签名，变化才重置累计（否则每次 start 都会清零行走态）

    // 判定优先级：屏幕/锁定 > 遮挡 > 步态 > 室内
    // 不可用（息屏/锁定）时解注册全部传感器：步数作废，距离传感器也不用
    // 可用（亮屏已解锁）时才注册
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    refreshUseState()
                    syncSensors()
                    refreshIndoorTracking()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    refreshUseState()
                    syncSensors()
                    refreshIndoorTracking()
                }
                LocationManager.PROVIDERS_CHANGED_ACTION -> refreshIndoorTracking()
            }
        }
    }

    /** 从系统刷新亮灭屏/锁屏状态并同步给检测器（不可用时清步数、清挂起） */
    private fun refreshUseState() {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        detector.setUseState(interactive, !locked)
        if (!detector.isUsable()) {
            pendingSince = 0L
            pendingSimulation = false
        }
    }

    /** 按可用性统一注册/解注册传感器 */
    private fun syncSensors() {
        sensorMgr?.let {
            if (detector.isUsable()) detector.register(it)
            else detector.unregister(it)
        }
    }

    /** 触发前复核：灭屏/锁屏/口袋直接拦截（防锁屏亮屏、口袋亮屏、GMS 延迟回调） */
    private fun isUsableNow(): Boolean {
        refreshUseState()
        return detector.isUsable() && !detector.pocketed
    }

    // 挂起提醒：步态已确认但位置未决，等 GPS 结论再发（优先准确）
    private var pendingSince = 0L
    private var pendingSimulation = false

    // 最近一次 tick 快照：发出后定格守卫用
    private var lastTick: DetectSnapshot? = null

    /** 开关开 + 位置够用 + 定位总开关开 + 亮屏已解锁才追踪 GPS，否则停 */
    private fun refreshIndoorTracking() {
        val usable = ::detector.isInitialized && detector.isUsable()
        // 同步高级设置自定义参数（未变时内部跳过）
        IndoorDetector.applyCustom(Prefs.getIndoorCustomOrNull(this))
        if (Prefs.isEnabled(this) && Prefs.isIndoorMute(this) &&
            PermissionHelper.isLocationEnough(this) && IndoorDetector.isLocationOn(this) && usable
        ) IndoorDetector.start(this)
        else IndoorDetector.stop()
    }

    /** 步行触发统一入口：只有明确室外才提醒，未知或室内均抑制 */
    private fun handleWalkTrigger() {
        if (!Prefs.isEnabled(this) || !isUsableNow() || !::detector.isInitialized) return
        val simulating = detector.isSimulating()
        if (!simulating && !Prefs.canTrigger(this)) return // 冷却中直接丢弃，不挂起
        if (!Prefs.isIndoorMute(this)) {
            fireNow(simulating)
            return
        }
        when (IndoorDetector.verdict()) {
            IndoorDetector.Verdict.INDOOR -> return // 抑制，不计冷却
            IndoorDetector.Verdict.OUTDOOR -> fireNow(simulating)
            IndoorDetector.Verdict.UNKNOWN -> {
                // 室内静音优先：定位不可用时不将未知误当室外
                if (!IndoorDetector.isLocationOn(this) ||
                    !PermissionHelper.isLocationEnough(this)
                ) {
                    return
                }
                if (pendingSince == 0L) {
                    pendingSince = SystemClock.elapsedRealtime()
                    pendingSimulation = simulating
                }
                IndoorDetector.noteWalking() // 确保 GPS 开着等结论，结论到后发/抑再关
            }
        }
    }

    /** 真实发出前重新读取结论，避免 GNSS 回调已切换为室内时仍发通知 */
    private fun fireNow(skipCooldown: Boolean = false): Boolean {
        if (Prefs.isIndoorMute(this) && IndoorDetector.verdict() != IndoorDetector.Verdict.OUTDOOR) {
            pendingSince = 0L
            pendingSimulation = false
            IndoorDetector.noteIdle()
            return false
        }
        if (ReminderManager.fire(this, checkIndoor = false, skipCooldown = skipCooldown)) {
            detector.noteFired()
            // 发出后冷却期内 verdict 用不上，关 GPS（10s 延迟，冷却过期 tick 会按需再开）
            pendingSince = 0L
            pendingSimulation = false
            IndoorDetector.noteIdle()
            // 定格守卫为行走态：显示与已发动作一致，不回写旧室内/挂起
            lastTick?.let { renderGuard(it.copy(indoorPending = false), false) }
            return true
        }
        return false
    }

    /** 守卫文本：室内抑制优先，其次挂起确认中，其余显示行走/静止 */
    private fun renderGuard(s: DetectSnapshot, indoorNow: Boolean) {
        if (!s.screenOn) notifyGuard(getString(R.string.guard_screen_off))
        else if (!s.unlocked) notifyGuard(getString(R.string.guard_locked))
        else if (s.pocketed) notifyGuard(getString(R.string.guard_pocket))
        else if (indoorNow && Prefs.isIndoorMute(this)) notifyGuard(getString(R.string.guard_indoor))
        else if (s.indoorPending && Prefs.isIndoorMute(this)) notifyGuard(getString(R.string.guard_loc_confirming))
        else refreshGuard(s.walking, s.runLen, s.runNeed, s.walkElapsedSec)
    }
    /** 灵敏度变化才应用，避免重复启动时清空已累计的步数 */
    private fun applySensIfChanged() {
        val p = Prefs.gaitParams(this)
        val sig = "${p.steps}-${p.minIv}-${p.maxIv}-${p.absMs}-${p.ratio}-${p.gyro}-${p.accMin}-${p.accMax}-${p.cand}-${p.maxMiss}-${p.idleMs}"
        if (sig == lastSensSig) return
        lastSensSig = sig
        detector.applySensitivity(p.steps, p.minIv, p.maxIv, p.absMs, p.ratio, p.gyro, p.accMin, p.accMax, p.cand, p.maxMiss, p.idleMs)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReminderManager.ensureChannels(this)
        detector = WalkDetector(
            onTrigger = { handleWalkTrigger() },
            onTick = { snap ->
                // 单次求值：先处理挂起再快照，显示与决策用同一结论
                val v0 = IndoorDetector.verdict()
                var fired = false
                // 挂起提醒处理：停走、室内或超时均抑制，仅明确室外才发出
                if (pendingSince > 0L) {
                    val timedOut = SystemClock.elapsedRealtime() - pendingSince >= PENDING_TIMEOUT_MS
                    if (!snap.walking) {
                        pendingSince = 0L
                        pendingSimulation = false
                    } else if (v0 == IndoorDetector.Verdict.INDOOR) {
                        pendingSince = 0L
                        pendingSimulation = false
                    }
                    else if (v0 == IndoorDetector.Verdict.OUTDOOR) {
                        pendingSince = 0L
                        fired = fireNow(pendingSimulation)
                    } else if (timedOut) {
                        pendingSince = 0L
                        pendingSimulation = false
                        IndoorDetector.noteIdle()
                    }
                }
                val withIndoor = snap.copy(
                    indoor = v0 == IndoorDetector.Verdict.INDOOR,
                    indoorPending = pendingSince > 0L,
                    sats = IndoorDetector.satInfo,
                )
                // 未发出才发布：发出时 fire 内重入 tick 已发布更新快照，此处防回写旧值
                if (!fired) {
                    DetectState.snap = withIndoor
                    lastTick = withIndoor
                }
                // 行走驱动 GPS：只在“冷却已过 + 行走中”才开
                // 被室内抑制的触发不消耗冷却，抑制成立后 GPS 会持续开着保温 verdict
                if (withIndoor.walking && (detector.isSimulating() || Prefs.canTrigger(this))) IndoorDetector.noteWalking()
                else IndoorDetector.noteIdle()
                // 发出后不再显示旧室内快照（fire 内重入 tick 已刷成最新，此处防回写）
                renderGuard(withIndoor, withIndoor.indoor && !fired)
            },
        )
        applySensIfChanged()
        sensorMgr = getSystemService(SensorManager::class.java)
        refreshUseState() // 先读真实亮灭屏/锁屏，再决定起传感器与 GPS
        syncSensors()
        refreshIndoorTracking()
        detector.publishState()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            },
        )
        subscribeGms()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 灵敏度切换后重进即生效（仅变化时重置，避免 GMS 回调把累计清掉）
        if (::detector.isInitialized) {
        applySensIfChanged()
            refreshUseState() // 后台进来的启动也要带最新亮锁屏态，GPS 才跟得上
            syncSensors()
            refreshIndoorTracking()
        }
        when (intent?.action) {
            ACTION_SIMULATE -> {
                if (::detector.isInitialized) detector.injectTestBurst()
            }
            ACTION_CANCEL_SIMULATION -> {
                if (::detector.isInitialized) detector.cancelTestBurst()
                pendingSince = 0L
                pendingSimulation = false
                IndoorDetector.noteIdle()
            }
            // 授权后重注册传感器：无身体活动权限时注册的监听收不到事件，必须重新注册
            ACTION_REREGISTER -> {
                refreshUseState()
                sensorMgr?.let { detector.reregister(it, detector.isUsable()) }
            }
            ACTION_GMS_HINT -> {
                if (::detector.isInitialized && detector.isWalkingNow()) handleWalkTrigger()
            }
            ACTION_RESUBSCRIBE -> subscribeGms()
        }
        return if (startForegroundGuard()) START_STICKY else START_NOT_STICKY
    }

    /** 常驻通知：附带实时检测状态 */
    private fun startForegroundGuard(): Boolean {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
            if (Prefs.isIndoorMute(this) && PermissionHelper.isLocationEnough(this)) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        return try {
            ServiceCompat.startForeground(
                this,
                ReminderManager.GUARD_ID,
                buildGuard(getString(R.string.guard_monitoring)),
                type,
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to promote monitoring service", e)
            stopSelf()
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unsupported monitoring service type", e)
            stopSelf()
            false
        }
    }

    private fun refreshGuard(walking: Boolean, run: Int, need: Int, elapsed: Int) {
        if (!Prefs.isEnabled(this)) return
        val text = if (walking) getString(R.string.guard_walking_format, run, need, elapsed)
        else getString(R.string.guard_still_format, run, need)
        notifyGuard(text)
    }

    private fun notifyGuard(text: String) {
        if (text == lastGuardText) return
        lastGuardText = text
        try {
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(ReminderManager.GUARD_ID, buildGuard(text))
        } catch (_: Exception) { }
    }

    private fun buildGuard(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ReminderManager.CH_GUARD)
            .setSmallIcon(com.playlab.headsup.R.drawable.ic_notify)
            .setContentTitle(getString(R.string.guard_running_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun subscribeGms() {
        if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            detector.gmsStatus = WalkDetector.GmsStatus.NO_PERM
            detector.publishState()
            return
        }
        try {
            val transitions = listOf(
                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                DetectedActivity.ON_FOOT,
            ).flatMap { type ->
                listOf(
                    ActivityTransition.Builder().setActivityType(type)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                    ActivityTransition.Builder().setActivityType(type)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
                )
            }
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), gmsCallback())
                .addOnSuccessListener {
                    detector.gmsStatus = WalkDetector.GmsStatus.AVAILABLE
                    detector.publishState()
                }
                .addOnFailureListener { e ->
                    detector.gmsStatus = WalkDetector.GmsStatus.UNAVAILABLE
                    detector.publishState()
                    Log.w(TAG, "GMS Activity Recognition is unavailable; using local sensors", e)
                }
        } catch (e: Exception) {
            detector.gmsStatus = WalkDetector.GmsStatus.UNAVAILABLE
            detector.publishState()
            Log.w(TAG, "GMS Activity Recognition setup failed; using local sensors", e)
        }
    }

    private fun gmsCallback() = PendingIntent.getBroadcast(
        this,
        0,
        Intent(this, ActivityUpdateReceiver::class.java).setAction(ACTION_ACTIVITY_UPDATE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    override fun onDestroy() {
        IndoorDetector.stop()
        try {
            ActivityRecognition.getClient(this).removeActivityTransitionUpdates(gmsCallback())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove GMS Activity Recognition updates", e)
        }
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        try {
            sensorMgr?.let {
                if (::detector.isInitialized) detector.unregister(it)
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val ACTION_SIMULATE = "com.playlab.headsup.action.SIMULATE"
        const val ACTION_CANCEL_SIMULATION = "com.playlab.headsup.action.CANCEL_SIMULATION"
        const val ACTION_REREGISTER = "com.playlab.headsup.action.REREGISTER"
        const val ACTION_GMS_HINT = "com.playlab.headsup.action.GMS_HINT"
        const val ACTION_RESUBSCRIBE = "com.playlab.headsup.action.RESUBSCRIBE"
        const val ACTION_ACTIVITY_UPDATE = "com.playlab.headsup.action.ACTIVITY_UPDATE"
        // 挂起等 GPS 结论的最长等待，超时仍未知时按室内静音策略抑制
        private const val PENDING_TIMEOUT_MS = 15_000L
        private const val TAG = "HeadsUpService"

        fun start(ctx: Context): Boolean {
            return try {
                val i = Intent(ctx, HeadsUpService::class.java)
                ctx.startForegroundService(i)
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to start monitoring service", e)
                false
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Monitoring service start was not allowed", e)
                false
            }
        }

        /** 身体活动权限授予后，重新注册传感器以开始接收事件 */
        fun reregister(ctx: Context) {
            startWithAction(ctx, ACTION_REREGISTER)
        }

        fun resubscribe(ctx: Context) {
            startWithAction(ctx, ACTION_RESUBSCRIBE)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HeadsUpService::class.java))
        }

        /** 模拟步行：走真实检测通路的端到端测试 */
        fun simulate(ctx: Context) {
            startWithAction(ctx, ACTION_SIMULATE)
        }

        fun cancelSimulation(ctx: Context) {
            startWithAction(ctx, ACTION_CANCEL_SIMULATION)
        }

        private fun startWithAction(ctx: Context, action: String): Boolean {
            return try {
                val i = Intent(ctx, HeadsUpService::class.java).setAction(action)
                ctx.startForegroundService(i)
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to send monitoring service action", e)
                false
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Monitoring service action was not allowed", e)
                false
            }
        }
    }
}
