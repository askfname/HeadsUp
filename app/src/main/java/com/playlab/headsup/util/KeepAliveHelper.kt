package com.playlab.headsup.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.playlab.headsup.R

/** 后台限制设置引导。系统和厂商策略不能由应用自行绕过 */
object KeepAliveHelper {
    fun ignoringBattery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    @SuppressLint("BatteryLife")
    fun requestBatteryWhitelist(ctx: Context) {
        try {
            ctx.startActivity(batteryWhitelistIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    /** 电池白名单意图（供设置返回回调启动，返回后可立即刷新状态） */
    fun batteryWhitelistIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    /** 跳转厂商自启动管理页（小米/华为/OPPO/vivo/一加/魅族） */
    fun openAutoStart(ctx: Context) {
        val targets = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
        )
        for (c in targets) {
            try {
                ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* 换下一个 */ }
        }
        try {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) { }
    }

    // 是否已知需手动允许“高后台耗电”的厂商：已知才显示直达入口，未知仅文字引导
    fun isHighBgSupported(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
            m.contains("blackshark") || m.contains("huawei") || m.contains("honor") ||
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ||
            m.contains("vivo") || m.contains("iqoo") ||
            m.contains("meizu") || m.contains("samsung")
    }

    // 逐个尝试厂商“高后台耗电”页：显式跳转逐个试错，全部失败返回 false
    fun openHighBackground(ctx: Context): Boolean {
        for (intent in highBgCandidates(ctx)) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) { /* 换下一个 */ }
        }
        return false
    }

    // 按厂商组装候选页：同厂商多版本并列，调用方无需关心 ROM 差异
    private fun highBgCandidates(ctx: Context): List<Intent> {
        val pkg = ctx.packageName
        val label = try {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) { pkg }
        fun explicit(pkgName: String, cls: String, withPkg: Boolean = false) =
            Intent().setComponent(ComponentName(pkgName, cls)).apply {
                if (withPkg) {
                    putExtra("package_name", pkg)
                    putExtra("package_label", label)
                }
            }
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") || m.contains("blackshark") -> listOf(
                explicit("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity", true),
                explicit("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity", true),
                explicit("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                explicit("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                explicit("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                explicit("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
                explicit("com.honor.systemmanager", "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                explicit("com.honor.systemmanager", "com.honor.systemmanager.optimize.process.ProtectActivity"),
            )
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> listOf(
                explicit("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                explicit("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"),
                explicit("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                explicit("com.oplus.battery", "com.oplus.battery.performance.PowerConsumptionActivity"),
                explicit("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                explicit("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            )
            m.contains("vivo") || m.contains("iqoo") -> listOf(
                explicit("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                explicit("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                explicit("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                explicit("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            )
            m.contains("meizu") -> listOf(
                explicit("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
            )
            m.contains("samsung") -> listOf(
                explicit("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                explicit("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
            else -> emptyList()
        }
    }

    fun openAppSettings(ctx: Context) {
        try {
            ctx.startActivity(appDetailsIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    /** 应用详情页意图（供设置返回回调启动，返回后可立即刷新状态） */
    fun appDetailsIntent(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    /** Follow system language: device hints are localized. Keep the no-arg overload out; callers pass Context. */
    fun deviceHint(ctx: Context): String = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi" -> ctx.getString(R.string.device_hint_xiaomi)
        "huawei", "honor" -> ctx.getString(R.string.device_hint_huawei)
        "oppo", "realme", "oneplus" -> ctx.getString(R.string.device_hint_oppo)
        "vivo", "iqoo" -> ctx.getString(R.string.device_hint_vivo)
        else -> ctx.getString(R.string.device_hint_default)
    }

    // 分厂商“后台配置 / 高后台耗电”路径：显示在自启动路径下方，子品牌按包含匹配
    fun highBgHint(ctx: Context): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                m.contains("blackshark") -> ctx.getString(R.string.device_highbg_xiaomi)
            m.contains("huawei") || m.contains("honor") -> ctx.getString(R.string.device_highbg_huawei)
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                ctx.getString(R.string.device_highbg_oppo)
            m.contains("vivo") || m.contains("iqoo") -> ctx.getString(R.string.device_highbg_vivo)
            else -> ctx.getString(R.string.device_highbg_default)
        }
    }

    // 卡片首行厂商名：已知分组显示本地化名，未知返回 null（调用方不显示该行）
    fun deviceVendor(ctx: Context): String? {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                m.contains("blackshark") -> ctx.getString(R.string.device_vendor_xiaomi)
            m.contains("huawei") || m.contains("honor") -> ctx.getString(R.string.device_vendor_huawei)
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                ctx.getString(R.string.device_vendor_oppo)
            m.contains("vivo") || m.contains("iqoo") -> ctx.getString(R.string.device_vendor_vivo)
            else -> null
        }
    }
}
