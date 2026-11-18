package com.example.pushupalarm

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Short beep for each counted rep. Plays on the alarm stream so it is audible even when media
 * volume is muted. [volumePercent] comes from the in-app slider (0 = off) and is relative to
 * the phone's alarm volume.
 */
class RepBeeper(volumePercent: Int) {
    private val tone: ToneGenerator? =
        if (volumePercent <= 0) null
        else runCatching { ToneGenerator(AudioManager.STREAM_ALARM, volumePercent.coerceIn(1, 100)) }.getOrNull()

    fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    fun release() {
        tone?.release()
    }
}
