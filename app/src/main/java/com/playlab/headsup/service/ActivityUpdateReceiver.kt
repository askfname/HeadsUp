package com.headsup.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.headsup.app.data.Prefs

/**
 * GMS 步行回调 + 通知按钮消除。
 * 注意：GMS 回调可能延迟数分钟到达，这里只转交 Hint 给服务，
 * 由服务用检测器活体状态二次确认后才提醒；且不再重启服务（避免重订阅自激循环）。
 */
class ActivityUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == "com.headsup.app.action.DISMISS") {
            ctx.getSystemService(NotificationManager::class.java)
                ?.cancel(com.headsup.app.reminder.ReminderManager.NOTIFY_ID)
            return
        }
        if (!Prefs.isEnabled(ctx)) return
        try {
            if (ActivityTransitionResult.hasResult(intent)) {
                val walking = ActivityTransitionResult.extractResult(intent)?.transitionEvents
                    ?.any { it.isWalkingEnter() } == true
                if (walking) {
                    try {
                        ctx.startService(
                            Intent(ctx, HeadsUpService::class.java)
                                .setAction(HeadsUpService.ACTION_GMS_HINT),
                        )
                    } catch (_: Exception) { /* 后台启动被系统拒绝则丢弃本次 Hint */ }
                }
            }
        } catch (_: Exception) { }
    }

    private fun ActivityTransitionEvent.isWalkingEnter(): Boolean {
        val walk = activityType == DetectedActivity.WALKING ||
            activityType == DetectedActivity.RUNNING ||
            activityType == DetectedActivity.ON_FOOT // 含上下楼
        return walk && transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
    }
}
