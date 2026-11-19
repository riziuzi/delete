package com.riziuzi.utho

import android.app.Application
import com.riziuzi.utho.alarm.Notifications

class UthoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // If the app was killed while media was muted for the mic, bring media back.
        com.riziuzi.utho.voice.AudioGuard.recover(this)
    }
}
