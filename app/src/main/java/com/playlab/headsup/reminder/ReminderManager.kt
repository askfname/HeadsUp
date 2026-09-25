package com.playlab.headsup.reminder

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.playlab.headsup.MainActivity
import com.playlab.headsup.R
import com.playlab.headsup.data.Prefs

/** 三种提醒：浮动通知 / 弹窗（悬浮窗）/ 全屏 */
object ReminderManager {
    const val CH_ALERT = "headsup_alert"
    const val CH_GUARD = "headsup_guard"
    const val NOTIFY_ID = 1001
    const val GUARD_ID = 2001

    // 随机标题
    private val TITLES = listOf("抬头看看", "注意脚下", "小心一点", "环顾四周", "别只看手机", "小心台阶", "注意安全")
    private const val CONTENT = "走路时请少看手机，注意周围环境"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_GUARD, "步行守护（常驻）", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "看路提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "走路玩手机时的提醒"
                enableVibration(true)
            }
        )
    }

    fun randomTitle() = TITLES.random()

    /** 统一入口：按用户选择的模式提醒，返回是否真正发出（冷却/灭屏/锁屏会被拦截） */
    fun fire(ctx: Context): Boolean {
        if (!Prefs.canTrigger(ctx)) return false
        if (!isUsable(ctx)) return false // 灭屏/锁屏兜底：延迟回调到此时已无意义
        Prefs.markTriggered(ctx)
        ensureChannels(ctx)
        vibrate(ctx)
        when (Prefs.getMode(ctx)) {
            Prefs.MODE_POPUP -> if (!showOverlay(ctx)) showFloat(ctx)
            Prefs.MODE_FULL -> showFullScreen(ctx)
            else -> showFloat(ctx)
        }
        return true
    }

    /** 最终门：仅亮屏 + 已解锁才提醒（锁屏/灭屏走动不打扰，口袋由服务层距离感应器拦截） */
    fun isUsable(ctx: Context): Boolean {
        val interactive = ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (!interactive) return false
        if (ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false
        return true
    }

    /** 供设置页测试：跳过冷却直接提醒 */
    fun test(ctx: Context) {
        Prefs.resetCooldown(ctx)
        fire(ctx)
    }

    // ---- 方式1：浮动通知（heads-up 横幅） ----
    fun showFloat(ctx: Context) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val full = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, ReminderActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle(randomTitle())
            .setContentText(CONTENT)
            .setSubText("看路提醒")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(full, false) // 锁屏时才全屏，平时只浮动横幅
            .addAction(0, "知道了", dismissPI(ctx))
            .addAction(0, "没在走路", dismissPI(ctx))
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    private fun dismissPI(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 2, Intent("com.playlab.headsup.action.DISMISS").setPackage(ctx.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ---- 方式2：弹窗（悬浮窗原生卡片，服务上下文可用，无需 Compose Lifecycle） ----
    fun showOverlay(ctx: Context): Boolean {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return false
        try {
            val wm = ctx.getSystemService(WindowManager::class.java) ?: return false
            val card = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(40, 36, 40, 36)
                setBackgroundColor(0xFFF3EDF7.toInt())
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = android.widget.TextView(ctx)
            tv.text = "${randomTitle()}\n$CONTENT"
            tv.textSize = 15f
            tv.setTextColor(0xFF1D1B20.toInt())
            tv.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val btn = android.widget.Button(ctx)
            btn.text = "知道了"
            card.addView(tv)
            card.addView(btn)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // 不抢焦点、不点亮锁屏
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP; y = 80 }
            wm.addView(card, params)
            val close = { try { wm.removeViewImmediate(card) } catch (_: Exception) { } }
            btn.setOnClickListener { close() }
            Handler(Looper.getMainLooper()).postDelayed({ close() }, 10_000)
            return true
        } catch (_: Exception) { return false }
    }

    // ---- 方式3：全屏提醒 ----
    fun showFullScreen(ctx: Context) {
        ctx.startActivity(Intent(ctx, ReminderActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }

    private fun vibrate(ctx: Context) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31)
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }
}
