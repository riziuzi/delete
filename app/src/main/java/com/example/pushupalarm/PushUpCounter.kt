package com.example.pushupalarm

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

data class Pt(val x: Float, val y: Float)

/** One side of the body (left or right) as seen by the pose model. */
data class Arm(
    val shoulder: Pt,
    val elbow: Pt,
    val wrist: Pt,
    val hip: Pt,
    val confidence: Float,
)

/**
 * Counts push-ups from the elbow angle.
 *
 * A rep = elbow goes from straight (> UP_THRESHOLD) to bent (< DOWN_THRESHOLD) and back to
 * straight. Half reps never reach DOWN_THRESHOLD, so they are not counted. The body must be
 * roughly horizontal (side-on view), so arm curls while standing do not count.
 *
 * Pure Kotlin, no Android dependencies, so it can be unit-tested on the JVM.
 */
class PushUpCounter {
    enum class Phase { UNKNOWN, UP, DOWN }

    data class Result(val count: Int, val phase: Phase, val elbowAngle: Float?, val hint: String)

    var count = 0
        private set
    private var phase = Phase.UNKNOWN
    private var smoothed: Float? = null

    fun update(left: Arm?, right: Arm?): Result {
        val arm = listOfNotNull(left, right).maxByOrNull { it.confidence }
        if (arm == null || arm.confidence < MIN_CONFIDENCE) {
            return Result(count, phase, smoothed, "Can't see you - go side-on, whole body in frame")
        }

        val torso = Math.toDegrees(
            atan2(
                abs(arm.shoulder.y - arm.hip.y).toDouble(),
                abs(arm.shoulder.x - arm.hip.x).toDouble() + 1e-6,
            ),
        )
        if (torso > MAX_TORSO_DEG) {
            phase = Phase.UNKNOWN
            smoothed = null
            return Result(count, phase, null, "Get into plank position, side-on to the phone")
        }

        val raw = elbowAngle(arm.shoulder, arm.elbow, arm.wrist)
        val s = smoothed?.let { ALPHA * raw + (1 - ALPHA) * it } ?: raw
        smoothed = s

        when (phase) {
            Phase.UNKNOWN -> if (s > UP_THRESHOLD) phase = Phase.UP
            Phase.UP -> if (s < DOWN_THRESHOLD) phase = Phase.DOWN
            Phase.DOWN -> if (s > UP_THRESHOLD) {
                phase = Phase.UP
                count++
            }
        }
        val hint = when (phase) {
            Phase.UNKNOWN -> "Straighten your arms to start"
            Phase.UP -> "Go down"
            Phase.DOWN -> "Push up!"
        }
        return Result(count, phase, s, hint)
    }

    fun reset() {
        count = 0
        phase = Phase.UNKNOWN
        smoothed = null
    }

    companion object {
        const val UP_THRESHOLD = 150f
        const val DOWN_THRESHOLD = 95f
        const val MIN_CONFIDENCE = 0.5f
        const val MAX_TORSO_DEG = 40.0
        const val ALPHA = 0.5f

        fun elbowAngle(shoulder: Pt, elbow: Pt, wrist: Pt): Float {
            val v1x = shoulder.x - elbow.x
            val v1y = shoulder.y - elbow.y
            val v2x = wrist.x - elbow.x
            val v2y = wrist.y - elbow.y
            val d = hypot(v1x, v1y) * hypot(v2x, v2y)
            if (d == 0f) return 180f
            val cos = ((v1x * v2x + v1y * v2y) / d).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos).toDouble()).toFloat()
        }
    }
}
