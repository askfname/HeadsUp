package com.headsup.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.headsup.app.MainActivity
import com.headsup.app.R
import com.headsup.app.data.Prefs
import com.headsup.app.detection.DetectState
import com.headsup.app.detection.WalkDetector
import com.headsup.app.reminder.ReminderManager
import com.headsup.app.util.KeepAliveHelper

/**
 * 步行检测前台服务：
 * 主链路 = 本机三源融合检测（STEP_DETECTOR/STEP_COUNTER/加速度计，不依赖 GMS）；
 * GMS ActivityRecognition 降级为加速通道（有回调且亮屏则直接提醒）。
 */
class HeadsUpService : Service() {

    private lateinit var detector: WalkDetector
    private var lastGuardText = ""

    // 屏幕状态：亮屏视为用机
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    detector.screenOn = true
                }
                Intent.ACTION_SCREEN_OFF -> {
                    detector.screenOn = false
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReminderManager.ensureChannels(this)
        detector = WalkDetector(
            onTrigger = { if (Prefs.isEnabled(this)) ReminderManager.fire(this) },
            onTick = { snap ->
                DetectState.snap = snap
                refreshGuard(snap.walking, snap.runLen, snap.runNeed, snap.walkElapsedSec)
            },
        )
        detector.requiredSteps = Prefs.requiredSteps(this)
        detector.screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        getSystemService(SensorManager::class.java)?.let { detector.register(it) }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
    }

    private var needsSubscribe = true // 进程新建后首次需要订阅
    private var gmsSubscribedAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 参数可变（灵敏度切换后重进即生效）
        if (::detector.isInitialized) {
            detector.requiredSteps = Prefs.requiredSteps(this)
        }
        when (intent?.action) {
            ACTION_SIMULATE -> {
                Prefs.resetCooldown(this)
                if (::detector.isInitialized) detector.injectTestBurst()
            }
            // GMS 只是提示：必须经检测器活体确认此刻处于行走状态，才执行 fire
            ACTION_GMS_HINT -> {
                if (Prefs.isEnabled(this) &&
                    ::detector.isInitialized && detector.isWalkingNow()
                ) {
                    ReminderManager.fire(this)
                }
            }
            ACTION_RESUBSCRIBE -> needsSubscribe = true
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

    /** 常驻通知：附带实时检测状态，证明服务存活且检测工作正常 */
    private fun startForegroundGuard() {
        startForeground(ReminderManager.GUARD_ID, buildGuard("正在检测步行中的用机行为…"))
    }

    private fun refreshGuard(walking: Boolean, run: Int, need: Int, elapsed: Int) {
        if (!Prefs.isEnabled(this)) return
        val text = if (walking) "疑似行走 · 连贯步数 $run/$need（已持续 ${elapsed}s）"
        else "静止 · 连贯步数 $run/$need"
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
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("看路提醒运行中")
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
            detector.gmsStatus = "无权限"
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
                .addOnSuccessListener { detector.gmsStatus = "可用" }
                .addOnFailureListener { e ->
                    detector.gmsStatus = "不可用"
                    Log.w(TAG, "GMS ActivityRecognition 不可用，本机检测继续工作: $e")
                }
        } catch (e: Exception) {
            detector.gmsStatus = "不可用"
            Log.w(TAG, "GMS 订阅异常，本机检测继续工作: $e")
        }
    }

    /** 被杀后 30s 拉活 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent("com.headsup.app.action.RESTART_SERVICE").setPackage(packageName),
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
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        try {
            getSystemService(SensorManager::class.java)?.let {
                if (::detector.isInitialized) detector.unregister(it)
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val ACTION_SIMULATE = "com.headsup.app.action.SIMULATE"
        const val ACTION_GMS_HINT = "com.headsup.app.action.GMS_HINT"
        const val ACTION_RESUBSCRIBE = "com.headsup.app.action.RESUBSCRIBE"
        private const val TAG = "HeadsUpService"

        fun start(ctx: Context) {
            val i = Intent(ctx, HeadsUpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
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
