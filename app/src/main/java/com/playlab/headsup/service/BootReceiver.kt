package com.playlab.headsup.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.playlab.headsup.data.Prefs

/** 仅处理平台投递的开机和应用更新广播 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.isEnabled(ctx)) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> HeadsUpService.start(ctx)
        }
    }
}
