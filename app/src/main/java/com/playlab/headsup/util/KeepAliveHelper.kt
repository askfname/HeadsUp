package com.playlab.headsup.util

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
import com.playlab.headsup.R
import com.playlab.headsup.worker.KeepAliveWorker
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

    /** 每 60 分钟检查服务是否存活（FGS+START_STICKY 是主力，此为低频兜底） */
    fun scheduleKeepAlive(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(60, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "headsup_keepalive", ExistingPeriodicWorkPolicy.UPDATE, req
        )
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
}
