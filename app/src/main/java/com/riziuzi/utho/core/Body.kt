package com.riziuzi.utho.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

data class Pt(val x: Float, val y: Float)

object Geo {
    /** Angle in degrees at [b] formed by a-b-c. */
    fun angle(a: Pt, b: Pt, c: Pt): Float {
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val d = hypot(v1x, v1y) * hypot(v2x, v2y)
        if (d == 0f) return 180f
        val cos = ((v1x * v2x + v1y * v2y) / d).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

    /** 0 = the line a-b is horizontal, 90 = vertical. */
    fun tilt(a: Pt, b: Pt): Double =
        Math.toDegrees(atan2(abs(a.y - b.y).toDouble(), abs(a.x - b.x).toDouble() + 1e-6))

    fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}

enum class Joint {
    NOSE,
    L_SHOULDER, R_SHOULDER,
    L_ELBOW, R_ELBOW,
    L_WRIST, R_WRIST,
    L_HIP, R_HIP,
    L_KNEE, R_KNEE,
    L_ANKLE, R_ANKLE,
}

enum class Side(
    val shoulder: Joint,
    val elbow: Joint,
    val wrist: Joint,
    val hip: Joint,
    val knee: Joint,
    val ankle: Joint,
) {
    LEFT(Joint.L_SHOULDER, Joint.L_ELBOW, Joint.L_WRIST, Joint.L_HIP, Joint.L_KNEE, Joint.L_ANKLE),
    RIGHT(Joint.R_SHOULDER, Joint.R_ELBOW, Joint.R_WRIST, Joint.R_HIP, Joint.R_KNEE, Joint.R_ANKLE),
}

/** The visible joints of one person in one frame, in upright image coordinates (y grows downward). */
class Body(val points: Map<Joint, Pt>) {
    operator fun get(j: Joint): Pt? = points[j]

    fun has(vararg joints: Joint) = joints.all { points.containsKey(it) }

    fun p(j: Joint): Pt = points.getValue(j)

    /** Sides for which every joint picked by [need] is visible. */
    fun sides(need: (Side) -> List<Joint>): List<Side> =
        Side.entries.filter { s -> need(s).all { points.containsKey(it) } }
}
