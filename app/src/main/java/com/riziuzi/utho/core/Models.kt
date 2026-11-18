package com.riziuzi.utho.core

import java.time.ZonedDateTime

enum class Lang { EN, HI }

/** Picks the English or Hindi text. */
fun Lang.t(en: String, hi: String): String = if (this == Lang.HI) hi else en

/**
 * A note in a study session.
 * SPOKEN: what the user said they studied since the previous note (tags that past stretch of time).
 * SWITCH: the user changed subject now (the next stretch of time gets [topic]).
 */
/**
 * SPOKEN: an answered check-in or a voice/typed note. SWITCH: the user changed subject.
 * MISSED: a timed check-in nobody answered; the stretch before it doesn't count as study until it is answered.
 */
enum class CheckInKind { SPOKEN, SWITCH, MISSED }

data class CheckIn(
    val timeMs: Long,
    val text: String,
    val topic: String?,
    val kind: CheckInKind = CheckInKind.SPOKEN,
    val corrected: Boolean = false,
    /** Mood said in this check-in (see [Moods]), null if none. */
    val mood: Int? = null,
)

data class StudySession(
    val id: Long,
    val startMs: Long,
    val endMs: Long?,
    val subject: String,
    val checkIns: List<CheckIn> = emptyList(),
) {
    val active: Boolean get() = endMs == null
}

/** [target] / [done] are reps, or seconds for a plank. */
data class WorkoutRecord(
    val timeMs: Long,
    val exercise: Exercise,
    val target: Int,
    val done: Int,
    val durationSec: Int,
    val viaAlarm: Boolean,
) {
    val completed: Boolean get() = done >= target
}

/** [mood]: 0 = great ... 4 = bad, null = not set. */
data class DayNote(val mood: Int? = null, val note: String = "")

object AlarmTime {
    /**
     * Next time the alarm rings strictly after [now].
     * [daysMask] bit 0 = Monday ... bit 6 = Sunday; 0 = no repeat (next occurrence of the time).
     */
    fun next(now: ZonedDateTime, hour: Int, minute: Int, daysMask: Int): ZonedDateTime {
        var c = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!c.isAfter(now)) c = c.plusDays(1)
        if (daysMask and 0x7F == 0) return c
        repeat(8) {
            if (daysMask and (1 shl (c.dayOfWeek.value - 1)) != 0) return c
            c = c.plusDays(1)
        }
        return c
    }
}
