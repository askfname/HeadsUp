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
import com.playlab.headsup.util.IndoorDetector

/** 三种提醒：浮动通知 / 弹窗（悬浮窗）/ 全屏 */
object ReminderManager {
    const val CH_ALERT = "headsup_alert"
    const val CH_GUARD = "headsup_guard"
    const val NOTIFY_ID = 1001
    const val GUARD_ID = 2001

    // Localized titles follow the system language via string-array resources.
    fun randomTitle(ctx: Context): String {
        val arr = try {
            ctx.resources.getStringArray(R.array.reminder_titles)
        } catch (_: Exception) {
            null
        }
        if (arr.isNullOrEmpty()) return ctx.getString(R.string.guard_running_title)
        return arr.random()
    }

    private fun content(ctx: Context): String = ctx.getString(R.string.reminder_content)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_GUARD, ctx.getString(R.string.channel_guard), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, ctx.getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_alert_desc)
                // 关系统通知的震动
                enableVibration(false)
                vibrationPattern = null
            }
        )
        // 存量渠道：同样关闭系统震动
        try {
            nm.getNotificationChannel(CH_ALERT)?.let {
                if (it.shouldVibrate()) {
                    it.enableVibration(false)
                    it.vibrationPattern = null
                    nm.createNotificationChannel(it)
                }
            }
        } catch (_: Exception) { }
    }

    // No-arg overload kept only for compat; callers should prefer randomTitle(ctx) so the
    // title follows the system language. Falls back to the English name for safety.
    fun randomTitle(): String = "HeadsUp"

    /** 统一入口：按用户选择的模式提醒，返回是否真正发出（冷却/灭屏/锁屏会被拦截） */
    fun fire(ctx: Context, checkIndoor: Boolean = true): Boolean {
        if (!Prefs.canTrigger(ctx)) return false
        if (!isUsable(ctx)) return false // 灭屏/锁屏兜底：延迟回调到此时已无意义
        // 室内抑制：开关开且 GPS 判室内才拦截，开关关则不用 GPS
        if (checkIndoor && Prefs.isIndoorMute(ctx) && IndoorDetector.isIndoorNow()) return false
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

    /** 供设置页测试：跳过冷却与室内判断，直接提醒 */
    fun test(ctx: Context) {
        Prefs.resetCooldown(ctx)
        fire(ctx, checkIndoor = false)
    }

    // ---- 方式1：浮动通知（heads-up 横幅） ----
    fun showFloat(ctx: Context) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(randomTitle(ctx))
            .setContentText(content(ctx))
            .setSubText(ctx.getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.notif_action_ack), dismissPI(ctx))
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    private fun dismissPI(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 2, Intent("com.playlab.headsup.action.DISMISS").setPackage(ctx.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ---- 方式2：弹窗（Material You 悬浮卡片） ----
    fun showOverlay(ctx: Context): Boolean {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return false
        try {
            val wm = ctx.getSystemService(WindowManager::class.java) ?: return false
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            // 取系统动态色（壁纸取色），低版本回退 M3 基准色
            fun sys(name: String, fallback: Int): Int {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        val id = ctx.resources.getIdentifier(name, "color", "android")
                        if (id != 0) return ctx.getColor(id)
                    } catch (_: Exception) { }
                }
                return fallback
            }
            // 深色适配
            val dark = (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val container = if (dark) sys("system_neutral1_900", 0xFF2B2930.toInt())
            else sys("system_neutral1_50", 0xFFF3EDF7.toInt())
            val primary = if (dark) sys("system_accent1_200", 0xFFD0BCFF.toInt())
            else sys("system_accent1_600", 0xFF6750A4.toInt())
            val primaryContainer = if (dark) sys("system_accent1_700", 0xFF4F378B.toInt())
            else sys("system_accent1_100", 0xFFEADDFF.toInt())
            val onPrimaryContainer = if (dark) sys("system_accent1_100", 0xFFEADDFF.toInt())
            else sys("system_accent1_900", 0xFF21005D.toInt())
            val onSurface = if (dark) sys("system_neutral1_100", 0xFFE6E0E9.toInt())
            else sys("system_neutral1_900", 0xFF1D1B20.toInt())
            val onSurfaceVariant = if (dark) sys("system_neutral2_200", 0xFFCAC4D0.toInt())
            else sys("system_neutral2_700", 0xFF49454F.toInt())
            val onPrimary = if (dark) sys("system_accent1_900", 0xFF381E72.toInt())
            else sys("system_accent1_0", 0xFFFFFFFF.toInt())

            // 外层透明容器
            val root = android.widget.FrameLayout(ctx).apply {
                clipChildren = false
                clipToPadding = false
                setPadding(dp(16), dp(12), dp(16), dp(16))
            }
            // 卡片主体：大圆角 + 阴影
            val card = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(16))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(container)
                }
                elevation = dp(2).toFloat()
            }
            // 上行：图标 + 标题文案
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val iconWrap = android.widget.FrameLayout(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(primaryContainer)
                }
            }
            val icon = android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_notify)
                imageTintList = android.content.res.ColorStateList.valueOf(onPrimaryContainer)
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = dp(10)
                }
            }
            iconWrap.addView(icon)
            val texts = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { leftMargin = dp(16) }
            }
            val title = android.widget.TextView(ctx).apply {
                text = randomTitle(ctx)
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
            }
            val content = android.widget.TextView(ctx).apply {
                text = content(ctx)
                textSize = 14f
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(4), 0, 0)
            }
            texts.addView(title)
            texts.addView(content)
            row.addView(iconWrap)
            row.addView(texts)
            val actionRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(dp(64), dp(16), 0, 0)
            }
            val btn = android.widget.TextView(ctx).apply {
                text = ctx.getString(R.string.notif_action_ack)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onPrimary)
                setPadding(dp(24), dp(10), dp(24), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    setColor(primary)
                }
                isClickable = true
                isFocusable = true
            }
            actionRow.addView(btn)
            card.addView(row)
            card.addView(actionRow)
            root.addView(card, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP; y = dp(8) }
            wm.addView(root, params)
            // 弹出动画：下滑 + 淡入 + 微缩放
            card.alpha = 0f
            card.translationY = -dp(24).toFloat()
            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            // 消失动画后移除，避免闪切
            val main = Handler(Looper.getMainLooper())
            var dismissed = false
            fun dismiss() {
                if (dismissed) return
                dismissed = true
                main.removeCallbacksAndMessages(null)
                try {
                    card.animate().alpha(0f).translationY(-dp(16).toFloat())
                        .scaleX(0.97f).scaleY(0.97f).setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction { try { wm.removeView(root) } catch (_: Exception) { } }
                        .start()
                } catch (_: Exception) {
                    try { wm.removeViewImmediate(root) } catch (_: Exception) { }
                }
            }
            btn.setOnClickListener { dismiss() }
            main.postDelayed({ dismiss() }, 10_000)
            return true
        } catch (_: Exception) { return false }
    }

    // ---- 方式3：全屏提醒 ----
    fun showFullScreen(ctx: Context) {
        ctx.startActivity(Intent(ctx, ReminderActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }

    // 震动提醒
    private fun vibrate(ctx: Context) {
        if (!Prefs.isVibrate(ctx)) return
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31)
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 250, 350), -1))
        } catch (_: Exception) { }
    }
}
