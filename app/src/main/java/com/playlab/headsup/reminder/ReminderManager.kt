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

/** 三种提醒：浮动通知 / 弹窗（悬浮窗）/ 全屏悬浮窗 */
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

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // 取系统动态色（壁纸取色），低版本回退 M3 基准色
    private fun sysColor(ctx: Context, name: String, fallback: Int): Int {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val id = ctx.resources.getIdentifier(name, "color", "android")
                if (id != 0) return ctx.getColor(id)
            } catch (_: Exception) { }
        }
        return fallback
    }

    private class Palette(
        val container: Int, val primary: Int, val primaryContainer: Int,
        val onPrimaryContainer: Int, val onSurface: Int, val onSurfaceVariant: Int, val onPrimary: Int,
    )

    private fun palette(ctx: Context): Palette {
        val dark = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return Palette(
            container = if (dark) sysColor(ctx, "system_neutral1_900", 0xFF2B2930.toInt())
            else sysColor(ctx, "system_neutral1_50", 0xFFF3EDF7.toInt()),
            primary = if (dark) sysColor(ctx, "system_accent1_200", 0xFFD0BCFF.toInt())
            else sysColor(ctx, "system_accent1_600", 0xFF6750A4.toInt()),
            primaryContainer = if (dark) sysColor(ctx, "system_accent1_700", 0xFF4F378B.toInt())
            else sysColor(ctx, "system_accent1_100", 0xFFEADDFF.toInt()),
            onPrimaryContainer = if (dark) sysColor(ctx, "system_accent1_100", 0xFFEADDFF.toInt())
            else sysColor(ctx, "system_accent1_900", 0xFF21005D.toInt()),
            onSurface = if (dark) sysColor(ctx, "system_neutral1_100", 0xFFE6E0E9.toInt())
            else sysColor(ctx, "system_neutral1_900", 0xFF1D1B20.toInt()),
            onSurfaceVariant = if (dark) sysColor(ctx, "system_neutral2_200", 0xFFCAC4D0.toInt())
            else sysColor(ctx, "system_neutral2_700", 0xFF49454F.toInt()),
            onPrimary = if (dark) sysColor(ctx, "system_accent1_900", 0xFF381E72.toInt())
            else sysColor(ctx, "system_accent1_0", 0xFFFFFFFF.toInt()),
        )
    }

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
    fun fire(
        ctx: Context,
        checkIndoor: Boolean = true,
        skipCooldown: Boolean = false,
    ): Boolean {
        if (!skipCooldown && !Prefs.canTrigger(ctx)) return false
        if (!isUsable(ctx)) return false // 灭屏/锁屏兜底：延迟回调到此时已无意义
        // 室内抑制：开关开且 GPS 判室内才拦截，开关关则不用 GPS
        if (checkIndoor && Prefs.isIndoorMute(ctx) && IndoorDetector.isIndoorNow()) return false
        ensureChannels(ctx)
        val shown = when (Prefs.getMode(ctx)) {
            Prefs.MODE_POPUP -> if (showOverlay(ctx)) true else showFloat(ctx)
            Prefs.MODE_FULL -> if (showFullOverlay(ctx)) true else showFloat(ctx)
            else -> showFloat(ctx)
        }
        // 真正发出后才计冷却，避免无权限时被静默吞掉整段冷却
        if (!shown) return false
        if (!skipCooldown) Prefs.markTriggered(ctx)
        vibrate(ctx)
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
        fire(ctx, checkIndoor = false, skipCooldown = true)
    }

    // ---- 方式1：浮动通知（heads-up 横幅） ----
    fun showFloat(ctx: Context): Boolean {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
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
        return try {
            nm.notify(NOTIFY_ID, n)
            true
        } catch (_: Exception) { false }
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
            fun dp(v: Int) = dp(ctx, v)
            val c = palette(ctx)
            val container = c.container
            val primary = c.primary
            val primaryContainer = c.primaryContainer
            val onPrimaryContainer = c.onPrimaryContainer
            val onSurface = c.onSurface
            val onSurfaceVariant = c.onSurfaceVariant
            val onPrimary = c.onPrimary

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

    // ---- 方式3：全屏提醒（全屏悬浮窗，避免后台启动 Activity 限制） ----
    fun showFullOverlay(ctx: Context): Boolean {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return false
        return try {
            val wm = ctx.getSystemService(WindowManager::class.java) ?: return false
            fun dp(v: Int) = dp(ctx, v)
            val c = palette(ctx)
            val container = c.container
            val primary = c.primary
            val primaryContainer = c.primaryContainer
            val onPrimaryContainer = c.onPrimaryContainer
            val onSurface = c.onSurface
            val onSurfaceVariant = c.onSurfaceVariant
            val onPrimary = c.onPrimary

            val root = android.widget.FrameLayout(ctx).apply {
                setBackgroundColor(0x99000000.toInt())
                isClickable = true
            }
            val card = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(32), dp(28), dp(24))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(container)
                }
                elevation = dp(6).toFloat()
            }
            val iconWrap = android.widget.FrameLayout(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(96), dp(96))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(primaryContainer)
                }
            }
            val icon = android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_notify)
                imageTintList = android.content.res.ColorStateList.valueOf(onPrimaryContainer)
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(68), dp(68)).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = dp(14)
                }
            }
            iconWrap.addView(icon)
            val title = android.widget.TextView(ctx).apply {
                text = randomTitle(ctx)
                textSize = 26f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
                setPadding(0, dp(20), 0, 0)
            }
            val content = android.widget.TextView(ctx).apply {
                text = content(ctx)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(8), 0, 0)
            }
            val btn = android.widget.TextView(ctx).apply {
                text = ctx.getString(R.string.reminder_confirm)
                textSize = 16f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onPrimary)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(26).toFloat()
                    setColor(primary)
                }
                isClickable = true
                isFocusable = true
            }
            card.addView(iconWrap)
            card.addView(title)
            card.addView(content)
            card.addView(btn, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(24) })
            root.addView(card, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { leftMargin = dp(28); rightMargin = dp(28) })
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(root, params)
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(220).start()
            val main = Handler(Looper.getMainLooper())
            var dismissed = false
            fun dismiss() {
                if (dismissed) return
                dismissed = true
                main.removeCallbacksAndMessages(null)
                try {
                    root.animate().alpha(0f).setDuration(180)
                        .withEndAction { try { wm.removeView(root) } catch (_: Exception) { } }
                        .start()
                } catch (_: Exception) {
                    try { wm.removeViewImmediate(root) } catch (_: Exception) { }
                }
            }
            root.setOnClickListener { dismiss() }
            btn.setOnClickListener { dismiss() }
            // 自动消失，避免长时间遮挡屏幕
            main.postDelayed({ dismiss() }, 15_000)
            true
        } catch (_: Exception) {
            false
        }
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
