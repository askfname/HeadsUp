package com.playlab.headsup.data

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

    /** 灵敏度三档：0=灵敏/ 1=标准 / 2=严格 */
    fun getSensitivity(ctx: Context) = sp(ctx).getInt("sensitivity", 1)
    fun setSensitivity(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("sensitivity", v.coerceIn(0, 2)).apply()

    /** 灵敏度联动参数：触发步数 / 步频窗 / 节律容差 / 动作门限 / 显示门限 / 单次容错 / 停走延迟 */
    data class GaitParams(
        val steps: Int,
        val minIv: Long,
        val maxIv: Long,
        val absMs: Long,
        val ratio: Float,
        val gyro: Float,
        val accMin: Float,
        val accMax: Float,
        val cand: Int, // 显示“疑似行走”所需连贯步数
        val maxMiss: Int, // 允许连续几次不规律步不断链
        val idleMs: Long, // 末步后多久判停走
    )

    fun gaitParams(ctx: Context) = when (getSensitivity(ctx)) {
        0 -> GaitParams(steps = 8, minIv = 300L, maxIv = 2000L, absMs = 320L, ratio = 0.6f, gyro = 1.8f, accMin = 0.15f, accMax = 5.0f, cand = 3, maxMiss = 2, idleMs = 4200L)
        2 -> GaitParams(steps = 16, minIv = 400L, maxIv = 1500L, absMs = 200L, ratio = 0.35f, gyro = 1.0f, accMin = 0.3f, accMax = 3.2f, cand = 6, maxMiss = 1, idleMs = 3000L)
        else -> GaitParams(steps = 12, minIv = 350L, maxIv = 1700L, absMs = 260L, ratio = 0.5f, gyro = 1.4f, accMin = 0.25f, accMax = 4.2f, cand = 4, maxMiss = 1, idleMs = 3400L)
    }

    fun requiredSteps(ctx: Context) = gaitParams(ctx).steps

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
