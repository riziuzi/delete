package com.example.pushupalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Queue tomorrow's alarm first, so the daily repeat survives whatever happens next.
        AlarmScheduler.schedule(context)
        ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AlarmScheduler.schedule(context)
    }
}
