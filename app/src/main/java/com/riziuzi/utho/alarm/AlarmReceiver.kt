package com.riziuzi.utho.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.JournalWriter
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
            AlarmScheduler.ACTION_CHECK_IN -> CheckIns.start(ctx)
            // The pop-up never opened (or was never answered): stop studying, no entry for this interval.
            AlarmScheduler.ACTION_CHECK_IN_DEADLINE -> CheckIns.noAnswer(ctx, intent.getLongExtra(AlarmScheduler.EXTRA_TIME, -1L))
            AlarmScheduler.ACTION_TEST_POPUP -> CheckIns.open(
                ctx, CheckInActivity.testIntent(ctx),
                lang.t("Test check-in", "Test check-in"), lang.t("This is how the pop-up will look", "Pop-up aisa dikhega"),
            )
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
