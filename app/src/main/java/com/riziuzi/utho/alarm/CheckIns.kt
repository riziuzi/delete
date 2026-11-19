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
        // The previous check-in is still unanswered (its pop-up never got an answer): that's no answer.
        session.checkIns.lastOrNull { it.kind == CheckInKind.MISSED }?.let {
            noAnswer(ctx, it.timeMs)
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
        // It can still be answered (by voice or by typing) from the lock screen; that continues study.
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val every = prefs.checkInSec.coerceAtLeast(60) * 1000L
        prefs.reviveSession = session.id
        prefs.reviveTime = timeMs
        prefs.reviveUntil = maxOf(timeMs + every, now + REVIVE_MIN_MS)
        Notifications.showRevive(ctx, session, timeMs, endAt, prefs.reviveUntil)
        return endAt
    }

    /** An answer that comes later still continues study for this long at least. */
    const val REVIVE_MIN_MS = 10 * 60_000L

    /** The check-in that stopped study and can still be answered, or null. */
    data class Waiting(val sessionId: Long, val timeMs: Long, val untilMs: Long)

    fun waiting(ctx: Context, now: Long = System.currentTimeMillis()): Waiting? {
        val p = Prefs(ctx)
        if (p.reviveSession < 0 || p.reviveTime < 0) return null
        if (now > p.reviveUntil) {
            clearWaiting(ctx)
            return null
        }
        return Waiting(p.reviveSession, p.reviveTime, p.reviveUntil)
    }

    /** Shows the "Study paused" notification again (the pop-up for it was closed without an answer). */
    fun showWaiting(ctx: Context) {
        val w = waiting(ctx) ?: return
        val s = Store.get(ctx).sessions.firstOrNull { it.id == w.sessionId } ?: return
        if (s.active) return
        Notifications.showRevive(ctx, s, w.timeMs, s.endMs ?: w.timeMs, w.untilMs)
    }

    fun clearWaiting(ctx: Context) {
        val p = Prefs(ctx)
        p.reviveSession = -1L
        p.reviveTime = -1L
        p.reviveUntil = -1L
        Notifications.cancel(ctx, Notifications.ID_MISSED)
    }

    /**
     * An answer for the check-in that stopped study: the session goes on from where it was, this interval
     * counts, and the next check-in is planned. Returns false when it's too late or another session started;
     * the answer is then kept as a note.
     */
    fun revive(ctx: Context, answer: CheckIn, now: Long = System.currentTimeMillis()): Boolean {
        val store = Store.get(ctx)
        val w = waiting(ctx, now)
        if (w == null || store.activeSession() != null) {
            clearWaiting(ctx)
            store.addFreeNote(answer.copy(timeMs = now, kind = CheckInKind.SPOKEN))
            return false
        }
        val s = store.reopenSession(w.sessionId)
        if (s == null) {
            clearWaiting(ctx)
            store.addFreeNote(answer.copy(timeMs = now, kind = CheckInKind.SPOKEN))
            return false
        }
        store.addCheckIn(s.id, answer.copy(timeMs = w.timeMs, kind = CheckInKind.SPOKEN))
        clearWaiting(ctx)
        Notifications.cancel(ctx, Notifications.ID_STUDY)
        Notifications.showSession(ctx, s)
        val every = Prefs(ctx).checkInEverySec
        if (every > 0) AlarmScheduler.scheduleCheckIn(ctx, maxOf(w.timeMs + every * 1000L, now + 60_000L))
        return true
    }
}
