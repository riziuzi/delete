package com.riziuzi.utho.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media stays muted from before the mic opens until well after the last session using it has closed. */
class QuietWindowTest {
    private class Phone(var muted: Boolean = false, var saved: Boolean = false) {
        val changes = mutableListOf<Boolean>()
        fun window(hold: Long = 8000) = QuietWindow(
            hold,
            isMuted = { muted },
            setMuted = { muted = it; changes += it },
            remembered = { saved },
            remember = { saved = it },
        )
    }

    @Test fun mutesOnStartAndUnmutesOnlyAfterTheHold() {
        val p = Phone()
        val w = p.window()
        w.acquire(0)
        assertTrue(p.muted)
        val next = w.release(15_000)
        assertEquals(23_000L, next)
        assertEquals(23_000L, w.check(20_000)) // too early: still muted
        assertTrue(p.muted)
        assertNull(w.check(23_000))
        assertFalse(p.muted)
        assertEquals(listOf(true, false), p.changes)
        assertFalse(p.saved)
    }

    @Test fun aSecondSessionKeepsItMutedWhenTheFirstEnds() {
        // Listen, Type, then Speak again within the hold; or a pop-up recreated while listening.
        val p = Phone()
        val w = p.window()
        w.acquire(0)
        w.release(10_000)
        w.acquire(12_000) // listening again before the first hold ran out
        assertNull(w.check(18_000)) // first session's check: someone still holds it
        assertTrue(p.muted)
        val next = w.release(40_000)
        assertEquals(48_000L, next)
        assertEquals(48_000L, w.check(47_999))
        assertTrue(p.muted)
        assertNull(w.check(48_000))
        assertFalse(p.muted)
        assertEquals(listOf(true, false), p.changes) // muted once, unmuted once
    }

    @Test fun overlappingSessionsShareOneWindow() {
        val p = Phone()
        val w = p.window()
        w.acquire(0) // old pop-up
        w.acquire(1_000) // new pop-up (recreated)
        w.release(2_000) // old one goes away
        assertNull(w.check(20_000))
        assertTrue(p.muted)
        w.release(30_000)
        assertNull(w.check(38_000))
        assertFalse(p.muted)
    }

    @Test fun mediaTheUserMutedStaysMuted() {
        val p = Phone(muted = true)
        val w = p.window()
        w.acquire(0)
        assertNull(w.release(5_000))
        assertNull(w.check(60_000))
        assertTrue(p.muted)
        assertTrue(p.changes.isEmpty())
    }

    @Test fun aMuteLeftByAKilledRunIsUndone() {
        // The app was killed while media was muted by it.
        val p = Phone(muted = true, saved = true)
        p.window().recover()
        assertFalse(p.muted)
        assertFalse(p.saved)
    }

    @Test fun aMuteLeftByAKilledRunIsUndoneAfterTheNextSessionToo() {
        val p = Phone(muted = true, saved = true)
        val w = p.window()
        w.acquire(0)
        assertTrue(w.ours)
        w.release(1_000)
        assertNull(w.check(9_000))
        assertFalse(p.muted)
    }

    @Test fun extraReleasesDoNothing() {
        val p = Phone()
        val w = p.window()
        w.acquire(0)
        w.release(1_000)
        w.release(1_000)
        assertEquals(0, w.holders)
        assertNull(w.check(9_000))
        assertFalse(p.muted)
    }
}
