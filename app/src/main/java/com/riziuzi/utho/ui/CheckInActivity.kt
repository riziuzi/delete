package com.riziuzi.utho.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.riziuzi.utho.alarm.AlarmScheduler
import com.riziuzi.utho.alarm.AlarmService
import com.riziuzi.utho.alarm.CheckIns
import com.riziuzi.utho.alarm.Beeper
import com.riziuzi.utho.voice.AudioGuard
import com.riziuzi.utho.voice.Earcons
import android.os.Handler
import android.os.Looper
import com.riziuzi.utho.alarm.Notifications
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.Hinglish
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Moods
import com.riziuzi.utho.core.StudySession
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import com.riziuzi.utho.voice.VoiceInput
import com.riziuzi.utho.voice.VoiceRecognizer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Voice (or typed) study notes. Ways in:
 *  - TIMED: when a study interval ends the pop-up opens by itself (also over the lock screen) and listens for the
 *    time set in Settings. Something said: it's saved (subject and mood if found) and the study timer goes on.
 *    Nothing said: the study timer stops at your last answer and this interval leaves no entry (see [CheckIns]).
 *  - TEST: the same pop-up from Settings, to check it appears; saves nothing.
 *  - NOTE: Speak / Type from the home screen or journal. The mic stays on until Done.
 *  - ANSWER: an older missed check-in from the journal; the answer fills it in.
 * The text is written in Hinglish; subject and mood are picked automatically and can be fixed with a tap.
 */
class CheckInActivity : ComponentActivity() {
    private var shownMode: Mode? = null
    private var shownTime = -1L

    /**
     * The pop-up is single-top, so a new request (Speak, a missed-check-in notification) arrives here while one is
     * open. It replaces the open one, except that a timed pop-up never interrupts a note you're in the middle of:
     * that check-in stays missed and its notification lets you answer it after.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newMode = Mode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: Mode.TIMED
        val time = intent.getLongExtra(EXTRA_TIME, -1L)
        // The same pop-up asked for twice (full-screen notification and direct start): keep the open one.
        if (newMode == shownMode && time == shownTime) return
        // Never interrupt a note you're in the middle of; that check-in's own deadline decides it.
        if (newMode == Mode.TIMED && shownMode != null && shownMode != Mode.TIMED) return
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Notifications.cancel(this, Notifications.ID_CHECKIN)
        val store = Store.get(this)
        val session = store.activeSession()
        val now = System.currentTimeMillis()
        var mode = Mode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: Mode.TIMED
        val timeExtra = intent.getLongExtra(EXTRA_TIME, -1L)
        var target = timeExtra.takeIf { it > 0 }?.let { store.findCheckIn(it) }
        if (target != null && target.kind != CheckInKind.MISSED) target = null // already answered
        if (mode == Mode.TIMED) {
            // Already answered, or study already stopped for it, or no study running: nothing to ask.
            if (session == null || (timeExtra > 0 && target == null)) {
                finish()
                return
            }
            if (target == null) target = CheckIn(now, "", null, CheckInKind.MISSED).also { store.addCheckIn(session.id, it) }
            // The pop-up is up, so it decides; the backup deadline is only for when it never shows.
            AlarmScheduler.cancelDeadline(this)
        }
        if (mode == Mode.ANSWER && target == null) mode = Mode.NOTE
        // Answering the check-in that stopped study (from its notification): too late now means a plain note.
        val waiting = if (mode == Mode.REVIVE) CheckIns.waiting(this) else null
        if (mode == Mode.REVIVE && waiting == null) mode = Mode.NOTE
        // Answering it here: its notification steps aside (and comes back if this closes without an answer).
        if (mode == Mode.REVIVE) Notifications.cancel(this, Notifications.ID_MISSED)
        AudioGuard.recover(this)
        com.riziuzi.utho.data.MeaningModel.preload(this)
        shownMode = mode
        shownTime = timeExtra
        val typeFirst = intent.getBooleanExtra(EXTRA_TYPE, false)
        val prefs = Prefs(this)
        setContent {
            UthoTheme(prefs.theme, prefs.lang) {
                CheckInScreen(mode, session, target, waiting, typeFirst, prefs.checkInSec, prefs.listenSeconds) { finish() }
            }
        }
    }

    enum class Mode { TIMED, TEST, NOTE, ANSWER, REVIVE }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_TIME = "time"
        const val EXTRA_TYPE = "type"

        /** Tests only (debug builds): text to treat as heard, since an emulator can't hear. */
        const val EXTRA_FAKE_HEARD = "fakeHeard"

        /** Speak (or type) a note now: home screen, journal, study screen. */
        fun noteIntent(ctx: Context, type: Boolean = false): Intent =
            Intent(ctx, CheckInActivity::class.java).putExtra(EXTRA_MODE, Mode.NOTE.name).putExtra(EXTRA_TYPE, type)

        /** The timed pop-up for the check-in saved at [timeMs]. */
        fun timedIntent(ctx: Context, timeMs: Long): Intent =
            Intent(ctx, CheckInActivity::class.java).putExtra(EXTRA_MODE, Mode.TIMED.name).putExtra(EXTRA_TIME, timeMs)

        /** Settings › test the pop-up: listens like a real check-in but saves nothing. */
        fun testIntent(ctx: Context): Intent = Intent(ctx, CheckInActivity::class.java).putExtra(EXTRA_MODE, Mode.TEST.name)

        /** Answer the check-in that stopped study (its notification's Speak), which continues the session. */
        fun reviveIntent(ctx: Context, type: Boolean = false): Intent =
            Intent(ctx, CheckInActivity::class.java).putExtra(EXTRA_MODE, Mode.REVIVE.name).putExtra(EXTRA_TYPE, type)

        /** Answer the missed check-in saved at [timeMs]. */
        fun answerIntent(ctx: Context, timeMs: Long, type: Boolean = false): Intent =
            Intent(ctx, CheckInActivity::class.java).putExtra(EXTRA_MODE, Mode.ANSWER.name).putExtra(EXTRA_TIME, timeMs).putExtra(EXTRA_TYPE, type)
    }
}

private enum class Stage { LISTENING, TYPING, RESULT, STOPPED }

/** A note you start yourself listens until you tap Done (with a 30-minute safety stop). */
private const val NOTE_MAX_MS = 30 * 60_000L


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInScreen(
    mode: CheckInActivity.Mode,
    session: StudySession?,
    target: CheckIn?,
    /** REVIVE: the check-in that stopped study. */
    waiting: CheckIns.Waiting?,
    typeFirst: Boolean,
    /** Seconds between timed check-ins. */
    interval: Int,
    listenSeconds: Int,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { Store.get(ctx) }
    val timed = mode == CheckInActivity.Mode.TIMED
    val test = mode == CheckInActivity.Mode.TEST
    // Answering the check-in that stopped study (from the notification, or Speak / Type on "Study stopped").
    var reviving by remember { mutableStateOf(mode == CheckInActivity.Mode.REVIVE) }
    var reviveTarget by remember { mutableStateOf(waiting) }
    // Timed and test pop-ups listen by themselves for the set time and close by themselves (not when answering later).
    fun isAuto() = (timed || test) && !reviving
    val auto = isAuto()
    val fakeHeard = remember {
        val debuggable = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        (ctx as? android.app.Activity)?.intent?.getStringExtra(CheckInActivity.EXTRA_FAKE_HEARD)?.takeIf { debuggable }
    }
    var resolved by remember { mutableStateOf(false) }
    var stoppedAt by remember { mutableLongStateOf(0L) }
    val subjects = remember { prefs.subjects.ifEmpty { listOfNotNull(session?.subject) } }
    val startedAt = remember { target?.timeMs ?: waiting?.timeMs ?: System.currentTimeMillis() }

    var stage by remember { mutableStateOf(if (typeFirst) Stage.TYPING else Stage.LISTENING) }
    var round by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(listenSeconds) }
    var elapsedSec by remember { mutableIntStateOf(0) }
    val raw = remember { mutableStateListOf<String>() }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var lastSpeech by remember { mutableLongStateOf(0L) }
    var problem by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var typeHint by remember { mutableStateOf<String?>(null) }
    var stopping by remember { mutableStateOf(false) }

    var text by remember { mutableStateOf("") }
    var guessedTopic by remember { mutableStateOf<String?>(null) }
    var topic by remember { mutableStateOf<String?>(null) }
    var mood by remember { mutableStateOf<Int?>(null) }
    var closeIn by remember { mutableIntStateOf(5) }
    var closeAt by remember { mutableLongStateOf(0L) }
    var saved by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }

    val micOk = remember { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    // One soft sound when the mic really opens, one when it closes. Utho opens the mic itself, so the
    // phone's speech service has no sound to play (and media stays muted around it anyway: VoiceInput).
    val sound = remember { prefs.checkInSound }
    var micOpen by remember { mutableStateOf(false) }

    fun closeMic() {
        if (micOpen) {
            micOpen = false
            Earcons.play(ctx, sound, open = false)
        }
    }

    fun save() {
        if (saved) return
        saved = true
        resolved = true
        if (test) {
            onDone()
            return
        }
        val chosen = topic
        val corrected = chosen != guessedTopic
        val c = CheckIn(startedAt, text, chosen, CheckInKind.SPOKEN, corrected = corrected, mood = mood)
        when {
            // Study goes on from where it stopped, and this interval counts.
            reviving -> CheckIns.revive(ctx, c)
            target != null -> {
                store.updateCheckIn(target.timeMs) { c }
                // An answer to the check-in waiting now (pop-up or journal) keeps study going.
                if (timed || session?.checkIns?.any { it.timeMs == target.timeMs } == true) CheckIns.answered(ctx)
            }
            session != null && timed -> store.addCheckIn(session.id, c)
            session != null -> store.addCheckIn(session.id, c.copy(timeMs = System.currentTimeMillis()))
            else -> store.addFreeNote(c)
        }
        mood?.let { store.setDayMood(Instant.ofEpochMilli(startedAt).atZone(Summaries.zone).toLocalDate(), it) }
        // The word list grows from notes you confirmed: a fix counts double, a plain Save once.
        // A timed pop-up that saved itself without a tap teaches nothing (it may have guessed wrong).
        if (chosen != null && text.isNotBlank()) {
            if (corrected) store.learn(chosen, text, strong = true) else if (!isAuto() || touched) store.learn(chosen, text, strong = false)
        }
        onDone()
    }

    /** Hinglish text, subject and mood from what was said or typed. */
    fun analyse(original: String, written: String) {
        text = written
        val found = Summaries.classifier(ctx).classify("$original $written").best
        guessedTopic = found
        topic = found
        mood = Moods.detect(written) ?: Moods.detect(original)
        closeIn = 3
        closeAt = System.currentTimeMillis() + 3000
        stage = Stage.RESULT
    }

    /** Nothing was said: the study timer stops at your last answer and this interval leaves no entry. */
    fun noAnswer() {
        if (stage == Stage.STOPPED || reviving) return
        resolved = true
        stage = Stage.STOPPED
        if (timed && target != null) stoppedAt = CheckIns.noAnswer(ctx, target.timeMs) ?: 0L
    }

    fun afterListening() {
        closeMic()
        val heard = (raw + listOf(partial)).filter { it.isNotBlank() }
        when {
            heard.isNotEmpty() -> analyse(heard.joinToString(" "), Hinglish.note(heard))
            isAuto() -> noAnswer()
            else -> {
                typeHint = problem ?: lang.t2("Didn't catch anything. Type it, or tap Speak to try again.", "Kuch sunai nahi diya. Type karein, ya Bolein dabakar phir try karein.")
                stage = Stage.TYPING
            }
        }
    }

    val recognizer = remember {
        VoiceInput(ctx, prefs.voiceLang, object : VoiceRecognizer.Listener {
            override fun onListening() {
                setReady()
                if (!micOpen) {
                    micOpen = true
                    Earcons.play(ctx, sound, open = true)
                }
            }
            override fun onPartial(text: String) {
                partial = text
                lastSpeech = System.currentTimeMillis()
            }
            override fun onSegment(text: String) {
                raw += text
                partial = ""
                lastSpeech = System.currentTimeMillis()
            }
            override fun onLevel(level: Float) = setLevel(level)
            override fun onProblem(problem: VoiceRecognizer.Problem, code: Int) {
                setProblem(problemText(problem, lang))
                // No voice right now: typing is the way to answer (a timed pop-up still waits for it).
                if (problem == VoiceRecognizer.Problem.SILENT_MISSING || problem == VoiceRecognizer.Problem.NO_MIC) toTyping()
            }
            override fun onFinished() {
                // After a problem the message stays up; the timer (timed) or Done (note) moves on.
                if (stage == Stage.LISTENING && (problem == null || stopping)) afterListening()
            }

            private fun setReady() {
                ready = true
            }
            private fun toTyping() {
                if (stage != Stage.LISTENING) return
                closeMic()
                val heard = (raw + listOf(partial)).filter { it.isNotBlank() }
                typed = if (heard.isEmpty()) "" else Hinglish.note(heard)
                typeHint = problem
                stage = Stage.TYPING
            }
            private fun setLevel(v: Float) {
                level = v
            }
            private fun setProblem(v: String) {
                problem = v
            }
        })
    }

    fun stopListening() {
        stopping = true
        // Keep what's already on screen and cut the mic now (no extra "stop" beep, no wait).
        if (micOk && problem == null) recognizer.cancel() else afterListening()
    }

    fun listenAgain() {
        raw.clear()
        partial = ""
        problem = null
        ready = false
        typeHint = null
        stopping = false
        stage = Stage.LISTENING
        round++
    }

    DisposableEffect(Unit) {
        onDispose {
            val changing = (ctx as? android.app.Activity)?.isChangingConfigurations == true
            // A timed pop-up closed (back, swipe away) before it was answered counts as no answer.
            if (timed && !resolved && !reviving && !changing && target != null) CheckIns.noAnswer(ctx, target.timeMs)
            // Closed without answering the check-in that stopped study: its notification stays to answer later.
            if (reviving && !resolved && !changing) CheckIns.showWaiting(ctx)
            recognizer.destroy()
            if (micOpen) closeMic()
        }
    }

    // A note you started yourself stops listening when you leave the screen (Home, another app), keeping what was said.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            val changing = (ctx as? android.app.Activity)?.isChangingConfigurations == true
            if (e == Lifecycle.Event.ON_STOP && !changing && !isAuto() && stage == Stage.LISTENING) stopListening()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // One listening round: wait for the mic to open, then 15 s (timed) or until Done (note, max 3 min).
    LaunchedEffect(round, stage == Stage.LISTENING) {
        if (stage != Stage.LISTENING) return@LaunchedEffect
        val autoNow = isAuto()
        if (micOk) {
            // One session for the whole note.
            recognizer.sessionMs = if (autoNow) (listenSeconds + 10) * 1000L else NOTE_MAX_MS
            recognizer.start()
        } else {
            problem = lang.t2("Microphone permission is off. Open Utho once and allow the microphone.", "Mic ki permission band hai. Utho kholkar mic allow karein.")
            typeHint = problem
            stage = Stage.TYPING
            return@LaunchedEffect
        }
        val readyBy = System.currentTimeMillis() + 5000
        while (!ready && problem == null && System.currentTimeMillis() < readyBy) delay(100)
        if (fakeHeard != null) {
            raw += fakeHeard
            lastSpeech = System.currentTimeMillis() - 3000
        }
        val from = System.currentTimeMillis()
        while (stage == Stage.LISTENING) {
            delay(200)
            val elapsed = System.currentTimeMillis() - from
            elapsedSec = (elapsed / 1000).toInt()
            if (autoNow) {
                secondsLeft = (listenSeconds - elapsedSec).coerceAtLeast(0)
                val paused = raw.isNotEmpty() && partial.isBlank() && System.currentTimeMillis() - lastSpeech > 2500
                if (secondsLeft == 0 || paused) break
            } else if (elapsed > NOTE_MAX_MS) {
                break
            }
        }
        if (stage == Stage.LISTENING) stopListening()
    }

    // Timed result: save and close by itself (counts wall time). Notes wait for Save.
    LaunchedEffect(stage) {
        when (stage) {
            Stage.RESULT -> if (isAuto()) {
                while (!saved) {
                    val left = closeAt - System.currentTimeMillis()
                    if (left <= 0) break
                    closeIn = ((left + 999) / 1000).toInt()
                    delay(200)
                }
                save()
            }
            Stage.STOPPED -> {
                val until = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < until) delay(200)
                onDone()
            }
            else -> {}
        }
    }

    val question = when {
        reviving && reviveTarget != null && interval > 0 -> {
            val t = reviveTarget!!.timeMs
            val a = JournalWriter.clock(t - interval * 1000L, Summaries.zone, lang)
            val b = JournalWriter.clock(t, Summaries.zone, lang)
            tr("What did you study from $a to $b?", "$a se $b tak kya padha?")
        }
        mode == CheckInActivity.Mode.ANSWER && target != null && interval > 0 -> {
            val a = JournalWriter.clock(target.timeMs - interval * 1000L, Summaries.zone, lang)
            val b = JournalWriter.clock(target.timeMs, Summaries.zone, lang)
            tr("What did you study from $a to $b?", "$a se $b tak kya padha?")
        }
        auto && interval > 0 -> {
            val span = JournalWriter.span(interval, lang)
            tr("What did you study in the last $span? How's your mood?", "Pichhle $span mein kya padha? Mood kaisa hai?")
        }
        else -> tr("What did you study? How's your mood?", "Kya padha? Mood kaisa hai?")
    }

    // Centred on the screen like a dialog; with the keyboard up it sits in the space above it.
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) { Column(
        Modifier.fillMaxWidth().widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            when {
                test -> tr("Test check-in", "Test check-in")
                reviving -> tr("Answer to keep studying", "Jawaab dein, padhai chalti rahegi")
                mode == CheckInActivity.Mode.ANSWER -> tr("Missed check-in", "Missed check-in")
                timed -> tr("Quick check-in", "Quick check-in")
                else -> tr("Study note", "Study note")
            },
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        )
        when (stage) {
            Stage.LISTENING -> {
                Text(question, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Muted(tr("e.g. \"Polity, fundamental rights. Feeling good.\"", "jaise: \"Polity ke fundamental rights padhe, mood accha hai\""), textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                val label = when {
                    !ready && problem == null -> "…"
                    auto -> "$secondsLeft"
                    else -> "%d:%02d".format(elapsedSec / 60, elapsedSec % 60)
                }
                Ring(if (auto) secondsLeft / listenSeconds.toFloat() else 1f, level, label)
                val live = (raw + listOf(partial)).filter { it.isNotBlank() }.joinToString(" ") { Hinglish.convert(it) }
                Text(
                    live.ifBlank {
                        when {
                            problem != null -> tr("The mic didn't start", "Mic shuru nahi hua")
                            ready -> tr("Listening… speak now", "Sun rahe hain… ab bolein")
                            else -> tr("Getting the mic ready…", "Mic ready ho raha hai…")
                        }
                    },
                    fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("checkInLive"),
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                ButtonsRow {
                    // Typing works everywhere, also when you can't talk (library) or the mic fails.
                    SecondButton(tr("Type", "Type"), {
                        recognizer.destroy()
                        closeMic()
                        val heard = (raw + listOf(partial)).filter { it.isNotBlank() }
                        typed = if (heard.isEmpty()) "" else Hinglish.note(heard)
                        stage = Stage.TYPING
                    }, Modifier.weight(1f).testTag("checkInTypeInstead"))
                    BigButton(tr("Done", "Ho gaya"), { stopListening() }, Modifier.weight(1f).testTag("checkInDone"))
                }
                if (auto) {
                    Muted(
                        if (test) tr("This is a test. Nothing is saved.", "Yeh test hai. Kuch save nahi hoga.")
                        else tr("Say nothing and study stops here.", "Kuch nahi bolenge to padhai yahin ruk jaayegi."),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    SmallTextButton(tr("Cancel", "Cancel")) {
                        recognizer.destroy()
                        closeMic()
                        onDone()
                    }
                }
            }

            Stage.TYPING -> {
                Text(question, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                typeHint?.let { Muted(it, textAlign = TextAlign.Center) }
                val focus = remember { FocusRequester() }
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, minLines = 3,
                    placeholder = {
                        Text(
                            tr("e.g. Polity, fundamental rights. Feeling good.", "jaise: Polity ke fundamental rights padhe, mood accha hai"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("checkInType"),
                )
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                ButtonsRow {
                    SecondButton(tr("Speak", "Bolein"), { listenAgain() }, Modifier.weight(1f).testTag("typingSpeak"))
                    BigButton(tr("Next", "Aage"), {
                        if (typed.isNotBlank()) analyse(typed, Hinglish.note(listOf(typed)))
                    }, Modifier.weight(1f).testTag("checkInNext"), enabled = typed.isNotBlank())
                }
                if (timed && !reviving) {
                    Muted(tr("Cancel and study stops here.", "Cancel karne par padhai yahin ruk jaayegi."), textAlign = TextAlign.Center)
                    SmallTextButton(tr("Cancel", "Cancel")) { noAnswer() }
                } else {
                    SmallTextButton(tr("Cancel", "Cancel")) { onDone() }
                }
                // A timed pop-up left open with nothing typed doesn't keep the screen on forever.
                if (timed && !reviving) {
                    LaunchedEffect(typed) {
                        delay(90_000)
                        if (stage == Stage.TYPING) {
                            if (typed.isNotBlank()) analyse(typed, Hinglish.note(listOf(typed))) else noAnswer()
                        }
                    }
                }
            }

            Stage.RESULT -> {
                Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.testTag("checkInText"))
                CardBox {
                    Text(
                        "Subject: " + (topic ?: tr("not selected", "select nahi hua")),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("checkInSubject"),
                    )
                    Text(
                        "Mood: " + (mood?.let { Moods.label(it, lang) } ?: tr("not said", "nahi bataya")),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("checkInMood"),
                    )
                }
                Muted(tr("Wrong? Tap to fix.", "Galat hai? Tap karke theek karein."))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    subjects.forEach { s ->
                        OptionTile(s, s == topic, {
                            topic = if (topic == s) null else s
                            touched = true
                            closeAt = System.currentTimeMillis() + 4000
                        }, minHeight = 44.dp)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..4).forEach { m ->
                        OptionTile(Moods.label(m, lang), m == mood, {
                            mood = if (mood == m) null else m
                            touched = true
                            closeAt = System.currentTimeMillis() + 4000
                        }, minHeight = 44.dp)
                    }
                }
                if (auto) {
                    BigButton(if (test) tr("Close ($closeIn)", "Band ($closeIn)") else "Save ($closeIn)", {
                        touched = true
                        save()
                    }, Modifier.testTag("checkInSave"))
                } else {
                    ButtonsRow {
                        SecondButton(tr("Speak again", "Phir bolein"), { listenAgain() }, Modifier.weight(1f))
                        BigButton("Save", { save() }, Modifier.weight(1f).testTag("checkInSave"))
                    }
                }
            }

            Stage.STOPPED -> {
                Text(
                    if (test) tr("Nothing heard", "Kuch sunai nahi diya") else tr("Study stopped", "Padhai ruk gayi"),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("checkInStopped"),
                )
                Muted(
                    when {
                        test -> tr("In a real check-in the study timer would stop here.", "Asli check-in mein padhai ka timer yahin ruk jaata.")
                        stoppedAt > 0 -> tr(
                            "Nothing was said, so the timer stopped at ${JournalWriter.clock(stoppedAt, Summaries.zone, lang)}. Answer now, or later from the notification, and study goes on.",
                            "Kuch nahi bola, isliye timer ${JournalWriter.clock(stoppedAt, Summaries.zone, lang)} par ruka. Abhi ya baad mein notification se jawaab dein, padhai chalti rahegi.",
                        )
                        else -> tr("Nothing was said.", "Kuch nahi bola.")
                    },
                    textAlign = TextAlign.Center,
                )
                val w = remember(stage) { if (timed) CheckIns.waiting(ctx) else null }
                if (w != null) {
                    ButtonsRow {
                        SecondButton(tr("Type", "Type"), {
                            reviveTarget = w
                            reviving = true
                            resolved = false
                            Notifications.cancel(ctx, Notifications.ID_MISSED)
                            typed = ""
                            typeHint = null
                            stage = Stage.TYPING
                        }, Modifier.weight(1f).testTag("reviveType"))
                        BigButton(tr("Speak", "Bolein"), {
                            reviveTarget = w
                            reviving = true
                            resolved = false
                            Notifications.cancel(ctx, Notifications.ID_MISSED)
                            listenAgain()
                        }, Modifier.weight(1f).testTag("reviveSpeak"))
                    }
                }
                if (timed) SmallTextButton(tr("Close", "Band karein")) { onDone() }
            }
        }
    } }
}

@Composable
private fun Ring(fraction: Float, level: Float, label: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(170.dp)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            drawArc(cs.surfaceVariant, 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            drawArc(cs.primary, -90f, 360f * fraction, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            drawCircle(cs.primaryContainer, radius = size.minDimension * (0.25f + 0.12f * level), center = center)
        }
        Text(label, fontSize = if (label.length > 3) 34.sp else 44.sp, fontWeight = FontWeight.Bold)
    }
}

/** "Still awake?" A minute to tap, or the alarm rings again. */
class AwakeCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Notifications.cancel(this, Notifications.ID_AWAKE)
        val prefs = Prefs(this)
        setContent {
            UthoTheme(prefs.theme, prefs.lang) {
                AwakeScreen(prefs.beepVolume, onAwake = { finish() }, onTimeout = {
                    ContextCompat.startForegroundService(this, Intent(this, AlarmService::class.java))
                    finish()
                })
            }
        }
    }
}

@Composable
private fun AwakeScreen(beepVolume: Int, onAwake: () -> Unit, onTimeout: () -> Unit) {
    var left by remember { mutableIntStateOf(60) }
    var answered by remember { mutableStateOf(false) }
    val beeper = remember { Beeper(beepVolume.coerceAtLeast(40)) }
    DisposableEffect(Unit) { onDispose { beeper.release() } }
    LaunchedEffect(Unit) {
        while (left > 0 && !answered) {
            if (left % 5 == 0) beeper.chime()
            delay(1000)
            left--
        }
        if (!answered) onTimeout()
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(tr("Still awake?", "Jaag rahe ho?"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Ring(left / 60f, 0f, "$left")
        Muted(tr("If you don't tap, the alarm rings again.", "Tap nahi kiya to alarm phir bajega."))
        BigButton(tr("I'm awake", "Main jaag gaya"), {
            answered = true
            onAwake()
        }, Modifier.testTag("imAwake"))
    }
}
