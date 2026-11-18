package com.riziuzi.utho.alarm

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Stats
import com.riziuzi.utho.core.t
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import com.riziuzi.utho.ui.CheckInActivity
import com.riziuzi.utho.ui.MainActivity

/**
 * The timed check-in rule:
 *  - when an interval ends, the pop-up opens by itself and listens;
 *  - something said: it's saved and the study timer keeps going;
 *  - nothing said: the study timer stops at your last answer (or the start) and that interval leaves no entry.
 */
object CheckIns {
    /** Extra seconds for the screen to wake and the pop-up to open before an unseen check-in counts as unanswered. */
    const val GRACE_SEC = 90

    /** Interval over: save a placeholder, plan the next one and the deadline, and open the pop-up. */
    fun start(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = Prefs(ctx)
        val store = Store.get(ctx)
        val session = store.activeSession()
        if (session == null || prefs.checkInEverySec <= 0) {
            AlarmScheduler.cancelCheckIn(ctx)
            return
        }
        store.addCheckIn(session.id, CheckIn(nowMs, "", null, CheckInKind.MISSED))
        AlarmScheduler.scheduleNextCheckIn(ctx, nowMs)
        AlarmScheduler.scheduleDeadline(ctx, nowMs + (prefs.listenSeconds + GRACE_SEC) * 1000L, nowMs)
        val lang = prefs.lang
        val span = JournalWriter.span(prefs.checkInEverySec, lang)
        open(
            ctx, CheckInActivity.timedIntent(ctx, nowMs),
            lang.t("Quick check-in", "Quick check-in"),
            lang.t("Say what you studied in the last $span", "Bataiye pichhle $span mein kya padha"),
        )
    }

    /**
     * Shows the pop-up. Over the lock screen (or with the screen off) Android opens it from the full-screen
     * notification; while the phone is in use it opens it directly if the app may appear over other apps.
     */
    fun open(ctx: Context, intent: Intent, title: String, text: String) {
        val n = Notifications.fullScreen(ctx, Notifications.ID_CHECKIN, Notifications.CH_CHECKIN, title, text, Intent(intent), NotificationCompat.CATEGORY_ALARM)
        Notifications.post(ctx, Notifications.ID_CHECKIN, n)
        runCatching { ctx.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The pop-up got something: the check-in counts and study goes on. */
    fun answered(ctx: Context) {
        AlarmScheduler.cancelDeadline(ctx)
        Notifications.cancel(ctx, Notifications.ID_CHECKIN)
    }

    /**
     * Nothing was said for the check-in saved at [timeMs]: stop studying at the last confirmed moment and leave
     * no entry for this interval. Returns the stop time, or null if there was nothing to stop.
     */
    fun noAnswer(ctx: Context, timeMs: Long): Long? {
        val store = Store.get(ctx)
        AlarmScheduler.cancelDeadline(ctx)
        Notifications.cancel(ctx, Notifications.ID_CHECKIN)
        val session = store.activeSession() ?: return null
        // Only for a check-in of the study running now that is still unanswered (not one from an earlier session).
        val pending = session.checkIns.firstOrNull { it.timeMs == timeMs }
        if (pending == null || pending.kind != CheckInKind.MISSED) return null
        val endAt = Stats.lastConfirmedMs(session)
        // Drop every unanswered placeholder after that point: no entry for these intervals.
        session.checkIns.filter { it.kind == CheckInKind.MISSED && it.timeMs >= endAt }.forEach { store.removeCheckIn(it.timeMs) }
        store.endSession(session.id, endAt)
        AlarmScheduler.cancelCheckIn(ctx)
        Notifications.cancel(ctx, Notifications.ID_SESSION)
        val lang = Prefs(ctx).lang
        val at = JournalWriter.clock(timeMs, Summaries.zone, lang)
        Notifications.simple(
            ctx, Notifications.ID_STUDY,
            lang.t("Study stopped", "Padhai ruk gayi"),
            lang.t("No answer to the $at check-in, so the timer stopped there.", "$at ke check-in ka jawaab nahi mila, isliye timer wahin ruk gaya."),
            Intent(ctx, MainActivity::class.java),
        )
        return endAt
    }
}
