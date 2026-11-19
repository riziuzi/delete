package com.riziuzi.utho.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.riziuzi.utho.R
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.core.t

/**
 * The two soft sounds of a voice note: one when the mic actually starts listening, one when it stops.
 * Made by tools/sounds.py. Played as an alarm-type sound so they still play while media is muted
 * (see [AudioGuard]); kept at a gentle level. These are the only sounds of a voice note.
 */
object Earcons {
    const val DEFAULT = "bowl"
    val options = listOf("bowl", "chime", "drop", "pad", "off")
    private const val VOLUME = 0.45f

    fun label(key: String, lang: Lang): String = when (key) {
        "bowl" -> lang.t("Singing bowl", "Singing bowl")
        "chime" -> lang.t("Soft chime", "Soft chime")
        "drop" -> lang.t("Water drop", "Water drop")
        "pad" -> lang.t("Warm pad", "Warm pad")
        else -> lang.t("Off", "Off")
    }

    private fun res(key: String, open: Boolean): Int? = when (key) {
        "bowl" -> if (open) R.raw.snd_bowl_open else R.raw.snd_bowl_close
        "chime" -> if (open) R.raw.snd_chime_open else R.raw.snd_chime_close
        "drop" -> if (open) R.raw.snd_drop_open else R.raw.snd_drop_close
        "pad" -> if (open) R.raw.snd_pad_open else R.raw.snd_pad_close
        else -> null
    }

    /** Plays the open or close sound of [key]; [then] runs when it has finished (or right away if silent). */
    fun play(ctx: Context, key: String, open: Boolean, then: (() -> Unit)? = null) {
        val id = res(key, open)
        if (id == null) {
            then?.invoke()
            return
        }
        VoiceLog.add(if (open) "earcon:open" else "earcon:close")
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val am = ctx.getSystemService(AudioManager::class.java)
        val mp = runCatching { MediaPlayer.create(ctx.applicationContext, id, attrs, am.generateAudioSessionId()) }.getOrNull()
        if (mp == null) {
            then?.invoke()
            return
        }
        mp.setVolume(VOLUME, VOLUME)
        mp.setOnCompletionListener {
            it.release()
            then?.invoke()
        }
        runCatching { mp.start() }.onFailure {
            mp.release()
            then?.invoke()
        }
    }

    /** Settings preview: open, then close. */
    fun preview(ctx: Context, key: String) = play(ctx, key, true) { play(ctx, key, false) }
}
