package com.riziuzi.utho.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.t
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import com.riziuzi.utho.ui.AwakeCheckActivity
import com.riziuzi.utho.ui.CheckInActivity
import com.riziuzi.utho.ui.MainActivity
import java.time.LocalDate

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        val lang = prefs.lang
        when (intent.action) {
            AlarmScheduler.ACTION_WAKE -> {
                // Queue the next one first, so the repeat survives whatever happens next.
                AlarmScheduler.scheduleWake(ctx)
                ContextCompat.startForegroundService(ctx, Intent(ctx, AlarmService::class.java))
            }
            AlarmScheduler.ACTION_AWAKE_CHECK -> {
                val n = Notifications.fullScreen(
                    ctx, Notifications.ID_AWAKE, Notifications.CH_ALARM,
                    lang.t("Still awake?", "Jaag rahe ho?"),
                    lang.t("Tap to confirm, or the alarm rings again", "Tap karein, warna alarm phir bajega"),
                    Intent(ctx, AwakeCheckActivity::class.java), NotificationCompat.CATEGORY_ALARM,
                )
                Notifications.post(ctx, Notifications.ID_AWAKE, n)
            }
            AlarmScheduler.ACTION_CHECK_IN -> {
                val session = Store.get(ctx).activeSession()
                if (session == null || prefs.checkInMin <= 0) {
                    AlarmScheduler.cancelCheckIn(ctx)
                    return
                }
                // Forgot to stop? After 3 unanswered check-ins, end the session at the last sign of studying.
                val now = System.currentTimeMillis()
                val lastSign = maxOf(session.startMs, session.checkIns.filter { it.text.isNotBlank() || it.kind == CheckInKind.SWITCH }.maxOfOrNull { it.timeMs } ?: 0L)
                val intervalMs = prefs.checkInMin * 60_000L
                if (now - lastSign > 3 * intervalMs + 5 * 60_000L) {
                    Store.get(ctx).endSession(session.id, lastSign)
                    AlarmScheduler.cancelCheckIn(ctx)
                    Notifications.cancel(ctx, Notifications.ID_SESSION)
                    Notifications.simple(
                        ctx, Notifications.ID_STUDY,
                        lang.t("Study session stopped", "Padhai session rok diya"),
                        lang.t("No answer to the last 3 check-ins, so it was saved until your last one.", "Pichhle 3 check-in ka jawaab nahi mila, isliye aakhri check-in tak save kiya."),
                        Intent(ctx, MainActivity::class.java),
                    )
                    return
                }
                AlarmScheduler.scheduleNextCheckIn(ctx)
                // Saved as missed right away; the pop-up (or a later answer) fills it in. Unanswered, this
                // stretch isn't counted, even if the pop-up never got to show.
                Store.get(ctx).addCheckIn(session.id, CheckIn(now, "", null, CheckInKind.MISSED))
                val n = Notifications.fullScreen(
                    ctx, Notifications.ID_CHECKIN, Notifications.CH_CHECKIN,
                    lang.t("Quick check-in", "Quick check-in"),
                    lang.t("Say what you studied in the last ${prefs.checkInMin} min", "Bataiye pichhle ${prefs.checkInMin} minute mein kya padha aur mood kaisa hai"),
                    CheckInActivity.timedIntent(ctx, now), NotificationCompat.CATEGORY_REMINDER,
                )
                Notifications.post(ctx, Notifications.ID_CHECKIN, n)
            }
            AlarmScheduler.ACTION_STUDY_REMINDER -> {
                AlarmScheduler.scheduleReminders(ctx)
                val today = LocalDate.now(Summaries.zone)
                val studied = Summaries.day(ctx, today).studyMinutes
                if (Store.get(ctx).activeSession() == null && studied == 0 && prefs.setupDone) {
                    Notifications.simple(
                        ctx, Notifications.ID_STUDY,
                        lang.t("Time to study", "Padhai ka time"),
                        lang.t("Tap to start today's first session", "Aaj ka pehla session shuru karein"),
                        Intent(ctx, MainActivity::class.java),
                    )
                }
            }
            AlarmScheduler.ACTION_JOURNAL_REMINDER -> {
                AlarmScheduler.scheduleReminders(ctx)
                val today = LocalDate.now(Summaries.zone)
                if (Store.get(ctx).note(today).mood == null && prefs.setupDone) {
                    Notifications.simple(
                        ctx, Notifications.ID_JOURNAL,
                        lang.t("Your journal is ready", "Aapka journal taiyaar hai"),
                        lang.t("Add how today went in one tap", "Ek tap mein bataiye aaj kaisa raha"),
                        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_JOURNAL),
                    )
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AlarmScheduler.rescheduleAll(ctx)
            Store.get(ctx).activeSession()?.let { Notifications.showSession(ctx, it) }
        }
    }
}
