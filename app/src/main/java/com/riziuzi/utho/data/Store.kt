package com.riziuzi.utho.data

import android.content.Context
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.DayNote
import com.riziuzi.utho.core.Exercise
import com.riziuzi.utho.core.StudySession
import com.riziuzi.utho.core.WorkoutRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Study sessions, workouts, journal notes and learned topic examples, kept in one JSON file on the phone. */
class Store private constructor(context: Context) {
    private val file = File(context.filesDir, "utho-data.json")
    private val lock = Any()

    private val sessionsList = mutableListOf<StudySession>()
    private val workoutsList = mutableListOf<WorkoutRecord>()
    private val notesMap = mutableMapOf<String, DayNote>()
    private val examplesMap = mutableMapOf<String, MutableList<String>>()

    init {
        load()
    }

    val sessions: List<StudySession> get() = synchronized(lock) { sessionsList.toList() }
    val workouts: List<WorkoutRecord> get() = synchronized(lock) { workoutsList.toList() }
    val examples: Map<String, List<String>> get() = synchronized(lock) { examplesMap.mapValues { it.value.toList() } }

    fun activeSession(): StudySession? = synchronized(lock) { sessionsList.lastOrNull { it.active } }

    fun startSession(subject: String, now: Long = System.currentTimeMillis()): StudySession = change {
        // Only one session at a time.
        for (i in sessionsList.indices) if (sessionsList[i].active) sessionsList[i] = sessionsList[i].copy(endMs = now)
        val s = StudySession(now, now, null, subject)
        sessionsList += s
        s
    }

    /** Adds a finished session as-is (sample data for the screen tour). */
    fun addSessionForDemo(s: StudySession) = change { sessionsList += s }

    fun endSession(id: Long, now: Long = System.currentTimeMillis()) = change {
        val i = sessionsList.indexOfFirst { it.id == id }
        if (i >= 0 && sessionsList[i].active) sessionsList[i] = sessionsList[i].copy(endMs = now)
    }

    fun addCheckIn(sessionId: Long, c: CheckIn) = change {
        val i = sessionsList.indexOfFirst { it.id == sessionId }
        if (i >= 0) sessionsList[i] = sessionsList[i].copy(checkIns = sessionsList[i].checkIns + c)
    }

    /** The user fixed a check-in's topic: save it and learn from it. */
    fun correctCheckIn(sessionId: Long, timeMs: Long, topic: String) {
        var text = ""
        change {
            val i = sessionsList.indexOfFirst { it.id == sessionId }
            if (i >= 0) {
                val s = sessionsList[i]
                sessionsList[i] = s.copy(
                    checkIns = s.checkIns.map {
                        if (it.timeMs == timeMs) {
                            text = it.text
                            it.copy(topic = topic, corrected = true)
                        } else {
                            it
                        }
                    },
                )
            }
        }
        if (text.isNotBlank()) learn(topic, text)
    }

    fun learn(topic: String, text: String) = change {
        val list = examplesMap.getOrPut(topic) { mutableListOf() }
        list += text
        while (list.size > 30) list.removeAt(0)
    }

    fun addWorkout(r: WorkoutRecord) = change { workoutsList += r }

    fun note(date: LocalDate): DayNote = synchronized(lock) { notesMap[date.toString()] ?: DayNote() }

    fun setNote(date: LocalDate, n: DayNote) = change { notesMap[date.toString()] = n }

    fun clearAll() = change {
        sessionsList.clear()
        workoutsList.clear()
        notesMap.clear()
        examplesMap.clear()
    }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    private fun <T> change(block: () -> T): T {
        val r = synchronized(lock) {
            val out = block()
            save()
            out
        }
        Changes.bump()
        return r
    }

    // ---------- JSON ----------
    private fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("version", 1)
        o.put("sessions", JSONArray().apply {
            sessionsList.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("start", s.startMs)
                    put("end", s.endMs ?: JSONObject.NULL)
                    put("subject", s.subject)
                    put("checkIns", JSONArray().apply {
                        s.checkIns.forEach { c ->
                            put(JSONObject().apply {
                                put("t", c.timeMs)
                                put("text", c.text)
                                put("topic", c.topic ?: JSONObject.NULL)
                                put("kind", c.kind.name)
                                put("corrected", c.corrected)
                            })
                        }
                    })
                })
            }
        })
        o.put("workouts", JSONArray().apply {
            workoutsList.forEach { w ->
                put(JSONObject().apply {
                    put("t", w.timeMs)
                    put("exercise", w.exercise.name)
                    put("target", w.target)
                    put("done", w.done)
                    put("sec", w.durationSec)
                    put("alarm", w.viaAlarm)
                })
            }
        })
        o.put("notes", JSONObject().apply {
            notesMap.forEach { (k, n) ->
                put(k, JSONObject().apply {
                    put("mood", n.mood ?: JSONObject.NULL)
                    put("note", n.note)
                })
            }
        })
        o.put("examples", JSONObject().apply { examplesMap.forEach { (k, v) -> put(k, JSONArray(v)) } })
        return o
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONArray("sessions")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i)
                    val cs = s.optJSONArray("checkIns") ?: JSONArray()
                    val checkIns = (0 until cs.length()).map { j ->
                        val c = cs.getJSONObject(j)
                        CheckIn(
                            timeMs = c.getLong("t"),
                            text = c.optString("text", ""),
                            topic = if (c.isNull("topic")) null else c.getString("topic"),
                            kind = runCatching { CheckInKind.valueOf(c.optString("kind", "SPOKEN")) }.getOrDefault(CheckInKind.SPOKEN),
                            corrected = c.optBoolean("corrected", false),
                        )
                    }
                    sessionsList += StudySession(
                        id = s.getLong("id"),
                        startMs = s.getLong("start"),
                        endMs = if (s.isNull("end")) null else s.getLong("end"),
                        subject = s.optString("subject", ""),
                        checkIns = checkIns,
                    )
                }
            }
            o.optJSONArray("workouts")?.let { a ->
                for (i in 0 until a.length()) {
                    val w = a.getJSONObject(i)
                    val ex = runCatching { Exercise.valueOf(w.getString("exercise")) }.getOrNull() ?: continue
                    workoutsList += WorkoutRecord(w.getLong("t"), ex, w.getInt("target"), w.getInt("done"), w.getInt("sec"), w.optBoolean("alarm"))
                }
            }
            o.optJSONObject("notes")?.let { n ->
                for (k in n.keys()) {
                    val x = n.getJSONObject(k)
                    notesMap[k] = DayNote(if (x.isNull("mood")) null else x.getInt("mood"), x.optString("note", ""))
                }
            }
            o.optJSONObject("examples")?.let { e ->
                for (k in e.keys()) {
                    val a = e.getJSONArray(k)
                    examplesMap[k] = (0 until a.length()).map { a.getString(it) }.toMutableList()
                }
            }
        }
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson().toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        @Volatile private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(context.applicationContext).also { instance = it } }
    }
}
