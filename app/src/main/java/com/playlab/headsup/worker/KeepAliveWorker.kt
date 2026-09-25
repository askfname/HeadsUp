package com.playlab.headsup.worker

import android.app.ActivityManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.playlab.headsup.data.Prefs
import com.playlab.headsup.service.HeadsUpService

/** WorkManager：服务被杀则重启 */
class KeepAliveWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        if (Prefs.isEnabled(applicationContext) && !running()) {
            try { HeadsUpService.start(applicationContext) } catch (_: Exception) { }
        }
        return Result.success()
    }

    private fun running(): Boolean {
        val am = applicationContext.getSystemService(ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == HeadsUpService::class.java.name }
    }
}
