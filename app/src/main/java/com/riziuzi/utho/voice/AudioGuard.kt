package com.riziuzi.utho.voice

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.riziuzi.utho.core.QuietWindow

/**
 * Keeps media muted while any voice session is running and for [HOLD_MS] after the last one ends, so no
 * speech-service sound can be heard (see [QuietWindow]). Every [acquire] gives a [Token]; releasing it twice is
 * harmless. Only used on the main thread.
 */
object AudioGuard {
    /** Long enough for a late closing sound of a speech service. */
    const val HOLD_MS = 8000L

    private val main = Handler(Looper.getMainLooper())
    private var window: QuietWindow? = null
    private val check = Runnable { runCheck() }

    class Token internal constructor() {
        internal var released = false
    }

    private fun window(ctx: Context): QuietWindow = window ?: run {
        val app = ctx.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        val prefs = app.getSharedPreferences("audio_guard", Context.MODE_PRIVATE)
        QuietWindow(
            HOLD_MS,
            isMuted = { runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false) },
            setMuted = { on ->
                runCatching {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                }
                VoiceLog.add(if (on) "guard:mute" else "guard:unmute")
            },
            remembered = { prefs.getBoolean("muted", false) },
            remember = { prefs.edit().putBoolean("muted", it).commit() },
        ).also { window = it }
    }

    private fun now() = SystemClock.elapsedRealtime()

    fun acquire(ctx: Context): Token {
        main.removeCallbacks(check)
        window(ctx).acquire(now())
        VoiceLog.add("guard:hold")
        return Token()
    }

    fun release(ctx: Context, token: Token?) {
        if (token == null || token.released) return
        token.released = true
        VoiceLog.add("guard:release")
        val next = window(ctx).release(now()) ?: return
        schedule(next)
    }

    private fun schedule(at: Long) {
        main.removeCallbacks(check)
        main.postDelayed(check, (at - now()).coerceAtLeast(0) + 50)
    }

    private fun runCheck() {
        val w = window ?: return
        w.check(now())?.let { schedule(it) }
    }

    /** App start: undo a mute a killed run left behind. */
    fun recover(ctx: Context) {
        val w = window(ctx)
        if (w.holders == 0 && !w.ours) w.recover()
    }

    /** Tests: is media muted by us right now / is anyone holding the window. */
    val mutedByUs: Boolean get() = window?.ours == true
    val holders: Int get() = window?.holders ?: 0
}

/**
 * What the voice parts did, in order, for tests (and a short log when something goes wrong): mic opened and
 * closed, which engine ran, sounds played, media muted and unmuted.
 */
object VoiceLog {
    data class Event(val atMs: Long, val what: String)

    private val events = mutableListOf<Event>()

    @Synchronized fun add(what: String) {
        events += Event(SystemClock.elapsedRealtime(), what)
        if (events.size > 400) events.removeAt(0)
    }

    @Synchronized fun all(): List<Event> = events.toList()

    @Synchronized fun clear() = events.clear()
}
