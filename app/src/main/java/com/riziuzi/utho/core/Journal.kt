package com.riziuzi.utho.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything the app recorded for one day, used to write that day's journal. */
data class DaySummary(
    val date: LocalDate,
    val workouts: List<WorkoutRecord>,
    val studyMinutes: Int,
    val byTopic: List<Pair<String, Int>>,
    val longestBlockMin: Int,
    val sessionCount: Int,
    val goalMinutes: Int,
    val checkIns: List<CheckIn>,
)

data class WeekSummary(
    val studyMinutes: Int,
    val activeDays: Int,
    val workouts: Int,
    val topTopic: Pair<String, Int>?,
)

/** Writes the automatic journal from recorded data, in English or Hindi. No AI or internet needed. */
object JournalWriter {

    fun exerciseName(e: Exercise, lang: Lang, plural: Boolean = true): String = when (lang) {
        Lang.EN -> when (e) {
            Exercise.PUSHUP -> if (plural) "push-ups" else "push-up"
            Exercise.SQUAT -> if (plural) "squats" else "squat"
            Exercise.JUMPING_JACK -> if (plural) "jumping jacks" else "jumping jack"
            Exercise.PLANK -> "plank"
        }
        Lang.HI -> when (e) {
            Exercise.PUSHUP -> "पुश-अप"
            Exercise.SQUAT -> "स्क्वैट"
            Exercise.JUMPING_JACK -> "जंपिंग जैक"
            Exercise.PLANK -> "प्लैंक"
        }
    }

    fun duration(minutes: Int, lang: Lang): String {
        val h = minutes / 60
        val m = minutes % 60
        return when (lang) {
            Lang.EN -> when {
                h == 0 -> "$m min"
                m == 0 -> "$h h"
                else -> "$h h $m min"
            }
            Lang.HI -> {
                val hs = if (h == 1) "1 घंटा" else "$h घंटे"
                when {
                    h == 0 -> "$m मिनट"
                    m == 0 -> hs
                    else -> "$hs $m मिनट"
                }
            }
        }
    }

    /** m:ss in English, "1 मिनट 52 सेकंड" in Hindi. */
    fun seconds(totalSec: Int, lang: Lang): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return when (lang) {
            Lang.EN -> "$m:" + s.toString().padStart(2, '0')
            Lang.HI -> when {
                m == 0 -> "$s सेकंड"
                s == 0 -> "$m मिनट"
                else -> "$m मिनट $s सेकंड"
            }
        }
    }

    /** "6:04 AM" / "सुबह 6:04". */
    fun clock(ms: Long, zone: ZoneId, lang: Lang): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val hm = "$h12:" + t.minute.toString().padStart(2, '0')
        return when (lang) {
            Lang.EN -> "$hm " + if (t.hour < 12) "AM" else "PM"
            Lang.HI -> {
                val period = when (t.hour) {
                    in 4..11 -> "सुबह"
                    in 12..15 -> "दोपहर"
                    in 16..19 -> "शाम"
                    else -> "रात"
                }
                "$period $hm"
            }
        }
    }

    private fun workoutPhrase(w: WorkoutRecord, lang: Lang): String = when (lang) {
        Lang.EN -> if (w.exercise.isHold) "held a ${w.done}-second plank" else "did ${w.done} ${exerciseName(w.exercise, lang, w.done != 1)}"
        Lang.HI -> if (w.exercise.isHold) "${w.done} सेकंड का प्लैंक किया" else "${w.done} ${exerciseName(w.exercise, lang)} किए"
    }

    private fun topicList(byTopic: List<Pair<String, Int>>, lang: Lang): String =
        byTopic.joinToString(", ") { (t, m) -> "$t ${duration(m, lang)}" }

    /** Paragraphs of the day's journal. */
    fun write(d: DaySummary, zone: ZoneId, lang: Lang): List<String> {
        val out = mutableListOf<String>()
        val alarm = d.workouts.filter { it.viaAlarm && it.completed }.minByOrNull { it.timeMs }
        val others = d.workouts.filter { it !== alarm && it.done > 0 }

        if (alarm != null) {
            val time = clock(alarm.timeMs - alarm.durationSec * 1000L, zone, lang)
            out += when (lang) {
                Lang.EN -> if (alarm.exercise.isHold) {
                    "Woke up at $time and held a ${alarm.done}-second plank."
                } else {
                    "Woke up at $time and did ${alarm.done} ${exerciseName(alarm.exercise, lang, alarm.done != 1)} in ${seconds(alarm.durationSec, lang)}."
                }
                Lang.HI -> if (alarm.exercise.isHold) {
                    "$time बजे उठे और ${alarm.done} सेकंड का प्लैंक किया।"
                } else {
                    "$time बजे उठे और ${seconds(alarm.durationSec, lang)} में ${alarm.done} ${exerciseName(alarm.exercise, lang)} किए।"
                }
            }
        }
        if (others.isNotEmpty()) {
            val list = others.joinToString(", ") { workoutPhrase(it, lang) }
            out += when (lang) {
                Lang.EN -> "Workout: ${list.replaceFirstChar { it.uppercaseChar() }}."
                Lang.HI -> "वर्कआउट: $list।"
            }
        }

        if (d.studyMinutes > 0) {
            val total = duration(d.studyMinutes, lang)
            val list = topicList(d.byTopic, lang)
            out += when (lang) {
                Lang.EN -> if (list.isEmpty()) "Studied $total in total." else "Studied $total in total: $list."
                Lang.HI -> if (list.isEmpty()) "कुल $total पढ़ाई की।" else "कुल $total पढ़ाई की: $list।"
            }
            if (d.sessionCount > 1 && d.longestBlockMin > 0) {
                out += when (lang) {
                    Lang.EN -> "Longest focus block: ${duration(d.longestBlockMin, lang)}."
                    Lang.HI -> "सबसे लंबा फोकस ${duration(d.longestBlockMin, lang)} रहा।"
                }
            }
        }

        if (d.goalMinutes > 0 && (d.studyMinutes > 0 || alarm != null)) {
            val goal = duration(d.goalMinutes, lang)
            out += if (d.studyMinutes >= d.goalMinutes) {
                when (lang) {
                    Lang.EN -> "Daily goal of $goal reached."
                    Lang.HI -> "$goal का लक्ष्य पूरा हुआ।"
                }
            } else {
                val short = duration(d.goalMinutes - d.studyMinutes, lang)
                when (lang) {
                    Lang.EN -> "Goal was $goal, $short short."
                    Lang.HI -> "लक्ष्य $goal का था, $short कम रहे।"
                }
            }
        }

        val notes = d.checkIns.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
        if (notes.isNotEmpty()) {
            out += when (lang) {
                Lang.EN -> "Study notes:"
                Lang.HI -> "पढ़ाई के नोट्स:"
            }
            for (c in notes) {
                val topic = c.topic?.let { " · $it" } ?: ""
                out += "${clock(c.timeMs, zone, lang)}$topic: ${c.text}"
            }
        }

        if (out.isEmpty()) {
            out += when (lang) {
                Lang.EN -> "Nothing recorded for this day yet."
                Lang.HI -> "इस दिन अभी कुछ दर्ज नहीं हुआ।"
            }
        }
        return out
    }

    fun week(w: WeekSummary, lang: Lang): String {
        val hours = duration(w.studyMinutes, lang)
        val base = when (lang) {
            Lang.EN -> "This week: $hours studied on ${w.activeDays} ${if (w.activeDays == 1) "day" else "days"}, " +
                "${w.workouts} ${if (w.workouts == 1) "workout" else "workouts"}."
            Lang.HI -> "इस हफ्ते: ${w.activeDays} दिनों में $hours पढ़ाई, ${w.workouts} वर्कआउट।"
        }
        val top = w.topTopic ?: return base
        return base + " " + when (lang) {
            Lang.EN -> "Most time on ${top.first} (${duration(top.second, lang)})."
            Lang.HI -> "सबसे ज़्यादा समय ${top.first} को (${duration(top.second, lang)})।"
        }
    }
}
