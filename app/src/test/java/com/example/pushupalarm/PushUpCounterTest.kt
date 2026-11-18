package com.example.pushupalarm

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Mirrors the simulated-pose tests used to validate the algorithm. */
class PushUpCounterTest {

    private fun arm(elbowDeg: Double, torsoDeg: Double = 5.0): Arm {
        val t = Math.toRadians(torsoDeg)
        val hip = Pt(0f, 0f)
        val sh = Pt((cos(t) * 3).toFloat(), (-sin(t) * 3).toFloat())
        val el = Pt(sh.x, sh.y + 1f)
        val base = atan2((sh.y - el.y).toDouble(), (sh.x - el.x).toDouble())
        val a = Math.toRadians(elbowDeg)
        val wr = Pt((el.x + cos(base + a)).toFloat(), (el.y + sin(base + a)).toFloat())
        return Arm(sh, el, wr, hip, 0.9f)
    }

    private fun reps(n: Int, lo: Double = 70.0, hi: Double = 165.0, steps: Int = 30): List<Double> {
        val out = mutableListOf<Double>()
        repeat(n) {
            for (i in 0 until steps) out += hi - (hi - lo) * (1 - cos(2 * PI * i / steps)) / 2
        }
        repeat(3) { out += hi } // ends the last rep at the top
        return out
    }

    private fun run(seq: List<Double>, torso: Double = 5.0): Int {
        val c = PushUpCounter()
        seq.forEach { c.update(arm(it, torso), null) }
        return c.count
    }

    @Test fun fortyFullReps() = assertEquals(40, run(reps(40)))
    @Test fun fastReps() = assertEquals(40, run(reps(40, steps = 12)))
    @Test fun halfRepsNotCounted() = assertEquals(0, run(reps(20, lo = 120.0)))
    @Test fun standingCurlsNotCounted() = assertEquals(0, run(reps(20), torso = 85.0))
    @Test fun idleNotCounted() = assertEquals(0, run(List(200) { 165.0 }))
}
