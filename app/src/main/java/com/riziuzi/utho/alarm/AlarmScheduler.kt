package com.riziuzi.utho.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.riziuzi.utho.core.AlarmTime
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.ui.MainActivity
import java.time.ZonedDateTime

object AlarmScheduler {
    const val ACTION_WAKE = "com.riziuzi.utho.WAKE"
    const val ACTION_AWAKE_CHECK = "com.riziuzi.utho.AWAKE_CHECK"
    const val ACTION_CHECK_IN = "com.riziuzi.utho.CHECK_IN"
    const val ACTION_CHECK_IN_DEADLINE = "com.riziuzi.utho.CHECK_IN_DEADLINE"
    const val ACTION_TEST_POPUP = "com.riziuzi.utho.TEST_POPUP"
    const val EXTRA_TIME = "time"
    const val ACTION_STUDY_REMINDER = "com.riziuzi.utho.STUDY_REMINDER"
    const val ACTION_JOURNAL_REMINDER = "com.riziuzi.utho.JOURNAL_REMINDER"

    private const val AWAKE_DELAY_MS = 5 * 60_000L

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun broadcast(ctx: Context, action: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, req, Intent(ctx, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * On time, always: an alarm-clock alarm. (The "allow while idle" kind can be held back for several minutes
     * while the phone sits locked, which made check-ins arrive late.)
     */
    private fun exact(ctx: Context, atMs: Long, pi: PendingIntent) {
        val show = Notifications.activityPending(ctx, 102, Intent(ctx, MainActivity::class.java))
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(atMs, show), pi)
    }

    private fun deadlinePi(ctx: Context, timeMs: Long) = PendingIntent.getBroadcast(
        ctx, 6, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_CHECK_IN_DEADLINE).putExtra(EXTRA_TIME, timeMs),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** If the check-in saved at [timeMs] is still unanswered at [atMs] (the pop-up never showed), study stops. */
    fun scheduleDeadline(ctx: Context, atMs: Long, timeMs: Long) = exact(ctx, atMs, deadlinePi(ctx, timeMs))

    fun cancelDeadline(ctx: Context) = am(ctx).cancel(deadlinePi(ctx, 0))

    /** Settings › test the pop-up: it comes in [delayMs] (lock the phone to see it on the lock screen). */
    fun scheduleTestPopup(ctx: Context, delayMs: Long) = exact(ctx, System.currentTimeMillis() + delayMs, broadcast(ctx, ACTION_TEST_POPUP, 7))

    fun nextWakeMs(ctx: Context): Long? {
        val p = Prefs(ctx)
        if (!p.alarmEnabled) return null
        return AlarmTime.next(ZonedDateTime.now(), p.alarmHour, p.alarmMinute, p.alarmDays).toInstant().toEpochMilli()
    }

    fun scheduleWake(ctx: Context) {
        val pi = broadcast(ctx, ACTION_WAKE, 1)
        am(ctx).cancel(pi)
        val at = nextWakeMs(ctx) ?: return
        val show = Notifications.activityPending(ctx, 101, Intent(ctx, MainActivity::class.java))
        // setAlarmClock: exact, survives Doze, needs no special permission.
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    fun scheduleAwakeCheck(ctx: Context) = exact(ctx, System.currentTimeMillis() + AWAKE_DELAY_MS, broadcast(ctx, ACTION_AWAKE_CHECK, 2))

    fun cancelAwakeCheck(ctx: Context) = am(ctx).cancel(broadcast(ctx, ACTION_AWAKE_CHECK, 2))

    fun scheduleCheckIn(ctx: Context, atMs: Long) {
        Prefs(ctx).nextCheckInMs = atMs
        exact(ctx, atMs, broadcast(ctx, ACTION_CHECK_IN, 3))
    }

    fun cancelCheckIn(ctx: Context) {
        Prefs(ctx).nextCheckInMs = -1L
        am(ctx).cancel(broadcast(ctx, ACTION_CHECK_IN, 3))
    }

    /** Next check-in [Prefs.checkInEverySec] after [fromMs], if check-ins are on and a session runs. */
    fun scheduleNextCheckIn(ctx: Context, fromMs: Long = System.currentTimeMillis()) {
        val p = Prefs(ctx)
        if (p.checkInEverySec <= 0 || Store.get(ctx).activeSession() == null) {
            cancelCheckIn(ctx)
            return
        }
        scheduleCheckIn(ctx, fromMs + p.checkInEverySec * 1000L)
    }

    private fun nextMinuteOfDay(minuteOfDay: Int): Long =
        AlarmTime.next(ZonedDateTime.now(), minuteOfDay / 60, minuteOfDay % 60, 0).toInstant().toEpochMilli()

    fun scheduleReminders(ctx: Context) {
        val p = Prefs(ctx)
        val am = am(ctx)
        val study = broadcast(ctx, ACTION_STUDY_REMINDER, 4)
        val journal = broadcast(ctx, ACTION_JOURNAL_REMINDER, 5)
        am.cancel(study)
        am.cancel(journal)
        if (p.studyReminderOn) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMinuteOfDay(p.studyReminderMin), study)
        if (p.journalReminderOn) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMinuteOfDay(p.journalReminderMin), journal)
    }

    fun rescheduleAll(ctx: Context) {
        scheduleWake(ctx)
        scheduleReminders(ctx)
        val next = Prefs(ctx).nextCheckInMs
        if (Store.get(ctx).activeSession() != null && next > System.currentTimeMillis()) {
            scheduleCheckIn(ctx, next)
        } else {
            scheduleNextCheckIn(ctx)
        }
    }
}
