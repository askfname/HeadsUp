package com.headsup.app.data

import android.content.Context

/** 存储：开关 / 提醒方式 / 冷却间隔 */
object Prefs {
    const val MODE_FLOAT = 0   // 浮动通知（heads-up）
    const val MODE_POPUP = 1   // 弹窗提醒（悬浮窗）
    const val MODE_FULL = 2    // 全屏提醒

    private const val FILE = "headsup"
    private const val K_ENABLED = "enabled"
    private const val K_MODE = "mode"
    private const val K_COOLDOWN = "cooldown"
    private const val K_LAST = "last_trigger"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = sp(ctx).getBoolean(K_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_ENABLED, v).apply()

    fun getMode(ctx: Context) = sp(ctx).getInt(K_MODE, MODE_FLOAT)
    fun setMode(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_MODE, v).apply()

    fun getCooldown(ctx: Context) = sp(ctx).getInt(K_COOLDOWN, 90)
    fun setCooldown(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(K_COOLDOWN, v.coerceIn(30, 300)).apply()

    /** 灵敏度：0=快速（连续10步节律一致，约6~8s），1=标准（连续14步，约9~12s） */
    fun getSensitivity(ctx: Context) = sp(ctx).getInt("sensitivity", 0)
    fun setSensitivity(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("sensitivity", v.coerceIn(0, 1)).apply()

    fun requiredSteps(ctx: Context) = if (getSensitivity(ctx) == 0) 10 else 14

    /** 冷却是否已过 */
    fun canTrigger(ctx: Context): Boolean {
        val last = sp(ctx).getLong(K_LAST, 0)
        return System.currentTimeMillis() - last > getCooldown(ctx) * 1000L
    }

    fun markTriggered(ctx: Context) =
        sp(ctx).edit().putLong(K_LAST, System.currentTimeMillis()).apply()

    fun resetCooldown(ctx: Context) =
        sp(ctx).edit().putLong(K_LAST, 0).apply()
}
