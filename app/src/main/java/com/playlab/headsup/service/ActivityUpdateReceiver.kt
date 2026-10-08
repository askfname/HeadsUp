package com.playlab.headsup.service

import android.app.NotificationManager
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.playlab.headsup.data.Prefs

/** GMS 步行提示与通知操作 */
class ActivityUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == "com.playlab.headsup.action.DISMISS") {
            ctx.getSystemService(NotificationManager::class.java)
                ?.cancel(com.playlab.headsup.reminder.ReminderManager.NOTIFY_ID)
            return
        }
        if (!Prefs.isEnabled(ctx) || !ActivityTransitionResult.hasResult(intent)) return
        val walking = ActivityTransitionResult.extractResult(intent)?.transitionEvents
            ?.any { it.isWalkingEnter() } == true
        if (!walking || !monitoringRunning(ctx)) return
        // This only reaches an existing FGS; it is never a background revival path
        try {
            ctx.startService(Intent(ctx, HeadsUpService::class.java).setAction(HeadsUpService.ACTION_GMS_HINT))
        } catch (e: RuntimeException) {
            android.util.Log.w(TAG, "Unable to deliver GMS hint", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun monitoringRunning(ctx: Context): Boolean =
        ctx.getSystemService(ActivityManager::class.java)
            ?.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service.className == HeadsUpService::class.java.name } == true

    private fun ActivityTransitionEvent.isWalkingEnter(): Boolean {
        val walking = activityType == DetectedActivity.WALKING ||
            activityType == DetectedActivity.RUNNING ||
            activityType == DetectedActivity.ON_FOOT
        return walking && transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
    }

    private companion object {
        const val TAG = "ActivityUpdateReceiver"
    }
}
