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

    /** 室内不提醒：开 + GPS 授权才抑制；关则完全不用 GPS，走正常逻辑 */
    fun isIndoorMute(ctx: Context) = sp(ctx).getBoolean("indoor_mute", false)
    fun setIndoorMute(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("indoor_mute", v).apply()

    /** 提醒时震动：默认开 */
    fun isVibrate(ctx: Context) = sp(ctx).getBoolean("vibrate", true)
    fun setVibrate(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("vibrate", v).apply()

    /** 室内判断参数：只判室内，达阈值即室内，其余按室外 */
    data class IndoorParams(
        val visIndoor: Int = 7, // 室内强星数上限
        val ratioIndoor: Float = 0.4f, // 室内占比上限
        val gpsAcc: Float = 10f, // 精度优于此值按室外（越小越易判室内）
        val visCn0: Float = 25f, // 强星门限（越小强星越多，越难判室内）
    )

    private const val K_IN_VIS_IN = "indoor_c_vis_in"
    private const val K_IN_RATIO_IN = "indoor_c_ratio_in"
    private const val K_IN_GPS_ACC = "indoor_c_gps_acc"
    private const val K_IN_VIS_CN0 = "indoor_c_vis_cn0"

    // 旧版参数键：仅用于清理
    private const val K_IN_VIS_OUT_OLD = "indoor_c_vis_out"
    private const val K_IN_FIX_OUT_OLD = "indoor_c_fix_out"
    private const val K_IN_RATIO_OUT_OLD = "indoor_c_ratio_out"
    private const val K_IN_CONFIRM_OLD = "indoor_c_confirm"
    private const val K_IN_FIX_CN0_OLD = "indoor_c_fix_cn0"

    // 有效参数：自定义缺省项回落默认
    fun getIndoorParams(ctx: Context): IndoorParams {
        val s = sp(ctx)
        val d = IndoorParams()
        return IndoorParams(
            visIndoor = if (s.contains(K_IN_VIS_IN)) s.getInt(K_IN_VIS_IN, d.visIndoor) else d.visIndoor,
            ratioIndoor = if (s.contains(K_IN_RATIO_IN)) s.getFloat(K_IN_RATIO_IN, d.ratioIndoor) else d.ratioIndoor,
            gpsAcc = if (s.contains(K_IN_GPS_ACC)) s.getFloat(K_IN_GPS_ACC, d.gpsAcc) else d.gpsAcc,
            visCn0 = if (s.contains(K_IN_VIS_CN0)) s.getFloat(K_IN_VIS_CN0, d.visCn0) else d.visCn0,
        )
    }

    // 无任何自定义时返回 null（调用方用默认）
    fun getIndoorCustomOrNull(ctx: Context): IndoorParams? =
        if (hasIndoorCustom(ctx)) getIndoorParams(ctx) else null

    fun hasIndoorCustom(ctx: Context): Boolean {
        val s = sp(ctx)
        return s.contains(K_IN_VIS_IN) || s.contains(K_IN_RATIO_IN) ||
            s.contains(K_IN_GPS_ACC) || s.contains(K_IN_VIS_CN0)
    }

    // null 表示该项恢复默认；全 null 等同清空
    fun saveIndoorCustom(
        ctx: Context,
        visIndoor: Int?, ratioIndoor: Float?, gpsAcc: Float?,
        visCn0: Float? = null,
    ) {
        val e = sp(ctx).edit()
        if (visIndoor == null) e.remove(K_IN_VIS_IN) else e.putInt(K_IN_VIS_IN, visIndoor)
        if (ratioIndoor == null) e.remove(K_IN_RATIO_IN) else e.putFloat(K_IN_RATIO_IN, ratioIndoor)
        if (gpsAcc == null) e.remove(K_IN_GPS_ACC) else e.putFloat(K_IN_GPS_ACC, gpsAcc)
        if (visCn0 == null) e.remove(K_IN_VIS_CN0) else e.putFloat(K_IN_VIS_CN0, visCn0)
        e.remove(K_IN_VIS_OUT_OLD).remove(K_IN_FIX_OUT_OLD)
            .remove(K_IN_RATIO_OUT_OLD).remove(K_IN_CONFIRM_OLD)
            .remove(K_IN_FIX_CN0_OLD)
        e.apply()
    }

    fun clearIndoorParams(ctx: Context) {
        sp(ctx).edit()
            .remove(K_IN_VIS_IN).remove(K_IN_RATIO_IN)
            .remove(K_IN_GPS_ACC).remove(K_IN_VIS_CN0)
            .remove(K_IN_VIS_OUT_OLD).remove(K_IN_FIX_OUT_OLD)
            .remove(K_IN_RATIO_OUT_OLD).remove(K_IN_CONFIRM_OLD)
            .remove(K_IN_FIX_CN0_OLD)
            .apply()
    }

    /** 位置兼容模式：系统不提供始终允许入口时前台即够用（API29 并申确认后置位） */
    fun isLocationCompat(ctx: Context) = sp(ctx).getBoolean("location_compat", false)
    fun setLocationCompat(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("location_compat", v).apply()

    /** 申请过的权限：系统不再弹窗（不再询问）时直接引导去设置 */
    fun wasAsked(ctx: Context, perm: String) =
        sp(ctx).getStringSet("asked_perms", emptySet()).orEmpty().contains(perm)

    fun markAsked(ctx: Context, perm: String) {
        val cur = sp(ctx).getStringSet("asked_perms", emptySet()).orEmpty().toMutableSet()
        if (cur.add(perm)) sp(ctx).edit().putStringSet("asked_perms", cur).apply()
    }

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
