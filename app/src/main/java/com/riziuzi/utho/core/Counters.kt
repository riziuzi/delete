package com.riziuzi.utho.core

import kotlin.math.max
import kotlin.math.min

enum class Exercise(val isHold: Boolean) {
    PUSHUP(false),
    SQUAT(false),
    JUMPING_JACK(false),
    PLANK(true),
}

enum class Level { EASY, NORMAL, STRICT }

enum class Hint {
    NOT_VISIBLE,
    GET_IN_POSITION,
    STRAIGHTEN_ARMS,
    GO_DOWN,
    PUSH_UP,
    STAND_TALL,
    SQUAT_DOWN,
    STAND_UP,
    OPEN_UP,
    CLOSE_IN,
    HOLD,
    STRAIGHTEN_BODY,
}

/**
 * [count] = reps done (or whole seconds held, for a plank).
 * [progress] = 0..1 how close the body is to the point where the rep counts (fills the depth bar).
 * [reached] = the counting point has been reached, return to the start to finish the rep.
 */
data class CounterState(val count: Int, val progress: Float, val reached: Boolean, val hint: Hint)

interface ExerciseCounter {
    val count: Int
    fun update(body: Body?, timeMs: Long): CounterState
}

object Counters {
    fun create(exercise: Exercise, level: Level): ExerciseCounter = when (exercise) {
        Exercise.PUSHUP -> PushUpCounter(level)
        Exercise.SQUAT -> SquatCounter(level)
        Exercise.JUMPING_JACK -> JumpingJackCounter(level)
        Exercise.PLANK -> PlankTimer(level)
    }
}

private enum class Phase { UNKNOWN, START, BOTTOM }

/** Light smoothing so reps register quickly but jitter does not double count. */
private const val ALPHA = 0.75f

private fun smooth(prev: Float?, raw: Float) = prev?.let { ALPHA * raw + (1 - ALPHA) * it } ?: raw

/**
 * Push-ups from the elbow angle: straight (> up) -> bent (< down) -> straight = 1 rep.
 * Works side-on or facing the phone. Frames where the person is standing (long upright torso) are ignored.
 */
class PushUpCounter(level: Level = Level.EASY) : ExerciseCounter {
    val up: Float
    val down: Float

    init {
        val (u, d) = when (level) {
            Level.EASY -> 140f to 120f
            Level.NORMAL -> 150f to 105f
            Level.STRICT -> 160f to 90f
        }
        up = u
        down = d
    }

    override var count = 0
        private set
    private var phase = Phase.UNKNOWN
    private var smoothed: Float? = null

    override fun update(body: Body?, timeMs: Long): CounterState {
        val b = body ?: return CounterState(count, 0f, phase == Phase.BOTTOM, Hint.NOT_VISIBLE)
        val sides = b.sides { listOf(it.shoulder, it.elbow, it.wrist) }
        if (sides.isEmpty()) return CounterState(count, 0f, phase == Phase.BOTTOM, Hint.NOT_VISIBLE)

        if (sides.any { s -> b[s.hip]?.let { hip -> isStanding(b.p(s.shoulder), b.p(s.elbow), b.p(s.wrist), hip) } == true }) {
            phase = Phase.UNKNOWN
            smoothed = null
            return CounterState(count, 0f, false, Hint.GET_IN_POSITION)
        }

        val raw = sides.map { Geo.angle(b.p(it.shoulder), b.p(it.elbow), b.p(it.wrist)) }.average().toFloat()
        val s = smooth(smoothed, raw)
        smoothed = s
        when (phase) {
            Phase.UNKNOWN -> if (s > up) phase = Phase.START
            Phase.START -> if (s < down) phase = Phase.BOTTOM
            Phase.BOTTOM -> if (s > up) {
                phase = Phase.START
                count++
            }
        }
        val progress = ((up - s) / (up - down)).coerceIn(0f, 1f)
        val hint = when (phase) {
            Phase.UNKNOWN -> Hint.STRAIGHTEN_ARMS
            Phase.START -> Hint.GO_DOWN
            Phase.BOTTOM -> Hint.PUSH_UP
        }
        return CounterState(count, progress, phase == Phase.BOTTOM, hint)
    }

    companion object {
        /** Upright AND long torso = standing. Facing the phone in a plank the torso looks short. */
        fun isStanding(shoulder: Pt, elbow: Pt, wrist: Pt, hip: Pt): Boolean {
            val torsoLen = Geo.dist(shoulder, hip)
            val armLen = Geo.dist(shoulder, elbow) + Geo.dist(elbow, wrist)
            return Geo.tilt(shoulder, hip) > 60.0 && torsoLen > 0.8f * armLen
        }
    }
}

/** Squats from the knee angle (hip-knee-ankle): standing (> up) -> bent (< down) -> standing = 1 rep. */
class SquatCounter(level: Level = Level.EASY) : ExerciseCounter {
    val up: Float
    val down: Float

    init {
        val (u, d) = when (level) {
            Level.EASY -> 150f to 125f
            Level.NORMAL -> 160f to 105f
            Level.STRICT -> 165f to 90f
        }
        up = u
        down = d
    }

    override var count = 0
        private set
    private var phase = Phase.UNKNOWN
    private var smoothed: Float? = null

    override fun update(body: Body?, timeMs: Long): CounterState {
        val b = body ?: return CounterState(count, 0f, phase == Phase.BOTTOM, Hint.NOT_VISIBLE)
        val sides = b.sides { listOf(it.hip, it.knee, it.ankle) }
        if (sides.isEmpty()) return CounterState(count, 0f, phase == Phase.BOTTOM, Hint.NOT_VISIBLE)

        // Lying down (plank, push-ups): hip and ankle at about the same height. Not a squat.
        val lying = sides.all { Geo.tilt(b.p(it.hip), b.p(it.ankle)) < 25.0 }
        if (lying) {
            phase = Phase.UNKNOWN
            smoothed = null
            return CounterState(count, 0f, false, Hint.STAND_TALL)
        }

        val raw = sides.map { Geo.angle(b.p(it.hip), b.p(it.knee), b.p(it.ankle)) }.average().toFloat()
        val s = smooth(smoothed, raw)
        smoothed = s
        when (phase) {
            Phase.UNKNOWN -> if (s > up) phase = Phase.START
            Phase.START -> if (s < down) phase = Phase.BOTTOM
            Phase.BOTTOM -> if (s > up) {
                phase = Phase.START
                count++
            }
        }
        val progress = ((up - s) / (up - down)).coerceIn(0f, 1f)
        val hint = when (phase) {
            Phase.UNKNOWN -> Hint.STAND_TALL
            Phase.START -> Hint.SQUAT_DOWN
            Phase.BOTTOM -> Hint.STAND_UP
        }
        return CounterState(count, progress, phase == Phase.BOTTOM, hint)
    }
}

/**
 * Jumping jacks, facing the phone. "Open" = wrists above the shoulders and feet apart;
 * "closed" = arms down and feet together. closed -> open -> closed = 1 rep.
 * Distances are measured in shoulder widths so the distance to the phone does not matter.
 */
class JumpingJackCounter(level: Level = Level.EASY) : ExerciseCounter {
    /** How far the wrists must rise above the shoulders, in shoulder widths. */
    val armsOpen: Float
    /** How wide the feet must be, in shoulder widths. */
    val legsOpen: Float
    private val armsClosed = -0.3f
    private val legsClosed = 1.1f

    init {
        val (a, l) = when (level) {
            Level.EASY -> 0.0f to 1.2f
            Level.NORMAL -> 0.5f to 1.4f
            Level.STRICT -> 1.0f to 1.6f
        }
        armsOpen = a
        legsOpen = l
    }

    override var count = 0
        private set
    private var phase = Phase.UNKNOWN
    private var arms: Float? = null
    private var legs: Float? = null

    override fun update(body: Body?, timeMs: Long): CounterState {
        val b = body ?: return CounterState(count, 0f, phase == Phase.BOTTOM, Hint.NOT_VISIBLE)
        if (!b.has(Joint.L_SHOULDER, Joint.R_SHOULDER, Joint.L_WRIST, Joint.R_WRIST, Joint.L_ANKLE, Joint.R_ANKLE)) {
            return CounterState(count, 0f, phase == Phase.BOTTOM, Hint.NOT_VISIBLE)
        }
        val width = Geo.dist(b.p(Joint.L_SHOULDER), b.p(Joint.R_SHOULDER))
        if (width < 1e-3f) return CounterState(count, 0f, phase == Phase.BOTTOM, Hint.GET_IN_POSITION)

        val shoulderY = (b.p(Joint.L_SHOULDER).y + b.p(Joint.R_SHOULDER).y) / 2f
        val wristY = (b.p(Joint.L_WRIST).y + b.p(Joint.R_WRIST).y) / 2f
        val armsRaw = (shoulderY - wristY) / width
        val legsRaw = Geo.dist(b.p(Joint.L_ANKLE), b.p(Joint.R_ANKLE)) / width
        val a = smooth(arms, armsRaw).also { arms = it }
        val l = smooth(legs, legsRaw).also { legs = it }

        val isOpen = a > armsOpen && l > legsOpen
        val isClosed = a < armsClosed && l < legsClosed
        when (phase) {
            Phase.UNKNOWN -> if (isClosed) phase = Phase.START
            Phase.START -> if (isOpen) phase = Phase.BOTTOM
            Phase.BOTTOM -> if (isClosed) {
                phase = Phase.START
                count++
            }
        }
        val armP = ((a - armsClosed) / (armsOpen - armsClosed)).coerceIn(0f, 1f)
        val legP = ((l - legsClosed) / (legsOpen - legsClosed)).coerceIn(0f, 1f)
        val hint = when (phase) {
            Phase.UNKNOWN -> Hint.STAND_TALL
            Phase.START -> Hint.OPEN_UP
            Phase.BOTTOM -> Hint.CLOSE_IN
        }
        return CounterState(count, min(armP, legP), phase == Phase.BOTTOM, hint)
    }
}

/**
 * Plank hold. Time counts while the body is straight (shoulder-hip-ankle) and roughly horizontal.
 * Short tracking drop-outs (under [graceMs]) do not stop the clock. [count] = whole seconds held.
 */
class PlankTimer(level: Level = Level.EASY, private val graceMs: Long = 700) : ExerciseCounter {
    val straight: Float = when (level) {
        Level.EASY -> 150f
        Level.NORMAL -> 160f
        Level.STRICT -> 168f
    }
    private val maxTilt = 35.0

    private var heldMs = 0L
    private var lastMs: Long? = null
    private var lastGoodMs: Long? = null
    private var smoothed: Float? = null

    override val count: Int get() = (heldMs / 1000).toInt()

    val heldMillis: Long get() = heldMs

    override fun update(body: Body?, timeMs: Long): CounterState {
        val prev = lastMs
        lastMs = timeMs
        val dt = if (prev == null) 0L else max(0L, min(timeMs - prev, 1000L))

        val sides = body?.sides { listOf(it.shoulder, it.hip, it.ankle) } ?: emptyList()
        if (body == null || sides.isEmpty()) {
            creditGrace(timeMs, dt)
            return CounterState(count, 0f, false, Hint.NOT_VISIBLE)
        }
        val raw = sides.map { Geo.angle(body.p(it.shoulder), body.p(it.hip), body.p(it.ankle)) }.average().toFloat()
        val s = smooth(smoothed, raw).also { smoothed = it }
        val horizontal = sides.all { Geo.tilt(body.p(it.shoulder), body.p(it.ankle)) < maxTilt }
        val progress = ((s - 120f) / (straight - 120f)).coerceIn(0f, 1f)

        if (!horizontal) {
            lastGoodMs = null
            return CounterState(count, 0f, false, Hint.GET_IN_POSITION)
        }
        if (s >= straight) {
            heldMs += dt
            lastGoodMs = timeMs
            return CounterState(count, 1f, true, Hint.HOLD)
        }
        creditGrace(timeMs, dt)
        return CounterState(count, progress, false, Hint.STRAIGHTEN_BODY)
    }

    private fun creditGrace(timeMs: Long, dt: Long) {
        val good = lastGoodMs ?: return
        if (timeMs - good <= graceMs) heldMs += dt else lastGoodMs = null
    }
}
