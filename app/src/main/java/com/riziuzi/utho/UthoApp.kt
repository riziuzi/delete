package com.riziuzi.utho

import android.app.Application
import com.riziuzi.utho.alarm.Notifications

class UthoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
