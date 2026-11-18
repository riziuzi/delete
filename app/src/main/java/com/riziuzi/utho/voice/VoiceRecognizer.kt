package com.riziuzi.utho.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.riziuzi.utho.data.VoiceLang

/**
 * Continuous speech-to-text using the phone's own recogniser, on-device first.
 * Keeps listening across pauses until [stop]; each finished phrase arrives as one segment.
 */
class VoiceRecognizer(
    private val context: Context,
    private val lang: VoiceLang,
    private val listener: Listener,
) {
    enum class Engine { ON_DEVICE, SYSTEM, NONE }

    enum class Problem { NO_RECOGNIZER, NO_PERMISSION, LANGUAGE_MISSING, NETWORK_NEEDED, OTHER }

    interface Listener {
        fun onReady(engine: Engine) {}
        /** The microphone is open and the user can start talking. */
        fun onListening() {}
        fun onPartial(text: String) {}
        fun onSegment(text: String) {}
        fun onLevel(level: Float) {}
        fun onProblem(problem: Problem, code: Int) {}
        /** Called once after [stop], when the last results are in. */
        fun onFinished() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var finished = false
    private var triedSystem = false
    private var told = false

    /**
     * Keep one listening session open this long (until [stop]) instead of ending it at each pause. Every new
     * session makes the phone's speech service play its mic beep, so fewer sessions means fewer beeps.
     * Uses Android 13+'s segmented sessions (text comes back at each pause while the mic stays on) and Google's
     * dictation mode; if the phone's recogniser ignores both, it still restarts after each phrase as before.
     */
    var sessionMs: Long = 0

    // If the on-device model can't do this language, remember it and go straight to the regular
    // recogniser next time instead of failing first (that round trip was the delay before listening).
    private val memo = context.getSharedPreferences(MEMO, Context.MODE_PRIVATE)
    private val memoKey = "skipOnDevice_" + primaryTag(lang)

    var engine = Engine.NONE
        private set

    fun start() {
        active = true
        finished = false
        told = false
        if (!create(onDevice = !triedSystem && !memo.getBoolean(memoKey, false))) return
        listen()
    }

    fun stop() {
        if (!active) return
        active = false
        val r = recognizer
        if (r == null) {
            finish()
            return
        }
        runCatching { r.stopListening() }
        // If the recogniser has nothing pending it may never call back.
        main.postDelayed({ finish() }, 2500)
    }

    /**
     * Stop now and keep what was already heard (the caller has the partial text). Skips the recogniser's
     * "stop" step, which on many phones plays a second beep.
     */
    fun cancel() {
        if (!active) {
            finish()
            return
        }
        active = false
        main.removeCallbacksAndMessages(null)
        // Release the speech service right away (it beeps when it lets go of the mic), while media is still muted.
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        finish()
    }

    fun destroy() {
        active = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun finish() {
        if (finished) return
        finished = true
        listener.onFinished()
    }

    private fun create(onDevice: Boolean): Boolean {
        runCatching { recognizer?.destroy() }
        recognizer = null
        val r: SpeechRecognizer? = try {
            when {
                onDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> {
                    engine = Engine.ON_DEVICE
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }
                SpeechRecognizer.isRecognitionAvailable(context) -> {
                    engine = Engine.SYSTEM
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (r == null) {
            engine = Engine.NONE
            active = false
            listener.onProblem(Problem.NO_RECOGNIZER, -1)
            finish()
            return false
        }
        r.setRecognitionListener(callbacks)
        recognizer = r
        listener.onReady(engine)
        return true
    }

    private fun listen() {
        if (!active) return
        try {
            recognizer?.startListening(intent(context, lang, sessionMs))
        } catch (_: Exception) {
            main.postDelayed({ listen() }, 400)
        }
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!told) {
                told = true
                listener.onListening()
            }
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) listener.onPartial(t)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val t = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) listener.onSegment(t)
        }

        override fun onEndOfSegmentedSession() {
            if (active) main.postDelayed({ listen() }, 120) else finish()
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) listener.onSegment(t)
            if (active) main.postDelayed({ listen() }, 120) else finish()
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (active) main.postDelayed({ listen() }, 150) else finish()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    if (active) main.postDelayed({ listen() }, 500) else finish()
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    if (active) main.postDelayed({ listen() }, 300) else finish()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(Problem.NO_PERMISSION, error)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER, 11 /* ERROR_SERVER_DISCONNECTED */ -> {
                    if (engine == Engine.ON_DEVICE && !triedSystem) fallBack() else fail(Problem.NETWORK_NEEDED, error)
                }
                12 /* ERROR_LANGUAGE_NOT_SUPPORTED */, 13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> {
                    if (engine == Engine.ON_DEVICE && !triedSystem) fallBack() else fail(Problem.LANGUAGE_MISSING, error)
                }
                else -> fail(Problem.OTHER, error)
            }
        }
    }

    /** The on-device model can't handle this language yet: use the phone's regular recogniser instead. */
    private fun fallBack() {
        triedSystem = true
        memo.edit().putBoolean(memoKey, true).apply()
        val wasActive = active
        if (create(onDevice = false) && wasActive) {
            active = true
            listen()
        }
    }

    private fun fail(p: Problem, code: Int) {
        active = false
        listener.onProblem(p, code)
        finish()
    }

    companion object {
        private const val MEMO = "voice"

        fun primaryTag(lang: VoiceLang) = if (lang == VoiceLang.ENGLISH) "en-IN" else "hi-IN"

        fun intent(context: Context, lang: VoiceLang, sessionMs: Long = 0): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryTag(lang))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Wait longer before closing a phrase, so a short pause doesn't cut the sentence.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
            if (sessionMs > 0) {
                // One long session: the mic stays on through pauses (Google's recogniser honours dictation mode;
                // Android 13+ recognisers that support segments return text at each pause).
                putExtra("android.speech.extra.DICTATION_MODE", true)
                if (Build.VERSION.SDK_INT >= 33) {
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, sessionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
            }
            if (Build.VERSION.SDK_INT >= 34 && lang == VoiceLang.MIX) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_HIGH_PRECISION)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf("hi-IN", "en-IN"))
            }
        }

        fun onDeviceAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

        fun anyAvailable(context: Context): Boolean =
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) || onDeviceAvailable(context)

        data class Support(val installed: List<String>, val pending: List<String>, val supported: List<String>, val online: List<String>)

        /** Which languages the on-device recogniser has installed (Android 13+). */
        @RequiresApi(33)
        fun checkSupport(context: Context, lang: VoiceLang, result: (Support?) -> Unit) {
            val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            if (r == null) {
                result(null)
                return
            }
            r.checkRecognitionSupport(
                intent(context, lang), ContextCompat.getMainExecutor(context),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) {
                        result(Support(s.installedOnDeviceLanguages, s.pendingOnDeviceLanguages, s.supportedOnDeviceLanguages, s.onlineLanguages))
                        r.destroy()
                    }

                    override fun onError(error: Int) {
                        result(null)
                        r.destroy()
                    }
                },
            )
        }

        /** Asks Android to download the on-device model for this language (Android 13+). */
        @RequiresApi(33)
        enum class Pack { INSTALLED, DOWNLOADING, NOT_INSTALLED, UNAVAILABLE }

        /** A voice pack is on the phone now: use the offline recogniser again (it was skipped after failing). */
        fun retryOnDevice(context: Context) = context.getSharedPreferences(MEMO, Context.MODE_PRIVATE).edit().clear().apply()

        private fun packIntent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)

        private fun has(list: List<String>, tag: String) = list.any { it.equals(tag, true) || it.substringBefore('-').equals(tag.substringBefore('-'), true) }

        /** Is the offline voice pack for [tag] ("hi-IN") on the phone, downloading, or available to download? */
        @RequiresApi(33)
        fun packStatus(context: Context, tag: String, result: (Pack?) -> Unit) {
            val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            if (r == null) {
                result(null)
                return
            }
            runCatching {
                r.checkRecognitionSupport(
                    packIntent(tag), ContextCompat.getMainExecutor(context),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(s: RecognitionSupport) {
                            result(
                                when {
                                    has(s.installedOnDeviceLanguages, tag) -> Pack.INSTALLED
                                    has(s.pendingOnDeviceLanguages, tag) -> Pack.DOWNLOADING
                                    has(s.supportedOnDeviceLanguages, tag) -> Pack.NOT_INSTALLED
                                    else -> Pack.UNAVAILABLE
                                },
                            )
                            r.destroy()
                        }

                        override fun onError(error: Int) {
                            result(null)
                            r.destroy()
                        }
                    },
                )
            }.onFailure {
                result(null)
                r.destroy()
            }
        }

        /** What a pack download reports back (Android 14+ gives a percentage; 13 only starts it). */
        interface DownloadListener {
            fun onProgress(percent: Int) {}
            fun onScheduled() {}
            fun onDone() {}
            fun onFailed() {}
        }

        /** Starts downloading the offline voice pack for [tag]. Android has no way for an app to pause or cancel it. */
        @RequiresApi(33)
        fun downloadPack(context: Context, tag: String, listener: DownloadListener) {
            // Try on-device again once a pack is being installed.
            context.getSharedPreferences(MEMO, Context.MODE_PRIVATE).edit().clear().apply()
            val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            if (r == null) {
                listener.onFailed()
                return
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val ok = runCatching {
                    r.triggerModelDownload(
                        packIntent(tag), ContextCompat.getMainExecutor(context),
                        object : android.speech.ModelDownloadListener {
                            override fun onProgress(completedPercent: Int) = listener.onProgress(completedPercent)
                            override fun onSuccess() {
                                listener.onDone()
                                r.destroy()
                            }
                            override fun onScheduled() {
                                listener.onScheduled()
                                r.destroy()
                            }
                            override fun onError(error: Int) {
                                listener.onFailed()
                                r.destroy()
                            }
                        },
                    )
                }.isSuccess
                if (!ok) {
                    r.destroy()
                    listener.onFailed()
                }
            } else {
                val ok = runCatching { r.triggerModelDownload(packIntent(tag)) }.isSuccess
                Handler(Looper.getMainLooper()).postDelayed({ r.destroy() }, 3000)
                if (!ok) listener.onFailed() // otherwise the caller watches packStatus until it's installed
            }
        }
    }
}
