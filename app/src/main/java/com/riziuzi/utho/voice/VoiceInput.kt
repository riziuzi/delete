package com.riziuzi.utho.voice

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.VoiceLang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer as MlRecognizer

/** Which speech engine turned voice into text. Only [GOOGLE_MIC] lets the phone's speech service open the mic. */
enum class VoiceEngineKind { MLKIT, PLATFORM_SOURCE, GOOGLE_MIC, FAKE, NONE }

/** Tests (debug builds only): a pretend speech engine that "hears" [script] while really reading Utho's mic. */
object FakeVoice {
    /** (ms after listening starts, text heard). Null: the real engines are used. */
    @Volatile var script: List<Pair<Long, String>>? = null

    /** Tests: act as if the phone had no silent engine (to check what happens then). */
    @Volatile var noSilentEngines = false
}

/**
 * Voice to text without the phone's speech-service sounds.
 *
 * Utho opens the microphone itself ([MicCapture]) and gives the audio to an on-device engine, so Google's
 * speech service never touches the mic and never plays its start or stop sound:
 *  1. ML Kit speech recognition (on-device, Android 12+), then
 *  2. Android's on-device recogniser fed with our audio (Android 13+).
 * Only if neither is on the phone, and only if the user allowed it in Settings, the phone's regular
 * recogniser is used ([VoiceEngineKind.GOOGLE_MIC]); that one opens the mic itself and plays its sounds,
 * which [AudioGuard] mutes as well as it can.
 *
 * Media stays muted from [start] until [AudioGuard.HOLD_MS] after the end, whatever the engine.
 * Same calls as [VoiceRecognizer]: start, stop (keep the last words), cancel (stop now), destroy.
 */
class VoiceInput(
    private val context: Context,
    private val lang: VoiceLang,
    private val listener: VoiceRecognizer.Listener,
) {
    var sessionMs: Long = 0
    var engine = VoiceEngineKind.NONE
        private set

    private val main = Handler(Looper.getMainLooper())
    private val scope: CoroutineScope = MainScope()
    private var token: AudioGuard.Token? = null
    private var capture: MicCapture? = null
    private var running: Running? = null
    private var pickJob: Job? = null
    private var active = false
    private var finished = true
    private var told = false
    private var heardAny = false
    private val endTimeout = Runnable { end() }

    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** One engine run; it reports back through these. */
    private abstract inner class Running(val kind: VoiceEngineKind) {
        var over = false
        abstract fun begin(): Boolean
        /** Finish: the mic is already closed; deliver the last text, then [ended]. */
        abstract fun finishUp()
        /** Drop it now, no more callbacks. */
        abstract fun abort()

        fun ready() {
            main.post { if (!over && running === this) listening() }
        }
        fun partial(t: String) {
            main.post { if (!over && running === this && t.isNotBlank()) { heardAny = true; listener.onPartial(t) } }
        }
        fun segment(t: String) {
            main.post { if (!over && running === this && t.isNotBlank()) { heardAny = true; listener.onSegment(t) } }
        }
        fun failed(p: VoiceRecognizer.Problem, code: Int) {
            main.post { if (!over && running === this) engineFailed(this, p, code) }
        }
        fun ended() {
            main.post { if (!over && running === this) end() }
        }
    }

    fun start() {
        if (active) return
        active = true
        finished = false
        told = false
        heardAny = false
        token = AudioGuard.acquire(context)
        VoiceLog.add("voice:start")
        pickJob = scope.launch { pick(SilentVoice.order(context, lang, debuggable)) }
    }

    private suspend fun pick(candidates: List<VoiceEngineKind>) {
        for (k in candidates) {
            if (!active) return
            if (!SilentVoice.usable(context, lang, k, debuggable)) continue
            if (begin(k)) return
        }
        if (!active) return
        // Nothing silent on this phone (and Google voice not allowed): say so, type instead.
        active = false
        listener.onProblem(VoiceRecognizer.Problem.SILENT_MISSING, -1)
        end()
    }

    private fun begin(k: VoiceEngineKind): Boolean {
        val r: Running = when (k) {
            VoiceEngineKind.FAKE -> FakeRun()
            VoiceEngineKind.MLKIT -> if (Build.VERSION.SDK_INT >= 31) MlKitRun() else return false
            VoiceEngineKind.PLATFORM_SOURCE -> if (Build.VERSION.SDK_INT >= 33) SourceRun() else return false
            VoiceEngineKind.GOOGLE_MIC -> MicRun()
            VoiceEngineKind.NONE -> return false
        }
        running = r
        engine = k
        VoiceLog.add("engine:${k.name}")
        listener.onReady(if (k == VoiceEngineKind.GOOGLE_MIC) VoiceRecognizer.Engine.SYSTEM else VoiceRecognizer.Engine.ON_DEVICE)
        val ok = runCatching { r.begin() }.getOrDefault(false)
        if (!ok) {
            r.over = true
            running = null
            stopMic()
            VoiceLog.add("engine:failed:${k.name}")
        }
        return ok
    }

    /** Opens our mic once per session; engines that restart get a new pipe on the same recording. */
    private fun openMic(): ParcelFileDescriptor? {
        val c = capture ?: MicCapture { level -> main.post { if (active) listener.onLevel(level) } }.also { capture = it }
        return c.start()
    }

    private fun stopMic() {
        capture?.stop()
        capture = null
    }

    private fun listening() {
        if (told) return
        told = true
        listener.onListening()
    }

    private fun engineFailed(r: Running, p: VoiceRecognizer.Problem, code: Int) {
        VoiceLog.add("engine:failed:${r.kind.name}:$p")
        r.over = true
        r.abort()
        running = null
        if (r.kind == VoiceEngineKind.PLATFORM_SOURCE && p == VoiceRecognizer.Problem.OTHER && code == SilentVoice.SOURCE_IGNORED) {
            SilentVoice.markSourceIgnored(context)
        }
        if (r.kind == VoiceEngineKind.MLKIT) SilentVoice.markMlKitFailed()
        if (active && !heardAny) {
            // Nothing heard yet: quietly try the next engine.
            stopMic()
            val rest = SilentVoice.order(context, lang, debuggable).dropWhile { it != r.kind }.drop(1)
            pickJob = scope.launch { pick(rest) }
            return
        }
        active = false
        stopMic()
        listener.onProblem(p, code)
        end()
    }

    /** Stop listening and keep everything heard (the last words may take a moment). */
    fun stop() {
        if (!active) {
            if (!finished) end()
            return
        }
        active = false
        pickJob?.cancel()
        stopMic()
        val r = running
        if (r == null) {
            end()
            return
        }
        runCatching { r.finishUp() }
        main.postDelayed(endTimeout, if (r.kind == VoiceEngineKind.GOOGLE_MIC) 2500 else 2000)
    }

    /**
     * Stop now. The silent engines still get a short moment to finish the last words (no sound either way);
     * Google voice is cut at once (its "stop" step plays an extra sound).
     */
    fun cancel() {
        if (!active) {
            if (!finished) end()
            return
        }
        if (running?.kind == VoiceEngineKind.GOOGLE_MIC) {
            active = false
            pickJob?.cancel()
            running?.let { it.over = true; it.abort() }
            running = null
            stopMic()
            end()
        } else {
            stop()
        }
    }

    /** Gone (screen closed): no more callbacks. */
    fun destroy() {
        if (!finished) VoiceLog.add("voice:destroy")
        active = false
        pickJob?.cancel()
        running?.let { it.over = true; runCatching { it.abort() } }
        running = null
        stopMic()
        main.removeCallbacks(endTimeout)
        finished = true
        AudioGuard.release(context, token)
        token = null
    }

    private fun end() {
        main.removeCallbacks(endTimeout)
        running?.let { it.over = true; runCatching { it.abort() } }
        running = null
        stopMic()
        if (finished) return
        finished = true
        VoiceLog.add("voice:end")
        AudioGuard.release(context, token)
        token = null
        listener.onFinished()
    }

    // ----- engines -----

    /** ML Kit on-device speech recognition reading our mic's pipe. */
    @RequiresApi(31)
    private inner class MlKitRun : Running(VoiceEngineKind.MLKIT) {
        private var client: MlRecognizer? = null
        private var source: AudioSource? = null
        private var job: Job? = null
        private val finals = mutableListOf<String>()
        private var stopping = false

        override fun begin(): Boolean {
            val read = openMic() ?: return false
            val c = SpeechRecognition.getClient(SilentVoice.mlOptions(lang))
            client = c
            val src = AudioSource.fromPfd(read, AudioSource.Mode.STREAMING)
            source = src
            job = scope.launch {
                try {
                    ready()
                    c.startRecognition(speechRecognizerRequest { audioSource = src }).collect { r ->
                        when (r) {
                            is SpeechRecognizerResponse.PartialTextResponse -> partial(strip(r.text))
                            is SpeechRecognizerResponse.FinalTextResponse -> {
                                val t = strip(r.text)
                                if (t.isNotBlank()) {
                                    finals += t
                                    segment(t)
                                }
                            }
                            is SpeechRecognizerResponse.ErrorResponse -> if (!stopping) {
                                failed(SilentVoice.problemFor(r.e.errorCode), r.e.errorCode)
                            }
                            else -> {}
                        }
                    }
                    ended()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (stopping) ended() else failed(VoiceRecognizer.Problem.OTHER, -2)
                } finally {
                    runCatching { source?.close() }
                    runCatching { client?.close() }
                }
            }
            return true
        }

        /** Some engines repeat earlier final text at the start of the next one: keep only what's new. */
        private fun strip(t: String): String {
            val before = finals.joinToString(" ")
            val s = t.trim()
            return if (before.isNotEmpty() && s.startsWith(before)) s.substring(before.length).trim() else s
        }

        override fun finishUp() {
            stopping = true
            // The mic is closed, so the pipe ends; ask ML Kit to wrap up as well.
            scope.launch { runCatching { withTimeoutOrNull(1800) { client?.stopRecognition() } } }
        }

        override fun abort() {
            stopping = true
            job?.cancel()
            runCatching { source?.close() }
            runCatching { client?.close() }
        }
    }

    /** Android's on-device recogniser (13+) fed with our mic's audio. */
    @RequiresApi(33)
    private inner class SourceRun : Running(VoiceEngineKind.PLATFORM_SOURCE) {
        private var rec: VoiceRecognizer? = null

        override fun begin(): Boolean {
            // Each listening round of the recogniser gets a fresh pipe on the same recording.
            var first: ParcelFileDescriptor? = openMic() ?: return false
            val r = VoiceRecognizer(context, lang, object : VoiceRecognizer.Listener {
                override fun onListening() {
                    ready()
                    // If the recogniser opened the mic anyway (ignored our audio), two recordings are running:
                    // stop it at once (it's muted) and never use this engine again.
                    main.postDelayed({
                        if (!over && othersRecording()) failed(VoiceRecognizer.Problem.OTHER, SilentVoice.SOURCE_IGNORED)
                    }, 500)
                }
                override fun onPartial(text: String) = partial(text)
                override fun onSegment(text: String) = segment(text)
                override fun onProblem(problem: VoiceRecognizer.Problem, code: Int) = failed(problem, code)
                override fun onFinished() = ended()
            }, audio = { first?.also { first = null } ?: capture?.newPipe() })
            rec = r
            r.sessionMs = sessionMs
            r.start()
            return true
        }

        private fun othersRecording(): Boolean = runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            am.activeRecordingConfigurations.size >= 2
        }.getOrDefault(false)

        override fun finishUp() {
            rec?.stop()
        }

        override fun abort() {
            rec?.destroy()
        }
    }

    /** The phone's regular recogniser with its own mic (plays its sounds; only if the user allowed it). */
    private inner class MicRun : Running(VoiceEngineKind.GOOGLE_MIC) {
        private var rec: VoiceRecognizer? = null

        override fun begin(): Boolean {
            val r = VoiceRecognizer(context, lang, object : VoiceRecognizer.Listener {
                override fun onListening() = ready()
                override fun onPartial(text: String) = partial(text)
                override fun onSegment(text: String) = segment(text)
                override fun onLevel(level: Float) {
                    main.post { if (active) listener.onLevel(level) }
                }
                override fun onProblem(problem: VoiceRecognizer.Problem, code: Int) = failed(problem, code)
                override fun onFinished() = ended()
            })
            rec = r
            r.sessionMs = sessionMs
            r.start()
            return true
        }

        override fun finishUp() {
            rec?.stop()
        }

        override fun abort() {
            rec?.cancel()
            rec?.destroy()
        }
    }

    /** Tests: reads our real mic pipe to the end and "hears" the script. */
    private inner class FakeRun : Running(VoiceEngineKind.FAKE) {
        private var reader: Thread? = null
        @Volatile private var bytes = 0L

        override fun begin(): Boolean {
            // An emulator may have no working microphone: then the test runs on generated silence.
            val read = openMic() ?: run {
                MicCapture.testNoMic = true
                openMic()
            } ?: return false
            reader = Thread({
                val buf = ByteArray(4096)
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(read).use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            bytes += n
                        }
                    }
                }
                VoiceLog.add("fake:read=$bytes")
                ended()
            }, "utho-fake-voice").apply {
                isDaemon = true
                start()
            }
            ready()
            for ((at, text) in FakeVoice.script.orEmpty()) {
                main.postDelayed({ if (!over && active) partial(text) }, (at - 400).coerceAtLeast(0))
                main.postDelayed({ if (!over && active) segment(text) }, at)
            }
            return true
        }

        override fun finishUp() {} // the mic closing ends the pipe; the reader then calls ended()

        override fun abort() {}
    }
}

/**
 * Which silent engines this phone has, and the one-time ML Kit model download (Settings).
 */
object SilentVoice {
    const val SOURCE_IGNORED = -77

    private const val MEMO = "voice_engines"
    @Volatile private var mlKitFailed = false

    fun locale(lang: VoiceLang): Locale = if (lang == VoiceLang.ENGLISH) Locale.US else Locale.forLanguageTag("hi-IN")

    fun mlOptions(lang: VoiceLang) = speechRecognizerOptions {
        locale = locale(lang)
        preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
    }

    fun problemFor(code: Int): VoiceRecognizer.Problem = VoiceRecognizer.Problem.OTHER

    fun markMlKitFailed() {
        mlKitFailed = true
    }

    fun markSourceIgnored(ctx: Context) {
        ctx.getSharedPreferences(MEMO, Context.MODE_PRIVATE).edit().putBoolean("sourceIgnored", true).apply()
    }

    private fun sourceIgnored(ctx: Context) = ctx.getSharedPreferences(MEMO, Context.MODE_PRIVATE).getBoolean("sourceIgnored", false)

    /** Engines to try, best first. Google voice comes last and only if the user allowed it. */
    fun order(ctx: Context, lang: VoiceLang, debuggable: Boolean): List<VoiceEngineKind> = buildList {
        if (debuggable && FakeVoice.script != null) add(VoiceEngineKind.FAKE)
        add(VoiceEngineKind.MLKIT)
        add(VoiceEngineKind.PLATFORM_SOURCE)
        if (Prefs(ctx).googleVoice) add(VoiceEngineKind.GOOGLE_MIC)
    }

    /** Can [k] be tried right now? (ML Kit: its model is on the phone.) */
    suspend fun usable(ctx: Context, lang: VoiceLang, k: VoiceEngineKind, debuggable: Boolean): Boolean {
        val none = debuggable && FakeVoice.noSilentEngines
        return when (k) {
            VoiceEngineKind.FAKE -> debuggable && FakeVoice.script != null
            VoiceEngineKind.MLKIT -> !none && !mlKitFailed && mlKitStatus(lang) == FeatureStatus.AVAILABLE
            VoiceEngineKind.PLATFORM_SOURCE -> !none && Build.VERSION.SDK_INT >= 33 && !sourceIgnored(ctx) && VoiceRecognizer.onDeviceAvailable(ctx)
            VoiceEngineKind.GOOGLE_MIC -> VoiceRecognizer.anyAvailable(ctx)
            VoiceEngineKind.NONE -> false
        }
    }

    /** ML Kit's state for this language: AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE (null: can't tell). */
    suspend fun mlKitStatus(lang: VoiceLang): Int? {
        if (Build.VERSION.SDK_INT < 31) return FeatureStatus.UNAVAILABLE
        return runCatching {
            withTimeoutOrNull(3000) {
                val c = SpeechRecognition.getClient(mlOptions(lang))
                try {
                    c.checkStatus()
                } finally {
                    runCatching { c.close() }
                }
            }
        }.getOrNull()
    }

    /** Downloads ML Kit's model for [lang] (once). [progress] gets 0..100, or null while the size isn't known. */
    fun download(lang: VoiceLang, scope: CoroutineScope, progress: (Int?) -> Unit, done: (Boolean) -> Unit): Job = scope.launch {
        if (Build.VERSION.SDK_INT < 31) {
            done(false)
            return@launch
        }
        val c = SpeechRecognition.getClient(mlOptions(lang))
        var total = 0L
        var ok = false
        try {
            c.download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadStarted -> {
                        total = s.bytesToDownload
                        progress(if (total > 0) 0 else null)
                    }
                    is DownloadStatus.DownloadProgress ->
                        progress(if (total > 0) (s.totalBytesDownloaded * 100 / total).toInt().coerceIn(0, 100) else null)
                    is DownloadStatus.DownloadCompleted -> ok = true
                    is DownloadStatus.DownloadFailed -> ok = false
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ok = false
        } finally {
            runCatching { c.close() }
        }
        if (ok) mlKitFailed = false
        done(ok)
    }
}
