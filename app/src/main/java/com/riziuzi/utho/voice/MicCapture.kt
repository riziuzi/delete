package com.riziuzi.utho.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.io.OutputStream
import kotlin.math.sqrt

/**
 * Utho's own microphone: 16 kHz mono 16-bit PCM written into a pipe that a speech engine reads.
 *
 * Because Utho opens the mic itself, the phone's speech service never does, so it has no mic to beep
 * about: this is what keeps voice notes free of Google's sounds. [newPipe] hands a fresh pipe to an engine
 * that has to restart; the old one gets end-of-file.
 */
class MicCapture(private val onLevel: (Float) -> Unit = {}) {
    companion object {
        const val RATE = 16_000
        private const val CHUNK = RATE / 10 // 100 ms of samples

        /**
         * Tests (debug builds): audio to send instead of the microphone's, in real time, then silence. The
         * microphone is still opened and closed as usual unless [testNoMic].
         */
        @Volatile var testPcm: ByteArray? = null

        /** Tests: an emulator without a microphone; silence (or [testPcm]) is sent at real-time pace instead. */
        @Volatile var testNoMic = false
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var out: OutputStream? = null
    private var writeEnd: ParcelFileDescriptor? = null

    /** The audio session of our recording, to tell it apart from anyone else's. */
    var sessionId: Int = 0
        private set

    val isRunning: Boolean get() = running

    /** Opens the microphone and returns the read end of the pipe, or null if the mic can't be opened. */
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
    fun start(): ParcelFileDescriptor? {
        if (running) return newPipe()
        val fake = testPcm
        val noMic = testNoMic
        val r: AudioRecord? = if (noMic) null else try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 2 * 4),
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Exception) {
            null
        }
        if (r == null && !noMic) {
            VoiceLog.add("mic:failed")
            return null
        }
        if (r != null) {
            try {
                r.startRecording()
            } catch (_: Exception) {
                r.release()
                VoiceLog.add("mic:failed")
                return null
            }
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                r.release()
                VoiceLog.add("mic:failed")
                return null
            }
            sessionId = r.audioSessionId
        }
        record = r
        running = true
        val read = newPipe()
        VoiceLog.add(if (r != null) "mic:open" else "mic:open:test")
        thread = Thread({ pump(r, fake) }, "utho-mic").apply {
            isDaemon = true
            start()
        }
        return read
    }

    /** A new pipe for the same recording (the engine restarted); the old pipe ends. */
    @Synchronized fun newPipe(): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
        val old = out
        val oldEnd = writeEnd
        writeEnd = write
        out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        runCatching { old?.close() }
        runCatching { oldEnd?.close() }
        return read
    }

    private fun pump(r: AudioRecord?, fake: ByteArray?) {
        val samples = ShortArray(CHUNK)
        val bytes = ByteArray(CHUNK * 2)
        var fakeAt = 0
        var next = System.nanoTime()
        while (running) {
            val n: Int
            if (r != null) {
                n = r.read(samples, 0, CHUNK)
                if (n <= 0) {
                    if (!running) break
                    Thread.sleep(10)
                    continue
                }
            } else {
                // No mic in this test: keep real-time pace by hand.
                next += 100_000_000L
                val wait = (next - System.nanoTime()) / 1_000_000L
                if (wait > 0) Thread.sleep(wait)
                n = CHUNK
                java.util.Arrays.fill(samples, 0)
            }
            var sum = 0.0
            for (i in 0 until n) {
                var s = samples[i]
                if (fake != null) {
                    // Replace the mic's samples with the test audio while it lasts, then silence.
                    s = if (fakeAt + 1 < fake.size) {
                        val v = (fake[fakeAt].toInt() and 0xff) or (fake[fakeAt + 1].toInt() shl 8)
                        fakeAt += 2
                        v.toShort()
                    } else 0
                }
                bytes[2 * i] = (s.toInt() and 0xff).toByte()
                bytes[2 * i + 1] = (s.toInt() shr 8).toByte()
                sum += s.toDouble() * s
            }
            val rms = sqrt(sum / n) / 32768.0
            onLevel((rms * 8).toFloat().coerceIn(0f, 1f))
            val o = synchronized(this) { out }
            try {
                o?.write(bytes, 0, n * 2)
            } catch (_: IOException) {
                // The engine stopped reading; keep the mic loop going until stop() (a new pipe may come).
            }
        }
    }

    /** Closes the microphone and ends the pipe (end-of-file for the engine). Safe to call more than once. */
    fun stop() {
        if (!running && record == null && out == null) return
        running = false
        val r = record
        record = null
        runCatching { r?.stop() }
        thread?.let { runCatching { it.join(1500) } }
        thread = null
        runCatching { r?.release() }
        synchronized(this) {
            runCatching { out?.close() }
            runCatching { writeEnd?.close() }
            out = null
            writeEnd = null
        }
        VoiceLog.add("mic:close")
    }
}
