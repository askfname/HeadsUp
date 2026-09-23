package com.headsup.app.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.headsup.app.worker.KeepAliveWorker
import java.util.concurrent.TimeUnit

/** 保活：电池白名单 / 厂商自启 / WorkManager */
object KeepAliveHelper {
    fun ignoringBattery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    @SuppressLint("BatteryLife")
    fun requestBatteryWhitelist(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
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

    /** 每 60 分钟检查服务是否存活（FGS+START_STICKY 是主力，此为低频兜底） */
    fun scheduleKeepAlive(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(60, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "headsup_keepalive", ExistingPeriodicWorkPolicy.UPDATE, req
        )
    }

    fun openAppSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun deviceHint(): String = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi" -> "小米：设置 → 应用设置 → 授权管理 → 自启动"
        "huawei", "honor" -> "华为：手机管家 → 应用启动管理 → 允许自启动"
        "oppo", "realme", "oneplus" -> "OPPO/一加：手机管家 → 权限隐私 → 自启动管理"
        "vivo", "iqoo" -> "vivo：设置 → 应用 → 自启动"
        else -> "请在系统设置中允许自启动 + 后台运行"
    }
}
