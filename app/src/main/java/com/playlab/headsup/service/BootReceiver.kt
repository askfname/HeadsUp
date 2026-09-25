package com.playlab.headsup.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.playlab.headsup.data.Prefs
import com.playlab.headsup.util.KeepAliveHelper

/** 开机 / 更新 / 被杀后自启 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.isEnabled(ctx)) return
        KeepAliveHelper.scheduleKeepAlive(ctx)
        try { HeadsUpService.start(ctx) } catch (_: Exception) { }
    }
}
