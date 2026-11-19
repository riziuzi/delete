package com.riziuzi.utho.core

/**
 * When media must stay muted around the microphone, as one shared rule for the whole app.
 *
 * Every voice session holds the window open from before the mic starts until [holdMs] after it stops (a
 * speech service can play its sound late, after it has let go of the mic). Overlapping sessions (listen
 * again, a pop-up recreated, a second pop-up) share one window, so one session ending can never unmute
 * while another is still listening: that was how the closing beep got through before.
 *
 * Media the user had muted themselves is left muted. A mute of ours left behind by a killed process
 * is remembered ([remembered]) and undone next time ([recover]).
 */
class QuietWindow(
    private val holdMs: Long,
    private val isMuted: () -> Boolean,
    private val setMuted: (Boolean) -> Unit,
    private val remembered: () -> Boolean,
    private val remember: (Boolean) -> Unit,
) {
    var holders = 0
        private set
    /** Media is muted because of us (not because the user muted it). */
    var ours = false
        private set
    private var quietUntil = 0L

    /** A session starts: mute now (if not already). */
    fun acquire(now: Long) {
        holders++
        quietUntil = maxOf(quietUntil, now + holdMs)
        if (ours) return
        if (!isMuted()) {
            setMuted(true)
            ours = true
            remember(true)
        } else if (remembered()) {
            // Muted by an earlier run of ours that never got to unmute: still ours to undo later.
            ours = true
        }
    }

    /** A session ended. Returns when to call [check] next (or null if nothing is muted by us). */
    fun release(now: Long): Long? {
        if (holders > 0) holders--
        quietUntil = maxOf(quietUntil, now + holdMs)
        return if (ours) quietUntil else null
    }

    /** Unmutes once nobody holds the window and the hold time has passed. Returns the next check time, or null. */
    fun check(now: Long): Long? {
        if (!ours) return null
        if (holders > 0) return null // the session that ends will release (and schedule) again
        if (now < quietUntil) return quietUntil
        setMuted(false)
        ours = false
        remember(false)
        return null
    }

    /** App start, nothing listening: undo a mute a killed process of ours left behind. */
    fun recover() {
        if (holders == 0 && !ours && remembered()) {
            if (isMuted()) setMuted(false)
            remember(false)
        }
    }
}
