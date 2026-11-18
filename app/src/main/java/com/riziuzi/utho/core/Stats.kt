package com.riziuzi.utho.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

object Stats {
    data class Segment(val startMs: Long, val endMs: Long, val topic: String)

    /** Splits a session into stretches of time, each tagged with a topic. */
    fun segments(s: StudySession, nowMs: Long): List<Segment> {
        val end = s.endMs ?: nowMs
        val out = mutableListOf<Segment>()
        var current = s.subject
        var from = s.startMs
        for (c in s.checkIns.sortedBy { it.timeMs }) {
            val t = c.timeMs.coerceIn(s.startMs, end)
            when (c.kind) {
                CheckInKind.SPOKEN -> {
                    val tag = c.topic ?: current
                    if (t > from) out += Segment(from, t, tag)
                    current = tag
                    from = t
                }
                CheckInKind.SWITCH -> {
                    if (t > from) out += Segment(from, t, current)
                    current = c.topic ?: current
                    from = t
                }
            }
        }
        if (end > from) out += Segment(from, end, current)
        return out
    }

    fun dayStart(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayEnd(date: LocalDate, zone: ZoneId): Long = dayStart(date.plusDays(1), zone)

    /** Minutes per topic studied on [date], biggest first. */
    fun minutesByTopic(sessions: List<StudySession>, date: LocalDate, zone: ZoneId, nowMs: Long): List<Pair<String, Int>> {
        val ms = msByTopic(sessions, dayStart(date, zone), dayEnd(date, zone), nowMs)
        return ms.map { it.key to (it.value / 60_000L).toInt() }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    fun msByTopic(sessions: List<StudySession>, from: Long, to: Long, nowMs: Long): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (s in sessions) {
            for (seg in segments(s, nowMs)) {
                val a = max(seg.startMs, from)
                val b = min(seg.endMs, to)
                if (b > a) out[seg.topic] = (out[seg.topic] ?: 0L) + (b - a)
            }
        }
        return out
    }

    fun totalMinutes(sessions: List<StudySession>, date: LocalDate, zone: ZoneId, nowMs: Long): Int =
        (msByTopic(sessions, dayStart(date, zone), dayEnd(date, zone), nowMs).values.sum() / 60_000L).toInt()

    fun minutesBetween(sessions: List<StudySession>, from: Long, to: Long, nowMs: Long): Int =
        (msByTopic(sessions, from, to, nowMs).values.sum() / 60_000L).toInt()

    /** Study minutes on every day that has any, across all sessions. */
    fun minutesPerDay(sessions: List<StudySession>, zone: ZoneId, nowMs: Long): Map<LocalDate, Int> {
        val ms = HashMap<LocalDate, Long>()
        for (s in sessions) {
            for (seg in segments(s, nowMs)) {
                var a = seg.startMs
                while (a < seg.endMs) {
                    val date = java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
                    val b = min(seg.endMs, dayEnd(date, zone))
                    ms[date] = (ms[date] ?: 0L) + (b - a)
                    a = b
                }
            }
        }
        return ms.mapValues { (it.value / 60_000L).toInt() }
    }

    /** Longest single session (clipped to the day), in minutes. */
    fun longestBlockMinutes(sessions: List<StudySession>, date: LocalDate, zone: ZoneId, nowMs: Long): Int {
        val from = dayStart(date, zone)
        val to = dayEnd(date, zone)
        return sessions.maxOfOrNull { s ->
            val a = max(s.startMs, from)
            val b = min(s.endMs ?: nowMs, to)
            if (b > a) ((b - a) / 60_000L).toInt() else 0
        } ?: 0
    }

    /** A day counts when there were at least 30 minutes of study or any completed workout. */
    fun isActive(studyMinutes: Int, workouts: Int) = studyMinutes >= 30 || workouts > 0

    /**
     * Consecutive active days ending today (or yesterday, if today is not active yet).
     * Up to [restPerMonth] inactive days in each calendar month are forgiven as rest days.
     */
    fun streak(active: Set<LocalDate>, today: LocalDate, restPerMonth: Int = 2): Int {
        var day = if (today in active) today else today.minusDays(1)
        var count = 0
        val restUsed = HashMap<Pair<Int, Int>, Int>()
        var guard = 0
        while (guard++ < 3660) {
            if (day in active) {
                count++
            } else {
                val key = day.year to day.monthValue
                val used = restUsed[key] ?: 0
                if (used >= restPerMonth) break
                restUsed[key] = used + 1
            }
            day = day.minusDays(1)
        }
        return count
    }

    /** Rest days still unused this month (days before today with no activity use them up). */
    fun restDaysLeft(active: Set<LocalDate>, today: LocalDate, restPerMonth: Int = 2): Int {
        var missed = 0
        var d = today.withDayOfMonth(1)
        while (d.isBefore(today)) {
            if (d !in active) missed++
            d = d.plusDays(1)
        }
        return max(0, restPerMonth - missed)
    }

    /** Fastest completed time (seconds) for this exercise and target, or null. */
    fun bestSeconds(records: List<WorkoutRecord>, exercise: Exercise, target: Int): Int? =
        records.filter { it.exercise == exercise && it.target == target && it.completed && it.durationSec > 0 }
            .minOfOrNull { it.durationSec }
}
