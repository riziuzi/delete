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
    /** Silent: the pop-up makes its own single sound when the mic opens. */
    const val CH_CHECKIN = "checkin_quiet"
    const val CH_REMIND = "reminders"
    const val CH_SESSION = "session"

    const val ID_ALARM = 42
    const val ID_CHECKIN = 43
    const val ID_AWAKE = 44
    const val ID_SESSION = 45
    const val ID_STUDY = 46
    const val ID_JOURNAL = 47
    const val ID_MISSED = 48

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val lang = Prefs(ctx).lang
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, lang.t("Wake-up alarm", "Subah ka alarm"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the alarm sound is played by AlarmService
            },
        )
        nm.deleteNotificationChannel("checkin") // older, noisy version of the channel
        nm.createNotificationChannel(
            NotificationChannel(CH_CHECKIN, lang.t("Study check-ins", "Padhai check-in"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
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

    /**
     * Nothing was said at a check-in, so study stopped. Shown on the lock screen with two ways to answer it:
     * Speak (opens the pop-up, which listens until Done) and Type (write the answer right in the notification).
     * Either one continues the study session. Silent, and gone by itself once it's too late to continue.
     */
    fun showRevive(ctx: Context, s: StudySession, checkInMs: Long, stoppedAt: Long, untilMs: Long) {
        val lang = Prefs(ctx).lang
        val zone = com.riziuzi.utho.data.Summaries.zone
        val at = com.riziuzi.utho.core.JournalWriter.clock(checkInMs, zone, lang)
        val stop = com.riziuzi.utho.core.JournalWriter.clock(stoppedAt, zone, lang)
        val speak = activityPending(ctx, ID_MISSED, com.riziuzi.utho.ui.CheckInActivity.reviveIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val input = androidx.core.app.RemoteInput.Builder(ReviveReceiver.KEY_TEXT)
            .setLabel(lang.t("What did you study? How's your mood?", "Kya padha? Mood kaisa hai?"))
            .build()
        val typePi = PendingIntent.getBroadcast(
            ctx, ID_MISSED, Intent(ctx, ReviveReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val type = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_edit, lang.t("Type", "Type karein"), typePi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
        val n = NotificationCompat.Builder(ctx, CH_CHECKIN)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(lang.t("Study paused at $stop", "Padhai $stop par ruki"))
            .setContentText(
                lang.t(
                    "Nothing was said at the $at check-in. Answer to keep studying.",
                    "$at ke check-in par kuch nahi bola. Jawaab dein to padhai chalti rahegi.",
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    lang.t(
                        "Nothing was said at the $at check-in, so the timer stopped at $stop. Speak or type what you studied to keep going: this interval counts too.",
                        "$at ke check-in par kuch nahi bola, isliye timer $stop par ruka. Bolkar ya likhkar bataiye kya padha, padhai chalti rahegi aur yeh samay bhi gina jaayega.",
                    ),
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(speak)
            .addAction(android.R.drawable.ic_btn_speak_now, lang.t("Speak", "Bolein"), speak)
            .addAction(type)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter((untilMs - System.currentTimeMillis()).coerceAtLeast(60_000L))
            .build()
        post(ctx, ID_MISSED, n)
    }

    /** After an answer typed in the notification: what was saved (the inline reply needs this to finish). */
    fun showReviveDone(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_CHECKIN)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(6000)
            .build()
        post(ctx, ID_MISSED, n)
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
