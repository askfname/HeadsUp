package com.playlab.headsup.util

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 权限状态查询：身体活动 / 通知 / 位置 / 悬浮窗 */
object PermissionHelper {
    fun hasActivity(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotification(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return nm?.areNotificationsEnabled() ?: true
    }

    fun hasFineLocation(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** 核心权限：身体活动 + 通知（位置为可选） */
    fun coreGranted(ctx: Context) = hasActivity(ctx) && hasNotification(ctx)
}
