package com.riziuzi.utho.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.riziuzi.utho.core.t
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.ui.WorkoutActivity

/** Rings and vibrates until stopped. WorkoutActivity stops it once the exercise target is reached. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.createChannels(this)
        val lang = Prefs(this).lang
        val ring = WorkoutActivity.alarmIntent(this)
        val n = Notifications.fullScreen(
            this, Notifications.ID_ALARM, Notifications.CH_ALARM,
            lang.t("Wake up!", "Utho!"),
            lang.t("Do your exercise to stop the alarm", "Alarm band karne ke liye exercise karein"),
            ring, NotificationCompat.CATEGORY_ALARM, ongoing = true,
        )
        ServiceCompat.startForeground(this, Notifications.ID_ALARM, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (player == null) startRinging()
        return START_STICKY
    }

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            // Sound failed; vibration still runs.
        }
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Vibrator::class.java)?.also {
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
    }

    override fun onDestroy() {
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        vibrator?.cancel()
        super.onDestroy()
    }

    companion object {
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AlarmService::class.java))
    }
}

/** Short beep for each counted rep, on the alarm stream. [volumePercent] 0 = off. */
class Beeper(volumePercent: Int) {
    private val tone: ToneGenerator? =
        if (volumePercent <= 0) null
        else runCatching { ToneGenerator(AudioManager.STREAM_ALARM, volumePercent.coerceIn(1, 100)) }.getOrNull()

    fun beep(ms: Int = 150) {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, ms)
    }

    fun chime() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
    }

    fun release() {
        tone?.release()
    }
}
