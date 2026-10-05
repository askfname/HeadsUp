package com.playlab.headsup.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
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
import com.playlab.headsup.util.KeepAliveHelper
import com.playlab.headsup.util.PermissionHelper

/**
 * 步行检测前台服务：
 * 主链路 = 本机 STEP_DETECTOR 检测（不依赖 GMS）
 * GMS ActivityRecognition 降级为提示通道（须经活体确认才提醒）
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
        if (!detector.isUsable()) pendingSince = 0L
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

    /** 步行触发统一入口：室内明确抑制；室外立即发；未知挂起等 GPS 结论 */
    private fun handleWalkTrigger() {
        if (!Prefs.isEnabled(this) || !isUsableNow() || !::detector.isInitialized) return
        if (!Prefs.canTrigger(this)) return // 冷却中直接丢弃，不挂起
        // 模拟步行走旧通路：未知按室外立即发，保证测试即时反馈
        if (detector.isSimulating()) {
            fireNow()
            return
        }
        if (!Prefs.isIndoorMute(this)) {
            fireNow()
            return
        }
        when (IndoorDetector.verdict()) {
            IndoorDetector.Verdict.INDOOR -> return // 抑制，不计冷却
            IndoorDetector.Verdict.OUTDOOR -> fireNow()
            IndoorDetector.Verdict.UNKNOWN -> {
                // 定位不可用则无等待意义，直接放行
                if (!IndoorDetector.isLocationOn(this) ||
                    !PermissionHelper.isLocationEnough(this)
                ) {
                    fireNow()
                    return
                }
                if (pendingSince == 0L) pendingSince = SystemClock.elapsedRealtime()
                IndoorDetector.noteWalking() // 确保 GPS 开着等结论，结论到后发/抑再关
            }
        }
    }

    /** 真实发出：闩锁 UI 同步；发出后冷却期内 verdict 无用，延迟关 GPS */
    private fun fireNow(): Boolean {
        if (ReminderManager.fire(this)) {
            detector.noteFired()
            // 发出后冷却期内 verdict 用不上，关 GPS（10s 延迟，冷却过期 tick 会按需再开）
            pendingSince = 0L
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
    /** 灵敏度变化才应用：GMS 回调每次都走 start，直接重置会把刚攒的步数清掉 */
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
                // 单次求值：显示与挂起决策用同一结论，避免并发翻转导致显示室内也发送提醒
                val v0 = IndoorDetector.verdict()
                val withIndoor = snap.copy(
                    indoor = v0 == IndoorDetector.Verdict.INDOOR,
                    indoorPending = pendingSince > 0L,
                    sats = IndoorDetector.satInfo,
                )
                DetectState.snap = withIndoor
                lastTick = withIndoor
                var fired = false
                // 挂起提醒处理：停走取消；室内抑制；室外或超时发出
                if (pendingSince > 0L) {
                    val v = IndoorDetector.verdict()
                    val timedOut = SystemClock.elapsedRealtime() - pendingSince >= PENDING_TIMEOUT_MS
                    if (!withIndoor.walking) pendingSince = 0L // 停走取消
                    else if (v == IndoorDetector.Verdict.INDOOR) pendingSince = 0L // 抑制
                    else if (v == IndoorDetector.Verdict.OUTDOOR || timedOut) {
                        pendingSince = 0L
                        fired = fireNow() // 超时按室外放行；fire 内复核可用性与冷却
                    }
                }
                // 行走驱动 GPS：只在“冷却已过 + 行走中”才开
                // 被室内抑制的触发不消耗冷却，抑制成立后 GPS 会持续开着保温 verdict
                if (withIndoor.walking && Prefs.canTrigger(this)) IndoorDetector.noteWalking()
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
    }

    private var needsSubscribe = true // 进程新建后首次需要订阅
    private var gmsSubscribedAt = 0L

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
                Prefs.resetCooldown(this)
                if (::detector.isInitialized) detector.injectTestBurst()
            }
            // GMS 只是提示：同样走统一触发入口（活体+位置结论都在内部复核）
            ACTION_GMS_HINT -> {
                if (::detector.isInitialized && detector.isWalkingNow()) handleWalkTrigger()
            }
            ACTION_RESUBSCRIBE -> needsSubscribe = true
            // 授权后重注册传感器：无身体活动权限时注册的监听收不到事件，必须重新注册
            ACTION_REREGISTER -> {
                refreshUseState()
                sensorMgr?.let { detector.reregister(it, detector.isUsable()) }
            }
        }
        startForegroundGuard()
        // 节流订阅（避免重订阅自激循环）
        val now = SystemClock.elapsedRealtime()
        if (needsSubscribe || now - gmsSubscribedAt > 30 * 60 * 1000L) {
            needsSubscribe = false
            gmsSubscribedAt = now
            subscribeGms()
        }
        KeepAliveHelper.scheduleKeepAlive(this)
        return START_STICKY
    }

    /** 常驻通知：附带实时检测状态 */
    private fun startForegroundGuard() {
        startForeground(ReminderManager.GUARD_ID, buildGuard(getString(R.string.guard_monitoring)))
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

    /** GMS 加速通道：成功/失败都记录状态，失败不影响本机主链路 */
    private fun subscribeGms() {
        if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            detector.gmsStatus = WalkDetector.GmsStatus.NO_PERM
            return
        }
        try {
            val types = listOf(
                DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT,
            )
            val transitions = types.flatMap {
                listOf(
                    ActivityTransition.Builder().setActivityType(it)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                    ActivityTransition.Builder().setActivityType(it)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
                )
            }
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(this, ActivityUpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pi)
                .addOnSuccessListener { detector.gmsStatus = WalkDetector.GmsStatus.AVAILABLE }
                .addOnFailureListener { e ->
                    detector.gmsStatus = WalkDetector.GmsStatus.UNAVAILABLE
                    Log.w(TAG, "GMS ActivityRecognition 不可用，本机检测继续工作: $e")
                }
        } catch (e: Exception) {
            detector.gmsStatus = WalkDetector.GmsStatus.UNAVAILABLE
            Log.w(TAG, "GMS 订阅异常，本机检测继续工作: $e")
        }
    }

    /** 进程被杀后自启 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent("com.playlab.headsup.action.RESTART_SERVICE").setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT >= 31 && am?.canScheduleExactAlarms() == false) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 30_000, pi)
            } else {
                am?.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 30_000, pi)
            }
        } catch (_: Exception) { }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        IndoorDetector.stop()
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
        const val ACTION_GMS_HINT = "com.playlab.headsup.action.GMS_HINT"
        const val ACTION_RESUBSCRIBE = "com.playlab.headsup.action.RESUBSCRIBE"
        const val ACTION_REREGISTER = "com.playlab.headsup.action.REREGISTER"
        // 挂起等 GPS 结论的最长等待时间（超时按室外放行，避免漏提醒）
        private const val PENDING_TIMEOUT_MS = 20_000L
        private const val TAG = "HeadsUpService"

        fun start(ctx: Context) {
            val i = Intent(ctx, HeadsUpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** 身体活动权限授予后：传感器必须重注册才会来事件，只重订阅 GMS 不够 */
        fun reregister(ctx: Context) {
            try {
                val i = Intent(ctx, HeadsUpService::class.java).setAction(ACTION_REREGISTER)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HeadsUpService::class.java))
        }

        /** 权限后授予等场景：强制重新订阅 GMS（平时节流，不必每次 start 都订阅） */
        fun resubscribe(ctx: Context) {
            val i = Intent(ctx, HeadsUpService::class.java).setAction(ACTION_RESUBSCRIBE)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** 模拟步行：走真实检测通路的端到端测试 */
        fun simulate(ctx: Context) {
            val i = Intent(ctx, HeadsUpService::class.java).setAction(ACTION_SIMULATE)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
