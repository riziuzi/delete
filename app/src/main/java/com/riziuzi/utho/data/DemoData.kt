package com.riziuzi.utho.data

import android.content.Context
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.DayNote
import com.riziuzi.utho.core.Exercise
import com.riziuzi.utho.core.StudySession
import com.riziuzi.utho.core.WorkoutRecord
import java.time.LocalDate
import kotlin.random.Random

/** Sample history used by the automated screen tour (never called by the app itself). */
object DemoData {
    private val notes = listOf(
        "Read Laxmikanth chapter on fundamental rights and writs.",
        "मुगल साम्राज्य की प्रशासन व्यवस्था पढ़ी।",
        "Monsoon and the rivers of the Himalaya.",
        "Inflation, repo rate and RBI monetary policy.",
        "संविधान के अनुच्छेद 14 से 18 दोहराए।",
        "Reasoning puzzles and two comprehension passages.",
        "Wrote one essay introduction on climate change.",
    )

    fun seed(ctx: Context, days: Int = 17) {
        val store = Store.get(ctx)
        val prefs = Prefs(ctx)
        val subjects = prefs.subjects.ifEmpty { listOf("Polity", "History", "Geography", "Economy", "CSAT") }
        val zone = Summaries.zone
        val rnd = Random(7)
        val today = LocalDate.now(zone)
        for (back in days downTo 1) {
            val date = today.minusDays(back.toLong())
            if (back == 5 || back == 11) continue // two rest days
            val base = date.atStartOfDay(zone).toInstant().toEpochMilli()
            fun at(h: Int, m: Int) = base + (h * 60L + m) * 60_000L
            if (back % 6 != 0) {
                store.addWorkout(WorkoutRecord(at(6, 2 + rnd.nextInt(4)), Exercise.PUSHUP, 40, 40, 95 + rnd.nextInt(40), true))
            }
            val blocks = listOf(9 to 0, 14 to 0, 19 to 0).take(2 + rnd.nextInt(2))
            for ((i, b) in blocks.withIndex()) {
                val start = at(b.first, b.second)
                val minutes = 50 + rnd.nextInt(80)
                val subject = subjects[(back + i) % subjects.size]
                val checkIns = (30 until minutes step 30).map { off ->
                    val text = notes[(back + i + off) % notes.size]
                    CheckIn(start + off * 60_000L, text, subjects[(back + i + off / 30) % subjects.size])
                }
                store.addSessionForDemo(StudySession(start, start, start + minutes * 60_000L, subject, checkIns))
            }
            if (back % 3 == 0) store.setNote(date, DayNote(back % 5, "Finish the next chapter tomorrow."))
        }
        // Today: morning workout done and one finished block.
        val base = today.atStartOfDay(zone).toInstant().toEpochMilli()
        store.addWorkout(WorkoutRecord(base + (6 * 60 + 6) * 60_000L, Exercise.PUSHUP, 40, 40, 112, true))
        val start = base + 9 * 60 * 60_000L
        if (start + 80 * 60_000L < System.currentTimeMillis()) {
            store.addSessionForDemo(
                StudySession(
                    start, start, start + 80 * 60_000L, subjects.first(),
                    listOf(CheckIn(start + 30 * 60_000L, notes[0], subjects.first())),
                ),
            )
        }
    }
}
