package com.aniq.orbit

import android.app.Application
import com.aniq.orbit.search.AppIndex
import com.aniq.orbit.search.Usage

class OrbitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Usage.init(this)
        AppIndex.init(this)
        Thread { runCatching { Commands.init(this) } }.start()
        Thread { runCatching { Prefs.purgeOldAi(this) } }.start()
    }
}
