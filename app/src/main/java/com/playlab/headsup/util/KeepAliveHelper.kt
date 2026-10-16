package com.playlab.headsup.util

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        // 定制 ROM 可能删减该 Activity，逐级降级到电池设置 / 应用详情
        if (tryStart(ctx, batteryWhitelistIntent(ctx))) return
        if (tryStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppSettings(ctx)
    }

    /** 电池白名单意图（供设置返回回调启动，返回后可立即刷新状态） */
    fun batteryWhitelistIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    // 自启动：本机厂商优先，其余全量试错（兜底管家首页/应用详情/系统设置）
    fun openAutoStart(ctx: Context): Boolean {
        for (intent in orderedAutoStart(ctx)) {
            if (tryStart(ctx, intent)) return true
        }
        // 具体 Activity 改名时退到管家首页，用户可手动找到自启动入口
        for (pkg in autoStartManagerPkgsOrdered()) {
            try {
                val home = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (home != null) {
                    ctx.startActivity(home)
                    return true
                }
            } catch (_: Exception) { /* 换下一个 */ }
        }
        if (tryStart(ctx, appDetailsIntent(ctx))) return true
        return tryStart(ctx, Intent(Settings.ACTION_SETTINGS))
    }

    // 通用启动：先校验可解析，避免在缺失机型上抛异常刷屏
    private fun tryStart(ctx: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Android 11+ 未声明 queries 时 resolve 可能返回 null，仍直接尝试启动
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false // 部分 ROM 导出限制（如 OPPO 需专用权限）
        } catch (_: Exception) {
            false
        }
    }

    private fun canResolve(ctx: Context, intent: Intent): Boolean {
        return try {
            ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        } catch (_: Exception) { false }
    }

    private data class Tagged(val tags: Set<String>, val intent: Intent)

    private fun explicit(pkgName: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkgName, cls))

    private fun brandTags(): Set<String> {
        val m = Build.MANUFACTURER.lowercase()
        val tags = mutableSetOf<String>()
        when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                m.contains("blackshark") -> tags.add("xiaomi")
            m.contains("huawei") -> tags.add("huawei")
            m.contains("honor") -> tags.add("honor")
            m.contains("oppo") || m.contains("realme") -> tags.add("oppo")
            m.contains("oneplus") -> tags.add("oneplus")
            m.contains("vivo") || m.contains("iqoo") -> tags.add("vivo")
        }
        // 子品牌/代工机 MANUFACTURER 不可靠，BRAND 辅助归属
        val b = Build.BRAND.lowercase()
        if (b.contains("redmi") || b.contains("poco") || b.contains("blackshark")) tags.add("xiaomi")
        if (b.contains("honor")) tags.add("honor")
        if (b.contains("realme")) tags.add("oppo")
        if (b.contains("iqoo")) tags.add("vivo")
        return tags
    }

    // 全量自启动候选（行业优选表）：同厂商多版本并列
    private fun allAutoStart(): List<Tagged> = listOf(
        Tagged(setOf("xiaomi"), explicit("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Tagged(setOf("huawei", "honor"), explicit("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Tagged(setOf("huawei", "honor"), explicit("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        Tagged(setOf("huawei", "honor"), explicit("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),
        Tagged(setOf("huawei", "honor"), explicit("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")),
        Tagged(setOf("honor"), explicit("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Tagged(setOf("honor"), explicit("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity")),
        Tagged(setOf("honor"), explicit("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")),
        Tagged(setOf("honor"), explicit("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.bootstart.BootStartActivity")),
        Tagged(setOf("honor"), explicit("com.honor.systemmanager", "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Tagged(setOf("honor"), explicit("com.honor.systemmanager", "com.honor.systemmanager.optimize.process.ProtectActivity")),
        Tagged(setOf("oppo"), explicit("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        Tagged(setOf("oppo"), explicit("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
        Tagged(setOf("oppo"), explicit("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
        Tagged(setOf("oneplus", "oppo"), explicit("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
        Tagged(setOf("vivo"), explicit("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        // OriginOS 新版本显式 Component 可能被拒，Action 隐式作补充
        Tagged(setOf("vivo"), Intent("com.vivo.permissionmanager.activity.BgStartUpManagerActivity").setPackage("com.vivo.permissionmanager")),
        Tagged(setOf("vivo"), explicit("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
        Tagged(setOf("vivo"), explicit("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
        Tagged(setOf("vivo"), explicit("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity")),
        // 三星无独立自启动页，电池页即正确入口
        Tagged(setOf("samsung"), explicit("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Tagged(setOf("samsung"), explicit("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Tagged(setOf("samsung"), explicit("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Tagged(setOf("meizu"), explicit("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")),
        Tagged(setOf("asus"), explicit("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")),
        Tagged(setOf("asus"), explicit("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings")),
        Tagged(setOf("letv"), explicit("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")),
        Tagged(setOf("lenovo"), explicit("com.zui.safecenter", "com.zui.safecenter.auth.AuthActivity")),
        Tagged(setOf("nokia"), explicit("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")),
        Tagged(setOf("htc"), explicit("com.htc.pitroad", "com.htc.pitroad.landingpage.activity.LandingPageActivity")),
        Tagged(setOf("zte"), explicit("com.zte.heartyservice", "com.zte.heartyservice.autorun.AppAutoRunManager")),
        Tagged(setOf("sony"), explicit("com.sonymobile.cta", "com.sonymobile.cta.SomcCTAMainActivity")),
        Tagged(setOf("transsion"), explicit("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")),
        Tagged(setOf("transsion"), explicit("com.itel.autobootmanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity")),
    )

    private fun orderedAutoStart(ctx: Context): List<Intent> {
        val mine = brandTags() + genericBrandTags()
        val all = allAutoStart()
        // 本机厂商优先，其余按原序试错；已安装管家包的排前减少误跳
        val (hit, miss) = all.partition { it.tags.any { t -> t in mine } }
        val ordered = hit + miss
        val pm = ctx.packageManager
        val (installed, rest) = ordered.partition {
            val pkgName = it.intent.component?.packageName ?: return@partition false
            try {
                if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkgName, 0)
                true
            } catch (_: Exception) { false }
        }
        return (installed + rest).map { it.intent }
    }

    // BRAND/机型兜底：MANUFACTURER 被改写时代工机仍能命中分组
    private fun genericBrandTags(): Set<String> {
        val s = (Build.BRAND + " " + Build.MODEL + " " + Build.DEVICE).lowercase()
        val tags = mutableSetOf<String>()
        if (s.contains("transsion") || s.contains("infinix") || s.contains("tecno") || s.contains("itel")) tags.add("transsion")
        if (s.contains("asus") || s.contains("rog")) tags.add("asus")
        if (s.contains("lenovo") || s.contains("zui")) tags.add("lenovo")
        if (s.contains("nokia") || s.contains("evenwell")) tags.add("nokia")
        if (s.contains("htc")) tags.add("htc")
        if (s.contains("zte") || s.contains("nubia")) tags.add("zte")
        if (s.contains("sony")) tags.add("sony")
        if (s.contains("letv") || s.contains("lemobile")) tags.add("letv")
        if (s.contains("meizu")) tags.add("meizu")
        if (s.contains("samsung")) tags.add("samsung")
        return tags
    }

    private fun autoStartManagerPkgsOrdered(): List<String> {
        val mine = brandTags()
        val all = listOf(
            "com.miui.securitycenter", "com.huawei.systemmanager", "com.hihonor.systemmanager",
            "com.honor.systemmanager",
            "com.coloros.safecenter", "com.oppo.safe", "com.oneplus.security",
            "com.vivo.permissionmanager", "com.iqoo.secure",
            "com.samsung.android.lool", "com.samsung.android.sm",
            "com.meizu.safe", "com.asus.mobilemanager", "com.letv.android.letvsafe",
            "com.zui.safecenter", "com.evenwell.powersaving.g3", "com.transsion.phonemaster",
        )
        return all.sortedByDescending { pkg ->
            when {
                mine.contains("xiaomi") && pkg.contains("miui") -> 1
                (mine.contains("huawei") || mine.contains("honor")) && pkg.contains("systemmanager") -> 1
                mine.contains("oppo") && (pkg.contains("coloros") || pkg.contains("oppo")) -> 1
                mine.contains("oneplus") && pkg.contains("oneplus") -> 1
                mine.contains("vivo") && (pkg.contains("vivo") || pkg.contains("iqoo")) -> 1
                else -> 0
            }
        }
    }

    // 通用兜底始终可用，直接显示直达入口
    fun isHighBgSupported(): Boolean = true

    // 解除后台限制：厂商电池页优先，打不开则退管家首页按文案手动找
    fun openHighBackground(ctx: Context): Boolean {
        val candidates = orderedHighBg(ctx)
        for (intent in candidates) {
            if (canResolve(ctx, intent) && tryStart(ctx, intent)) return true
        }
        // 未声明 queries 时 resolve 可能误判，不校验再全量试一次
        for (intent in candidates) {
            if (tryStart(ctx, intent)) return true
        }
        // 管家首页
        for (pkg in autoStartManagerPkgsOrdered()) {
            try {
                val home = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (home != null) {
                    ctx.startActivity(home)
                    return true
                }
            } catch (_: Exception) { /* 换下一个 */ }
        }
        // 原生电池优化页仅原生系统有意义；厂商机有自带电源管理
        if (brandTags().isEmpty() && genericBrandTags().isEmpty()) {
            if (tryStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
        }
        if (tryStart(ctx, appDetailsIntent(ctx))) return true
        return tryStart(ctx, Intent(Settings.ACTION_SETTINGS))
    }

    private fun orderedHighBg(ctx: Context): List<Intent> {
        val mine = brandTags() + genericBrandTags()
        val all = allHighBg(ctx)
        val (hit, miss) = all.partition { it.tags.any { t -> t in mine } }
        // 已安装管家包的排前，减少误跳与异常试错
        val pm = ctx.packageManager
        val (installed, rest) = (hit + miss).partition {
            val pkgName = it.intent.component?.packageName ?: return@partition false
            try {
                if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkgName, 0)
                true
            } catch (_: Exception) { false }
        }
        return (installed + rest).map { it.intent }
    }

    // 按厂商组装候选页：同厂商多版本并列，调用方无需关心 ROM 差异
    private fun allHighBg(ctx: Context): List<Tagged> {
        val pkg = ctx.packageName
        val label = try {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) { pkg }
        fun withPkg(pkgName: String, cls: String) =
            Intent().setComponent(ComponentName(pkgName, cls)).apply {
                putExtra("package_name", pkg)
                putExtra("package_label", label)
            }
        // 高耗电页只收录电池/功耗类 Activity：误跳自启动页比跳通用页更糟
        return listOf(
            Tagged(setOf("xiaomi"), withPkg("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
            Tagged(setOf("xiaomi"), withPkg("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
            Tagged(setOf("huawei", "honor"), explicit("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Tagged(setOf("huawei", "honor"), explicit("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity")),
            Tagged(setOf("honor"), explicit("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")),
            Tagged(setOf("oppo", "oneplus"), explicit("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")),
            Tagged(setOf("oppo", "oneplus"), explicit("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")),
            Tagged(setOf("oppo", "oneplus"), explicit("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerSaverModeActivity")),
            Tagged(setOf("oppo", "oneplus"), explicit("com.oplus.battery", "com.oplus.battery.performance.PowerConsumptionActivity")),
            // vivo 后台耗电管理：设置-电池-后台耗电管理；自启动页另走 openAutoStart
            Tagged(setOf("vivo"), explicit("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")),
            Tagged(setOf("samsung"), explicit("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Tagged(setOf("samsung"), explicit("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Tagged(setOf("samsung"), explicit("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Tagged(setOf("asus"), explicit("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings")),
            Tagged(setOf("nokia"), explicit("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")),
        )
    }

    fun openAppSettings(ctx: Context) {
        if (tryStart(ctx, appDetailsIntent(ctx))) return
        tryStart(ctx, Intent(Settings.ACTION_SETTINGS))
    }

    /** 应用详情页意图（供设置返回回调启动，返回后可立即刷新状态） */
    fun appDetailsIntent(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    // 机型匹配用包含而非相等：子品牌/代工机 MANUFACTURER 写法各异
    fun deviceHint(ctx: Context): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                m.contains("blackshark") -> ctx.getString(R.string.device_hint_xiaomi)
            m.contains("huawei") || m.contains("honor") -> ctx.getString(R.string.device_hint_huawei)
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                ctx.getString(R.string.device_hint_oppo)
            m.contains("vivo") || m.contains("iqoo") -> ctx.getString(R.string.device_hint_vivo)
            m.contains("samsung") -> ctx.getString(R.string.device_hint_samsung)
            else -> ctx.getString(R.string.device_hint_default)
        }
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
            m.contains("samsung") -> ctx.getString(R.string.device_highbg_samsung)
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
            m.contains("samsung") -> ctx.getString(R.string.device_vendor_samsung)
            else -> null
        }
    }
}
