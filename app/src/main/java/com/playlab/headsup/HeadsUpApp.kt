package com.playlab.headsup

import android.app.Application
import com.playlab.headsup.data.Prefs
import com.playlab.headsup.service.HeadsUpService

/** 进程启动时补起服务：应用被杀/重启后开关仍开着但服务已死” */
class HeadsUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Prefs.isEnabled(this)) {
            try { HeadsUpService.start(this) } catch (_: Exception) { }
        }
    }
}
