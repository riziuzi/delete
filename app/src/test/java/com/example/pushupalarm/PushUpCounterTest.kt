package com.example.pushupalarm

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Simulated poses: upper arm and forearm length 1 (arm length 2). */
class PushUpCounterTest {

    private enum class View { SIDE, FRONT, STANDING, NO_HIP }

    private fun arm(elbowDeg: Double, view: View, rnd: Random?): Arm {
        val sh = Pt(0f, 0f)
        val el = Pt(0f, 1f)
        val base = atan2((sh.y - el.y).toDouble(), (sh.x - el.x).toDouble())
        val a = Math.toRadians(elbowDeg)
        val wr = Pt((el.x + cos(base + a)).toFloat(), (el.y + sin(base + a)).toFloat())
        val hip = when (view) {
            View.SIDE -> Pt(-3f, 0.2f) // torso horizontal
            View.FRONT -> Pt(0.1f, -0.5f) // facing the phone: torso foreshortened
            View.STANDING -> Pt(0.1f, 3f) // torso upright and long
            View.NO_HIP -> null
        }
        fun j(p: Pt) = if (rnd == null) p else Pt(p.x + (rnd.nextFloat() - 0.5f) * 0.08f, p.y + (rnd.nextFloat() - 0.5f) * 0.08f)
        return Arm(j(sh), j(el), j(wr), hip, 0.9f)
    }

    private fun reps(n: Int, lo: Double, hi: Double = 165.0, steps: Int = 30): List<Double> {
        val out = mutableListOf<Double>()
        repeat(n) {
            for (i in 0 until steps) out += hi - (hi - lo) * (1 - cos(2 * PI * i / steps)) / 2
        }
        repeat(3) { out += hi }
        return out
    }

    private fun run(seq: List<Double>, level: Level, view: View = View.SIDE, noisy: Boolean = false): Int {
        val c = PushUpCounter(level)
        val rnd = if (noisy) Random(1) else null
        seq.forEach { c.update(arm(it, view, rnd), null) }
        return c.count
    }

    @Test fun normalFullReps() = assertEquals(40, run(reps(40, lo = 75.0), Level.NORMAL))
    @Test fun easyFullRepsNoisy() = assertEquals(40, run(reps(40, lo = 75.0), Level.EASY, noisy = true))
    @Test fun easyFastReps() = assertEquals(40, run(reps(40, lo = 95.0, steps = 8), Level.EASY))
    @Test fun easyCountsShallowReps() = assertEquals(20, run(reps(20, lo = 110.0, hi = 150.0), Level.EASY))
    @Test fun strictIgnoresShallowReps() = assertEquals(0, run(reps(20, lo = 110.0, hi = 150.0), Level.STRICT))
    @Test fun frontViewCounts() = assertEquals(20, run(reps(20, lo = 100.0), Level.EASY, View.FRONT))
    @Test fun hipOutOfViewCounts() = assertEquals(20, run(reps(20, lo = 100.0), Level.EASY, View.NO_HIP))
    @Test fun standingCurlsIgnored() = assertEquals(0, run(reps(20, lo = 60.0), Level.EASY, View.STANDING))
    @Test fun idleIgnored() = assertEquals(0, run(List(200) { 165.0 }, Level.EASY, noisy = true))
}
