package com.playlab.headsup.util

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.playlab.headsup.data.Prefs

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

    /** 前台位置：仅使用时/仅此次/始终允许都会通过 */
    fun hasForegroundLocation(ctx: Context) =
        hasFineLocation(ctx) ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 后台位置即“始终允许”（29 以下无区分，有前台即算始终） */
    fun hasBackgroundLocation(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return hasForegroundLocation(ctx)
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 系统是否区分“始终允许”：29 以下或框架无此权限时只有允许/拒绝，前台授权即够用 */
    fun requiresAlwaysLocation(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            ctx.packageManager.getPermissionInfo(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION, 0
            )
            true
        } catch (_: Exception) { false }
    }

    /** 室内判断要求始终允许：仅此次/仅使用时不算；不区分的系统以前台为准 */
    fun hasAlwaysLocation(ctx: Context) =
        if (!requiresAlwaysLocation(ctx)) hasForegroundLocation(ctx)
        else hasForegroundLocation(ctx) && hasBackgroundLocation(ctx)

    /** 实际生效门：始终允许，或兼容模式（系统无后台入口）下前台已授 */
    fun isLocationEnough(ctx: Context) =
        hasAlwaysLocation(ctx) ||
            (Prefs.isLocationCompat(ctx) && hasForegroundLocation(ctx))

    fun hasOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** 核心权限：身体活动 + 通知（位置为可选） */
    fun coreGranted(ctx: Context) = hasActivity(ctx) && hasNotification(ctx)
}
