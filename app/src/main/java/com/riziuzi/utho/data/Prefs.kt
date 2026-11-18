package com.riziuzi.utho.data

import android.content.Context
import android.content.SharedPreferences
import com.riziuzi.utho.core.Exercise
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.core.Level
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

/** Bumped on every saved change so screens recompute. */
object Changes {
    val tick = MutableStateFlow(0)
    fun bump() {
        tick.value = tick.value + 1
    }
}

enum class VoiceLang { MIX, HINDI, ENGLISH }

/** Settings and profile, stored on the phone. */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("utho", Context.MODE_PRIVATE)

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(block).apply()
        Changes.bump()
    }

    // ---- profile ----
    var setupDone: Boolean
        get() = p.getBoolean("setupDone", false)
        set(v) = edit { putBoolean("setupDone", v) }

    var name: String
        get() = p.getString("name", "") ?: ""
        set(v) = edit { putString("name", v.trim()) }

    /** One of [Exams.keys]. */
    var exam: String
        get() = p.getString("exam", "UPSC") ?: "UPSC"
        set(v) = edit { putString("exam", v) }

    /** Epoch day of the exam, or -1. */
    var examDate: Long
        get() = p.getLong("examDate", -1L)
        set(v) = edit { putLong("examDate", v) }

    var dailyGoalMin: Int
        get() = p.getInt("dailyGoalMin", 360)
        set(v) = edit { putInt("dailyGoalMin", v) }

    var subjects: List<String>
        get() {
            val raw = p.getString("subjects", null) ?: return emptyList()
            return runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).map { a.getString(it) }
            }.getOrDefault(emptyList())
        }
        set(v) = edit { putString("subjects", JSONArray(v.map { it.trim() }.filter { it.isNotEmpty() }.distinct()).toString()) }

    var lang: Lang
        get() = if (p.getString("lang", "en") == "hi") Lang.HI else Lang.EN
        set(v) = edit { putString("lang", if (v == Lang.HI) "hi" else "en") }

    /** "system", "light" or "dark". */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) = edit { putString("theme", v) }

    // ---- wake-up alarm ----
    var alarmEnabled: Boolean
        get() = p.getBoolean("alarmEnabled", false)
        set(v) = edit { putBoolean("alarmEnabled", v) }

    var alarmHour: Int
        get() = p.getInt("alarmHour", 6)
        set(v) = edit { putInt("alarmHour", v) }

    var alarmMinute: Int
        get() = p.getInt("alarmMinute", 0)
        set(v) = edit { putInt("alarmMinute", v) }

    /** Bit 0 = Monday ... bit 6 = Sunday. Default Monday-Saturday. */
    var alarmDays: Int
        get() = p.getInt("alarmDays", 0b0111111)
        set(v) = edit { putInt("alarmDays", v) }

    var exercise: Exercise
        get() = runCatching { Exercise.valueOf(p.getString("exercise", "PUSHUP")!!) }.getOrDefault(Exercise.PUSHUP)
        set(v) = edit { putString("exercise", v.name) }

    /** Reps, or seconds for a plank. */
    var alarmTarget: Int
        get() = p.getInt("alarmTarget", 40)
        set(v) = edit { putInt("alarmTarget", v) }

    var level: Level
        get() = runCatching { Level.valueOf(p.getString("level", "EASY")!!) }.getOrDefault(Level.EASY)
        set(v) = edit { putString("level", v.name) }

    var awakeCheck: Boolean
        get() = p.getBoolean("awakeCheck", false)
        set(v) = edit { putBoolean("awakeCheck", v) }

    var emergencySec: Int
        get() = p.getInt("emergencySec", 20)
        set(v) = edit { putInt("emergencySec", v) }

    var beepVolume: Int
        get() = p.getInt("beepVolume", 80)
        set(v) = edit { putInt("beepVolume", v) }

    // ---- study ----
    /** Minutes between voice check-ins while studying. 0 = off. */
    /** Sound when a voice note's mic opens and closes (see Earcons): bowl, chime, drop, pad or off. */
    var checkInSound: String
        get() = p.getString("checkInSound", com.riziuzi.utho.voice.Earcons.DEFAULT) ?: com.riziuzi.utho.voice.Earcons.DEFAULT
        set(v) = edit { putString("checkInSound", v) }

    var checkInMin: Int
        get() = p.getInt("checkInMin", 30)
        set(v) = edit { putInt("checkInMin", v) }

    var nextCheckInMs: Long
        get() = p.getLong("nextCheckInMs", -1L)
        set(v) = edit { putLong("nextCheckInMs", v) }

    var voiceLang: VoiceLang
        get() = runCatching { VoiceLang.valueOf(p.getString("voiceLang", "MIX")!!) }.getOrDefault(VoiceLang.MIX)
        set(v) = edit { putString("voiceLang", v.name) }

    var studyReminderOn: Boolean
        get() = p.getBoolean("studyReminderOn", true)
        set(v) = edit { putBoolean("studyReminderOn", v) }

    /** Minute of the day. */
    var studyReminderMin: Int
        get() = p.getInt("studyReminderMin", 9 * 60)
        set(v) = edit { putInt("studyReminderMin", v) }

    var journalReminderOn: Boolean
        get() = p.getBoolean("journalReminderOn", true)
        set(v) = edit { putBoolean("journalReminderOn", v) }

    var journalReminderMin: Int
        get() = p.getInt("journalReminderMin", 22 * 60)
        set(v) = edit { putInt("journalReminderMin", v) }

    fun clearAll() = edit { clear() }
}

/** Exams shown at setup, with their starting subjects. */
object Exams {
    data class Exam(val key: String, val name: String, val subjects: List<String>)

    val all = listOf(
        Exam("UPSC", "UPSC CSE", listOf("Polity", "History", "Geography", "Economy", "Environment", "Science & Tech", "Ethics", "Essay", "CSAT", "Current affairs")),
        Exam("PSC", "State PSC", listOf("Polity", "History", "Geography", "Economy", "State GK", "Current affairs", "CSAT")),
        Exam("SSC", "SSC (CGL, CHSL)", listOf("Maths", "Reasoning", "English", "General awareness")),
        Exam("BANK", "Banking (IBPS, SBI)", listOf("Quant", "Reasoning", "English", "Banking awareness", "Current affairs")),
        Exam("RRB", "Railways (RRB)", listOf("Maths", "Reasoning", "General science", "General awareness")),
        Exam("DEF", "Defence (NDA, CDS)", listOf("Maths", "English", "General knowledge")),
        Exam("NEET", "NEET", listOf("Physics", "Chemistry", "Biology")),
        Exam("JEE", "JEE", listOf("Physics", "Chemistry", "Maths")),
        Exam("TET", "Teaching (CTET, TET)", listOf("Child development", "Maths", "EVS", "Language")),
        Exam("OTHER", "Other", listOf("Reading", "Practice", "Revision")),
    )

    fun byKey(key: String) = all.firstOrNull { it.key == key } ?: all.first()
}
