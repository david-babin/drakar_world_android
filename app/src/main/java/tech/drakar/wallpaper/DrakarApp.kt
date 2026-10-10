package tech.drakar.wallpaper

import android.app.Application

class DrakarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        ChargingWorker.schedule(this, Hub.prefs.computeCharging)
    }
}
