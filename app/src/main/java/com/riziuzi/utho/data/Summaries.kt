package com.riziuzi.utho.data

import android.content.Context
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.DaySummary
import com.riziuzi.utho.core.Stats
import com.riziuzi.utho.core.TopicClassifier
import com.riziuzi.utho.core.WeekSummary
import com.riziuzi.utho.core.WorkoutRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Turns stored records into what the screens and the journal show. */
object Summaries {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun workoutsOn(ctx: Context, date: LocalDate): List<WorkoutRecord> =
        Store.get(ctx).workouts.filter { dateOf(it.timeMs) == date }

    fun day(ctx: Context, date: LocalDate, now: Long = System.currentTimeMillis()): DaySummary {
        val store = Store.get(ctx)
        val sessions = store.sessions
        val from = Stats.dayStart(date, zone)
        val to = Stats.dayEnd(date, zone)
        val daySessions = sessions.filter { it.startMs < to && (it.endMs ?: now) > from }
        val spoken = daySessions.flatMap { it.checkIns }
            .filter { it.timeMs in from until to && it.kind == com.riziuzi.utho.core.CheckInKind.SPOKEN }
        val free = store.freeNotes.filter { it.timeMs in from until to }
        val checkIns: List<CheckIn> = (spoken + free).filter { it.text.isNotBlank() }
        return DaySummary(
            date = date,
            workouts = workoutsOn(ctx, date),
            studyMinutes = Stats.totalMinutes(sessions, date, zone, now),
            byTopic = Stats.minutesByTopic(sessions, date, zone, now),
            longestBlockMin = Stats.longestBlockMinutes(daySessions, date, zone, now),
            sessionCount = daySessions.size,
            goalMinutes = Prefs(ctx).dailyGoalMin,
            checkIns = checkIns,
            checkInsAsked = spoken.size,
            checkInsAnswered = spoken.count { it.text.isNotBlank() },
        )
    }

    fun minutesPerDay(ctx: Context, now: Long = System.currentTimeMillis()): Map<LocalDate, Int> =
        Stats.minutesPerDay(Store.get(ctx).sessions, zone, now)

    fun activeDays(ctx: Context, now: Long = System.currentTimeMillis()): Set<LocalDate> {
        val minutes = minutesPerDay(ctx, now)
        val workoutDays = Store.get(ctx).workouts.filter { it.done > 0 }.map { dateOf(it.timeMs) }.toSet()
        val days = minutes.keys + workoutDays
        return days.filter { Stats.isActive(minutes[it] ?: 0, if (it in workoutDays) 1 else 0) }.toSet()
    }

    fun week(ctx: Context, weekStart: LocalDate, now: Long = System.currentTimeMillis()): WeekSummary {
        val store = Store.get(ctx)
        val from = Stats.dayStart(weekStart, zone)
        val to = Stats.dayStart(weekStart.plusDays(7), zone)
        val byTopic = Stats.msByTopic(store.sessions, from, to, now)
            .mapValues { (it.value / 60_000L).toInt() }
            .filter { it.value > 0 }
        val active = activeDays(ctx, now).count { !it.isBefore(weekStart) && it.isBefore(weekStart.plusDays(7)) }
        val workouts = store.workouts.count { it.done > 0 && it.timeMs in from until to }
        return WeekSummary(
            studyMinutes = byTopic.values.sum(),
            activeDays = active,
            workouts = workouts,
            topTopic = byTopic.maxByOrNull { it.value }?.toPair(),
        )
    }

    fun weekByTopic(ctx: Context, weekStart: LocalDate, now: Long = System.currentTimeMillis()): List<Pair<String, Int>> {
        val from = Stats.dayStart(weekStart, zone)
        val to = Stats.dayStart(weekStart.plusDays(7), zone)
        return Stats.msByTopic(Store.get(ctx).sessions, from, to, now)
            .map { it.key to (it.value / 60_000L).toInt() }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
    }

    fun classifier(ctx: Context): TopicClassifier = TopicClassifier(Prefs(ctx).subjects, Store.get(ctx).examples)
}
