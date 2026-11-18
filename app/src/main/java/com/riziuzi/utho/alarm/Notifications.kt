package com.riziuzi.utho.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.riziuzi.utho.core.StudySession
import com.riziuzi.utho.core.t
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.ui.MainActivity

object Notifications {
    const val CH_ALARM = "alarm"
    const val CH_CHECKIN = "checkin"
    const val CH_REMIND = "reminders"
    const val CH_SESSION = "session"

    const val ID_ALARM = 42
    const val ID_CHECKIN = 43
    const val ID_AWAKE = 44
    const val ID_SESSION = 45
    const val ID_STUDY = 46
    const val ID_JOURNAL = 47

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val lang = Prefs(ctx).lang
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, lang.t("Wake-up alarm", "Subah ka alarm"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the alarm sound is played by AlarmService
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CHECKIN, lang.t("Study check-ins", "Padhai check-in"), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, lang.t("Reminders", "Reminders"), NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SESSION, lang.t("Study timer", "Padhai timer"), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun activityPending(ctx: Context, req: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(ctx, req, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A notification that opens [intent] full screen (also over the lock screen). */
    fun fullScreen(
        ctx: Context, id: Int, channel: String, title: String, text: String, intent: Intent, category: String,
        ongoing: Boolean = false,
    ): Notification {
        val pi = activityPending(ctx, id, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        return NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(category)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    fun post(ctx: Context, id: Int, n: Notification) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    fun simple(ctx: Context, id: Int, title: String, text: String, open: Intent) {
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(activityPending(ctx, id, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            .setAutoCancel(true)
            .build()
        post(ctx, id, n)
    }

    /** Ongoing "Studying · Polity 1:20:05" notification while a session runs. */
    fun showSession(ctx: Context, s: StudySession) {
        val lang = Prefs(ctx).lang
        val open = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_STUDY)
        val n = NotificationCompat.Builder(ctx, CH_SESSION)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(lang.t("Studying", "Padhai chal rahi hai") + " · " + s.subject)
            .setWhen(s.startMs)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setContentIntent(activityPending(ctx, ID_SESSION, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)))
            .build()
        post(ctx, ID_SESSION, n)
    }
}
