package com.riziuzi.utho.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private fun at(date: LocalDate, h: Int, m: Int): Long = date.atTime(h, m).atZone(IST).toInstant().toEpochMilli()
private const val MIN = 60_000L

class StatsTest {
    private val day = LocalDate.of(2026, 11, 17)

    @Test fun spokenCheckInsTagThePastStretch() {
        val s = StudySession(
            1, at(day, 9, 0), at(day, 10, 30), "Polity",
            listOf(
                CheckIn(at(day, 9, 30), "history", "History"),
                CheckIn(at(day, 10, 0), "", null), // no answer: keeps previous topic
            ),
        )
        val byTopic = Stats.minutesByTopic(listOf(s), day, IST, at(day, 23, 0)).toMap()
        assertEquals(90, byTopic["History"]) // 9:00-9:30 History, 9:30-10:00 History, 10:00-10:30 History
        assertEquals(null, byTopic["Polity"])
    }

    @Test fun missedCheckInDropsThatStretch() {
        // 9:00-9:30 answered, 9:30-10:00 nobody answered, 10:00-10:30 answered.
        val s = StudySession(
            1, at(day, 9, 0), at(day, 10, 30), "Polity",
            listOf(
                CheckIn(at(day, 9, 30), "polity padhi", "Polity"),
                CheckIn(at(day, 10, 0), "", null, CheckInKind.MISSED),
                CheckIn(at(day, 10, 30), "history padhi", "History"),
            ),
        )
        val byTopic = Stats.minutesByTopic(listOf(s), day, IST, at(day, 23, 0)).toMap()
        assertEquals(30, byTopic["Polity"])
        assertEquals(30, byTopic["History"])
        assertEquals(60, Stats.totalMinutes(listOf(s), day, IST, at(day, 23, 0)))
        // The gap splits the focus block too.
        assertEquals(30, Stats.longestBlockMinutes(listOf(s), day, IST, at(day, 23, 0)))
        // Answering it later counts the 30 minutes again.
        val answered = s.copy(checkIns = s.checkIns.map { if (it.kind == CheckInKind.MISSED) it.copy(text = "maths", topic = "CSAT", kind = CheckInKind.SPOKEN) else it })
        assertEquals(90, Stats.totalMinutes(listOf(answered), day, IST, at(day, 23, 0)))
        assertEquals(90, Stats.longestBlockMinutes(listOf(answered), day, IST, at(day, 23, 0)))
    }

    @Test fun switchChangesTheNextStretch() {
        val s = StudySession(
            1, at(day, 9, 0), at(day, 10, 0), "Polity",
            listOf(CheckIn(at(day, 9, 20), "", "Maths", CheckInKind.SWITCH)),
        )
        val byTopic = Stats.minutesByTopic(listOf(s), day, IST, at(day, 23, 0)).toMap()
        assertEquals(20, byTopic["Polity"])
        assertEquals(40, byTopic["Maths"])
    }

    @Test fun activeSessionCountsUntilNow() {
        val s = StudySession(1, at(day, 9, 0), null, "Polity")
        assertEquals(45, Stats.totalMinutes(listOf(s), day, IST, at(day, 9, 45)))
    }

    @Test fun sessionAcrossMidnightIsSplit() {
        val s = StudySession(1, at(day, 23, 30), at(day.plusDays(1), 0, 30), "Polity")
        assertEquals(30, Stats.totalMinutes(listOf(s), day, IST, at(day.plusDays(2), 0, 0)))
        assertEquals(30, Stats.totalMinutes(listOf(s), day.plusDays(1), IST, at(day.plusDays(2), 0, 0)))
    }

    @Test fun longestBlock() {
        val a = StudySession(1, at(day, 9, 0), at(day, 10, 40), "Polity")
        val b = StudySession(2, at(day, 14, 0), at(day, 14, 50), "Maths")
        assertEquals(100, Stats.longestBlockMinutes(listOf(a, b), day, IST, at(day, 23, 0)))
    }

    @Test fun streakForgivesTwoRestDaysAMonth() {
        val today = LocalDate.of(2026, 11, 18)
        // Active 1-18 Nov except the 5th and 8th (two rest days).
        val active = (1..18).filter { it != 5 && it != 8 }.map { LocalDate.of(2026, 11, it) }.toSet()
        assertEquals(16, Stats.streak(active, today))
        assertEquals(0, Stats.restDaysLeft(active, today))
    }

    @Test fun streakBreaksOnThirdMissedDay() {
        val today = LocalDate.of(2026, 11, 18)
        val active = (1..18).filter { it != 5 && it != 8 && it != 12 }.map { LocalDate.of(2026, 11, it) }.toSet()
        // 18..13 active, 12 rest, 11..9 active, 8 rest, 7..6 active, 5 = third miss: stop.
        assertEquals(11, Stats.streak(active, today))
    }

    @Test fun streakTodayNotYetActive() {
        val today = LocalDate.of(2026, 11, 18)
        val active = (10..17).map { LocalDate.of(2026, 11, it) }.toSet()
        assertEquals(8, Stats.streak(active, today))
    }

    @Test fun bestTime() {
        val recs = listOf(
            WorkoutRecord(1, Exercise.PUSHUP, 40, 40, 112, true),
            WorkoutRecord(2, Exercise.PUSHUP, 40, 40, 101, true),
            WorkoutRecord(3, Exercise.PUSHUP, 40, 30, 90, false), // not completed
            WorkoutRecord(4, Exercise.SQUAT, 40, 40, 60, false),
        )
        assertEquals(101, Stats.bestSeconds(recs, Exercise.PUSHUP, 40))
    }
}

class AlarmTimeTest {
    private val wed6pm = ZonedDateTime.of(2026, 11, 18, 18, 0, 0, 0, IST) // Wednesday

    @Test fun laterTodayIsTomorrowWhenPassed() {
        val n = AlarmTime.next(wed6pm, 6, 0, 0)
        assertEquals(LocalDate.of(2026, 11, 19), n.toLocalDate())
        assertEquals(6, n.hour)
    }

    @Test fun sameDayIfStillAhead() {
        val n = AlarmTime.next(wed6pm, 22, 30, 0)
        assertEquals(LocalDate.of(2026, 11, 18), n.toLocalDate())
    }

    @Test fun skipsDaysNotSelected() {
        // Only Monday (bit 0) selected.
        val n = AlarmTime.next(wed6pm, 6, 0, 1)
        assertEquals(DayOfWeek.MONDAY, n.dayOfWeek)
        assertEquals(LocalDate.of(2026, 11, 23), n.toLocalDate())
    }

    @Test fun monToSatSkipsSunday() {
        val sat8am = ZonedDateTime.of(2026, 11, 21, 8, 0, 0, 0, IST)
        val n = AlarmTime.next(sat8am, 6, 0, 0b0111111)
        assertEquals(DayOfWeek.MONDAY, n.dayOfWeek)
    }
}

class JournalTest {
    private val day = LocalDate.of(2026, 11, 17)

    private fun summary() = DaySummary(
        date = day,
        workouts = listOf(WorkoutRecord(at(day, 6, 6), Exercise.PUSHUP, 40, 40, 112, true)),
        studyMinutes = 310,
        byTopic = listOf("Polity" to 180, "CSAT" to 130),
        longestBlockMin = 100,
        sessionCount = 3,
        goalMinutes = 360,
        checkIns = listOf(CheckIn(at(day, 9, 30), "Read about Article 21 cases.", "Polity")),
    )

    @Test fun english() {
        val p = JournalWriter.write(summary(), IST, Lang.EN)
        assertEquals("Woke up at 6:04 AM and did 40 push-ups in 1:52.", p[0])
        assertEquals("Studied 5 h 10 min in total: Polity 3 h, CSAT 2 h 10 min.", p[1])
        assertEquals("Longest focus block: 1 h 40 min.", p[2])
        assertEquals("Goal was 6 h, 50 min short.", p[3])
        assertEquals("Study notes:", p[4])
        assertEquals("9:30 AM · Polity: Read about Article 21 cases.", p[5])
    }

    @Test fun hinglish() {
        val p = JournalWriter.write(summary(), IST, Lang.HI)
        assertEquals("Subah 6:04 baje uthe aur 1 minute 52 second mein 40 push-ups kiye.", p[0])
        assertEquals("Kul 5 ghante 10 minute padhai ki: Polity 3 ghante, CSAT 2 ghante 10 minute.", p[1])
        assertEquals("Sabse lamba focus 1 ghanta 40 minute raha.", p[2])
        assertEquals("Target 6 ghante ka tha, 50 minute kam rahe.", p[3])
    }

    @Test fun checkInsAnsweredAndMood() {
        val d = summary().copy(
            checkIns = listOf(CheckIn(at(day, 9, 30), "Article 21 padha", "Polity", mood = 1)),
            checkInsAsked = 4, checkInsAnswered = 3,
        )
        val en = JournalWriter.write(d, IST, Lang.EN)
        assertTrue(en.contains("Answered 3 of 4 check-ins."))
        assertTrue(en.contains("9:30 AM · Polity · Good: Article 21 padha"))
        val hi = JournalWriter.write(d, IST, Lang.HI)
        assertTrue(hi.contains("4 mein se 3 check-in ka jawaab diya."))
        assertTrue(hi.contains("subah 9:30 · Polity · Accha: Article 21 padha"))
    }

    @Test fun emptyDay() {
        val p = JournalWriter.write(DaySummary(day, emptyList(), 0, emptyList(), 0, 0, 360, emptyList()), IST, Lang.EN)
        assertEquals(listOf("Nothing recorded for this day yet."), p)
    }

    @Test fun goalReachedAndPlank() {
        val d = DaySummary(
            day, listOf(WorkoutRecord(at(day, 6, 2), Exercise.PLANK, 60, 60, 60, true)),
            400, listOf("Maths" to 400), 400, 1, 360, emptyList(),
        )
        val p = JournalWriter.write(d, IST, Lang.EN)
        assertEquals("Woke up at 6:01 AM and held a 60-second plank.", p[0])
        assertTrue(p.contains("Daily goal of 6 h reached."))
    }

    @Test fun weekSummary() {
        val w = WeekSummary(31 * 60, 5, 6, "Polity" to 9 * 60)
        assertEquals("This week: 31 h studied on 5 days, 6 workouts. Most time on Polity (9 h).", JournalWriter.week(w, Lang.EN))
        assertEquals("Is hafte: 5 dinon mein 31 ghante padhai, 6 workout. Sabse zyada time Polity ko (9 ghante).", JournalWriter.week(w, Lang.HI))
    }
}
