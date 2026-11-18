package com.riziuzi.utho.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Simulated poses for each exercise. Image coordinates: y grows downward. */
class CountersTest {

    private fun cycle(n: Int, lo: Double, hi: Double, steps: Int = 30): List<Double> {
        val out = mutableListOf<Double>()
        repeat(n) { for (i in 0 until steps) out += hi - (hi - lo) * (1 - cos(2 * PI * i / steps)) / 2 }
        repeat(3) { out += hi }
        return out
    }

    private fun jitter(p: Pt, rnd: Random?, amount: Float = 0.08f) =
        if (rnd == null) p else Pt(p.x + (rnd.nextFloat() - 0.5f) * amount, p.y + (rnd.nextFloat() - 0.5f) * amount)

    // ---------- push-ups ----------
    private enum class View { SIDE, FRONT, STANDING, NO_HIP }

    private fun pushBody(elbowDeg: Double, view: View, rnd: Random? = null): Body {
        val sh = Pt(0f, 0f)
        val el = Pt(0f, 1f)
        val base = atan2((sh.y - el.y).toDouble(), (sh.x - el.x).toDouble())
        val a = Math.toRadians(elbowDeg)
        val wr = Pt((el.x + cos(base + a)).toFloat(), (el.y + sin(base + a)).toFloat())
        val hip = when (view) {
            View.SIDE -> Pt(-3f, 0.2f)
            View.FRONT -> Pt(0.1f, -0.5f)
            View.STANDING -> Pt(0.1f, 3f)
            View.NO_HIP -> null
        }
        val m = mutableMapOf(Joint.L_SHOULDER to jitter(sh, rnd), Joint.L_ELBOW to jitter(el, rnd), Joint.L_WRIST to jitter(wr, rnd))
        if (hip != null) m[Joint.L_HIP] = hip
        return Body(m)
    }

    private fun runPush(seq: List<Double>, level: Level, view: View = View.SIDE, noisy: Boolean = false): Int {
        val c = PushUpCounter(level)
        val rnd = if (noisy) Random(1) else null
        seq.forEachIndexed { i, a -> c.update(pushBody(a, view, rnd), i * 33L) }
        return c.count
    }

    @Test fun pushNormalFull() = assertEquals(40, runPush(cycle(40, 75.0, 165.0), Level.NORMAL))
    @Test fun pushEasyNoisy() = assertEquals(40, runPush(cycle(40, 75.0, 165.0), Level.EASY, noisy = true))
    @Test fun pushEasyFast() = assertEquals(40, runPush(cycle(40, 95.0, 165.0, steps = 8), Level.EASY))
    @Test fun pushEasyShallow() = assertEquals(20, runPush(cycle(20, 110.0, 150.0), Level.EASY))
    @Test fun pushStrictIgnoresShallow() = assertEquals(0, runPush(cycle(20, 110.0, 150.0), Level.STRICT))
    @Test fun pushFrontView() = assertEquals(20, runPush(cycle(20, 100.0, 165.0), Level.EASY, View.FRONT))
    @Test fun pushNoHip() = assertEquals(20, runPush(cycle(20, 100.0, 165.0), Level.EASY, View.NO_HIP))
    @Test fun pushStandingCurlsIgnored() = assertEquals(0, runPush(cycle(20, 60.0, 165.0), Level.EASY, View.STANDING))
    @Test fun pushIdle() = assertEquals(0, runPush(List(200) { 165.0 }, Level.EASY, noisy = true))

    @Test fun pushNothingVisible() {
        val c = PushUpCounter()
        val s = c.update(null, 0)
        assertEquals(Hint.NOT_VISIBLE, s.hint)
    }

    // ---------- squats ----------
    private fun squatBody(kneeDeg: Double, lying: Boolean = false): Body {
        if (lying) {
            return Body(mapOf(Joint.R_HIP to Pt(0f, 0f), Joint.R_KNEE to Pt(1f, 0.05f), Joint.R_ANKLE to Pt(2f, 0.1f)))
        }
        val knee = Pt(0f, 0f)
        val ankle = Pt(0f, 1f)
        val dir = Math.toRadians(90.0 + kneeDeg)
        val hip = Pt(cos(dir).toFloat(), sin(dir).toFloat())
        return Body(mapOf(Joint.R_HIP to hip, Joint.R_KNEE to knee, Joint.R_ANKLE to ankle))
    }

    private fun runSquat(seq: List<Double>, level: Level, lying: Boolean = false): Int {
        val c = SquatCounter(level)
        seq.forEachIndexed { i, a -> c.update(squatBody(a, lying), i * 33L) }
        return c.count
    }

    @Test fun squatFull() = assertEquals(25, runSquat(cycle(25, 80.0, 175.0), Level.NORMAL))
    @Test fun squatEasyShallow() = assertEquals(15, runSquat(cycle(15, 115.0, 170.0), Level.EASY))
    @Test fun squatStrictIgnoresShallow() = assertEquals(0, runSquat(cycle(15, 115.0, 170.0), Level.STRICT))
    @Test fun squatLyingIgnored() = assertEquals(0, runSquat(cycle(10, 80.0, 175.0), Level.EASY, lying = true))

    // ---------- jumping jacks ----------
    private fun jackBody(t: Double): Body {
        val wristY = (2 - 4 * t).toFloat()
        val ankleX = (0.6 + 1.9 * t).toFloat()
        return Body(
            mapOf(
                Joint.L_SHOULDER to Pt(-1f, 0f), Joint.R_SHOULDER to Pt(1f, 0f),
                Joint.L_WRIST to Pt(-1.3f, wristY), Joint.R_WRIST to Pt(1.3f, wristY),
                Joint.L_ANKLE to Pt(-ankleX, 5f), Joint.R_ANKLE to Pt(ankleX, 5f),
            ),
        )
    }

    private fun runJacks(n: Int, maxOpen: Double, level: Level, steps: Int = 20): Int {
        val c = JumpingJackCounter(level)
        var i = 0L
        repeat(n) { for (k in 0 until steps) c.update(jackBody(maxOpen * (1 - cos(2 * PI * k / steps)) / 2), i++ * 33) }
        repeat(3) { c.update(jackBody(0.0), i++ * 33) }
        return c.count
    }

    @Test fun jacksFull() = assertEquals(30, runJacks(30, 1.0, Level.NORMAL))
    @Test fun jacksFast() = assertEquals(30, runJacks(30, 1.0, Level.EASY, steps = 8))
    @Test fun jacksHalfArmsOnlyCountOnEasy() {
        assertEquals(10, runJacks(10, 0.6, Level.EASY))
        assertEquals(0, runJacks(10, 0.6, Level.STRICT))
    }

    // ---------- plank ----------
    private fun plankBody(hipSag: Float, standing: Boolean = false): Body {
        if (standing) {
            return Body(mapOf(Joint.L_SHOULDER to Pt(0f, -3f), Joint.L_HIP to Pt(0f, 0f), Joint.L_ANKLE to Pt(0f, 3f)))
        }
        return Body(mapOf(Joint.L_SHOULDER to Pt(3f, -0.3f), Joint.L_HIP to Pt(0f, hipSag), Joint.L_ANKLE to Pt(-3f, 0.3f)))
    }

    @Test fun plankStraightCounts() {
        val c = PlankTimer(Level.EASY)
        for (i in 0..300) c.update(plankBody(0f), i * 100L) // 30 s at 10 fps
        assertEquals(30, c.count)
    }

    @Test fun plankSaggingDoesNotCount() {
        val c = PlankTimer(Level.EASY)
        for (i in 0..300) c.update(plankBody(1f), i * 100L)
        assertEquals(0, c.count)
    }

    @Test fun plankStandingDoesNotCount() {
        val c = PlankTimer(Level.EASY)
        for (i in 0..300) c.update(plankBody(0f, standing = true), i * 100L)
        assertEquals(0, c.count)
    }

    @Test fun plankShortDropoutKeepsCounting() {
        val c = PlankTimer(Level.EASY)
        var t = 0L
        repeat(100) { c.update(plankBody(0f), t); t += 100 }   // 10 s
        repeat(5) { c.update(null, t); t += 100 }               // 0.5 s lost tracking
        repeat(100) { c.update(plankBody(0f), t); t += 100 }   // 10 s
        assertTrue("held ${c.count}", c.count in 20..21)
    }

    @Test fun plankLongDropoutStops() {
        val c = PlankTimer(Level.EASY)
        var t = 0L
        repeat(100) { c.update(plankBody(0f), t); t += 100 }
        repeat(50) { c.update(null, t); t += 100 }              // 5 s gone
        assertTrue("held ${c.count}", c.count in 9..11)
    }

    @Test fun factoryMakesEachCounter() {
        assertTrue(Counters.create(Exercise.PUSHUP, Level.EASY) is PushUpCounter)
        assertTrue(Counters.create(Exercise.SQUAT, Level.EASY) is SquatCounter)
        assertTrue(Counters.create(Exercise.JUMPING_JACK, Level.EASY) is JumpingJackCounter)
        assertTrue(Counters.create(Exercise.PLANK, Level.EASY) is PlankTimer)
    }
}
