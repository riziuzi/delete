package com.example.pushupalarm

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

data class Pt(val x: Float, val y: Float)

/** One side of the body as seen by the pose model. [hip] may be null if it is out of view. */
data class Arm(
    val shoulder: Pt,
    val elbow: Pt,
    val wrist: Pt,
    val hip: Pt?,
    val confidence: Float,
)

/** How far the elbows must bend. The gap between [up] and [down] stops jitter double-counting. */
enum class Level(val label: String, val up: Float, val down: Float) {
    EASY("Easy", 140f, 120f),
    NORMAL("Normal", 150f, 105f),
    STRICT("Strict", 160f, 90f),
}

/**
 * Counts push-ups from the elbow angle.
 *
 * A rep = elbow goes from straight (> level.up) to bent (< level.down) and back to straight.
 * Works side-on (best) and facing the phone. The only posture rule: if the hip is visible and
 * the torso is long and upright (standing), frames are ignored, so standing arm curls don't count.
 *
 * Pure Kotlin, no Android dependencies, so it is unit-tested on the JVM.
 */
class PushUpCounter(val level: Level = Level.EASY) {
    enum class Phase { UNKNOWN, UP, DOWN }

    data class Result(val count: Int, val phase: Phase, val elbowAngle: Float?, val hint: String)

    var count = 0
        private set
    private var phase = Phase.UNKNOWN
    private var smoothed: Float? = null

    fun update(left: Arm?, right: Arm?): Result {
        val arm = listOfNotNull(left, right).maxByOrNull { it.confidence }
        if (arm == null || arm.confidence < MIN_CONFIDENCE) {
            return Result(count, phase, smoothed, "Can't see your arms - move back a little")
        }

        if (isStanding(arm)) {
            phase = Phase.UNKNOWN
            smoothed = null
            return Result(count, phase, null, "Get into push-up position")
        }

        val raw = elbowAngle(arm.shoulder, arm.elbow, arm.wrist)
        val s = smoothed?.let { ALPHA * raw + (1 - ALPHA) * it } ?: raw
        smoothed = s

        when (phase) {
            Phase.UNKNOWN -> if (s > level.up) phase = Phase.UP
            Phase.UP -> if (s < level.down) phase = Phase.DOWN
            Phase.DOWN -> if (s > level.up) {
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
        const val MIN_CONFIDENCE = 0.5f
        /** Light smoothing: high = reacts faster. */
        const val ALPHA = 0.75f
        const val STANDING_TORSO_DEG = 60.0
        const val STANDING_TORSO_TO_ARM = 0.8f

        fun isStanding(arm: Arm): Boolean {
            val hip = arm.hip ?: return false
            val dx = abs(arm.shoulder.x - hip.x)
            val dy = abs(arm.shoulder.y - hip.y)
            val torsoDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble() + 1e-6))
            val torsoLen = hypot(dx, dy)
            val armLen = hypot(arm.shoulder.x - arm.elbow.x, arm.shoulder.y - arm.elbow.y) +
                hypot(arm.elbow.x - arm.wrist.x, arm.elbow.y - arm.wrist.y)
            // Upright AND long: standing. Facing the phone in a plank the torso looks short.
            return torsoDeg > STANDING_TORSO_DEG && torsoLen > STANDING_TORSO_TO_ARM * armLen
        }

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
