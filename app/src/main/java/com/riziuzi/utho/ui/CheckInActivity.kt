package com.riziuzi.utho.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.riziuzi.utho.alarm.AlarmService
import com.riziuzi.utho.alarm.Beeper
import com.riziuzi.utho.voice.BeepMuter
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
import com.riziuzi.utho.voice.VoiceRecognizer
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Voice (or typed) study notes. Three ways in:
 *  - TIMED: the pop-up every N minutes (Settings), also over the lock screen. Listens a few seconds (Settings).
 *    If nothing is said the check-in stays "missed" (those minutes aren't counted) and a notification lets you
 *    answer later.
 *  - NOTE: Speak / Type from the home screen or journal. The mic stays on until Done.
 *  - ANSWER: a missed check-in opened later from its notification or the journal. Like NOTE, and the
 *    answer fills in that check-in so its minutes count.
 * The text is written in Hinglish; subject and mood are picked automatically and can be fixed with a tap.
 */
class CheckInActivity : ComponentActivity() {
    private var shownMode: Mode? = null

    /**
     * The pop-up is single-top, so a new request (Speak, a missed-check-in notification) arrives here while one is
     * open. It replaces the open one, except that a timed pop-up never interrupts a note you're in the middle of:
     * that check-in stays missed and its notification lets you answer it after.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newMode = Mode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: Mode.TIMED
        val time = intent.getLongExtra(EXTRA_TIME, -1L)
        if (newMode == Mode.TIMED && shownMode != null && shownMode != Mode.TIMED) {
            if (time > 0) Notifications.showMissed(this, time)
            return
        }
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Notifications.cancel(this, Notifications.ID_CHECKIN)
        val store = Store.get(this)
        val session = store.activeSession()
        val now = System.currentTimeMillis()
        var mode = Mode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: Mode.TIMED
        var target = intent.getLongExtra(EXTRA_TIME, -1L).takeIf { it > 0 }?.let { store.findCheckIn(it) }
        if (target != null && target.kind != CheckInKind.MISSED) target = null // already answered
        // A timed pop-up opened late from the notification tray: answer it without the 15 s limit.
        if (mode == Mode.TIMED && target != null && now - target.timeMs > LATE_MS) mode = Mode.ANSWER
        if (mode == Mode.ANSWER && target == null) mode = Mode.NOTE
        if (mode == Mode.TIMED && session == null && target == null) {
            finish()
            return
        }
        if (target != null) Notifications.cancel(this, Notifications.ID_MISSED)
        com.riziuzi.utho.data.MeaningModel.preload(this)
        shownMode = mode
        val typeFirst = intent.getBooleanExtra(EXTRA_TYPE, false)
        val prefs = Prefs(this)
        setContent {
            UthoTheme(prefs.theme, prefs.lang) {
                CheckInScreen(mode, session, target, typeFirst, prefs.checkInMin, prefs.listenSeconds) { finish() }
            }
        }
    }

    enum class Mode { TIMED, NOTE, ANSWER }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_TIME = "time"
        const val EXTRA_TYPE = "type"
        private const val LATE_MS = 2 * 60_000L

        /** Speak (or type) a note now: home screen, journal, study screen. */
        fun noteIntent(ctx: Context, type: Boolean = false): Intent =
            Intent(ctx, CheckInActivity::class.java).putExtra(EXTRA_MODE, Mode.NOTE.name).putExtra(EXTRA_TYPE, type)

        /** The timed pop-up for the check-in saved at [timeMs]. */
        fun timedIntent(ctx: Context, timeMs: Long): Intent =
            Intent(ctx, CheckInActivity::class.java).putExtra(EXTRA_MODE, Mode.TIMED.name).putExtra(EXTRA_TIME, timeMs)

        /** Answer the missed check-in saved at [timeMs]. */
        fun answerIntent(ctx: Context, timeMs: Long, type: Boolean = false): Intent =
            Intent(ctx, CheckInActivity::class.java).putExtra(EXTRA_MODE, Mode.ANSWER.name).putExtra(EXTRA_TIME, timeMs).putExtra(EXTRA_TYPE, type)
    }
}

private enum class Stage { LISTENING, TYPING, RESULT, MISSED }

/** A note you start yourself listens until you tap Done (with a 30-minute safety stop). */
private const val NOTE_MAX_MS = 30 * 60_000L

/** How long media stays muted after the mic closes, so the speech service's closing beep can't be heard. */
private const val UNMUTE_DELAY_MS = 2000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInScreen(
    mode: CheckInActivity.Mode,
    session: StudySession?,
    target: CheckIn?,
    typeFirst: Boolean,
    interval: Int,
    listenSeconds: Int,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { Store.get(ctx) }
    val timed = mode == CheckInActivity.Mode.TIMED
    val subjects = remember { prefs.subjects.ifEmpty { listOfNotNull(session?.subject) } }
    val startedAt = remember { target?.timeMs ?: System.currentTimeMillis() }

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
    // One soft sound when the mic really opens, one when it closes; Google's own beeps are muted meanwhile.
    val sound = remember { prefs.checkInSound }
    val muter = remember { BeepMuter(ctx) }
    var micOpen by remember { mutableStateOf(false) }

    fun closeMic() {
        if (micOpen) {
            micOpen = false
            Earcons.play(ctx, sound, open = false)
        }
        // The speech service beeps when it releases the mic (done in cancel/destroy); keep media muted until that has passed.
        Handler(Looper.getMainLooper()).postDelayed({ muter.unmute() }, UNMUTE_DELAY_MS)
    }

    fun save() {
        if (saved) return
        saved = true
        val chosen = topic
        val corrected = chosen != guessedTopic
        val c = CheckIn(startedAt, text, chosen, CheckInKind.SPOKEN, corrected = corrected, mood = mood)
        when {
            target != null -> store.updateCheckIn(target.timeMs) { c }
            session != null && timed -> store.addCheckIn(session.id, c)
            session != null -> store.addCheckIn(session.id, c.copy(timeMs = System.currentTimeMillis()))
            else -> store.addFreeNote(c)
        }
        mood?.let { store.setDayMood(Instant.ofEpochMilli(startedAt).atZone(Summaries.zone).toLocalDate(), it) }
        // The word list grows from notes you confirmed: a fix counts double, a plain Save once.
        // A timed pop-up that saved itself without a tap teaches nothing (it may have guessed wrong).
        if (chosen != null && text.isNotBlank()) {
            if (corrected) store.learn(chosen, text, strong = true) else if (!timed || touched) store.learn(chosen, text, strong = false)
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
        closeIn = 5
        closeAt = System.currentTimeMillis() + 5000
        stage = Stage.RESULT
    }

    /** Nobody answered the timed pop-up: keep it as missed and offer to answer later. */
    fun missed() {
        if (stage == Stage.MISSED) return
        stage = Stage.MISSED
        val t = target?.timeMs ?: startedAt
        if (target == null && session != null) store.addCheckIn(session.id, CheckIn(t, "", null, CheckInKind.MISSED))
        Notifications.showMissed(ctx, t)
    }

    fun afterListening() {
        closeMic()
        val heard = (raw + listOf(partial)).filter { it.isNotBlank() }
        when {
            heard.isNotEmpty() -> analyse(heard.joinToString(" "), Hinglish.note(heard))
            timed -> missed()
            else -> {
                typeHint = problem ?: lang.t2("Didn't catch anything. Type it, or tap Speak to try again.", "Kuch sunai nahi diya. Type karein, ya Bolein dabakar phir try karein.")
                stage = Stage.TYPING
            }
        }
    }

    val recognizer = remember {
        VoiceRecognizer(ctx, prefs.voiceLang, object : VoiceRecognizer.Listener {
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
            override fun onProblem(problem: VoiceRecognizer.Problem, code: Int) = setProblem(problemText(problem, lang))
            override fun onFinished() {
                // After a problem the message stays up; the timer (timed) or Done (note) moves on.
                if (stage == Stage.LISTENING && (problem == null || stopping)) afterListening()
            }

            private fun setReady() {
                ready = true
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
            // Closing while the mic is on: let the service go while muted, then bring media back.
            recognizer.destroy()
            Handler(Looper.getMainLooper()).postDelayed({ muter.unmute() }, UNMUTE_DELAY_MS)
        }
    }

    // One listening round: wait for the mic to open, then 15 s (timed) or until Done (note, max 3 min).
    LaunchedEffect(round, stage == Stage.LISTENING) {
        if (stage != Stage.LISTENING) return@LaunchedEffect
        if (micOk) {
            muter.mute()
            // One session for the whole note, so the phone's mic beep comes once, not after every pause.
            recognizer.sessionMs = if (timed) (listenSeconds + 10) * 1000L else NOTE_MAX_MS
            recognizer.start()
        } else {
            problem = lang.t2("Microphone permission is off. Open Utho once and allow the microphone.", "Mic ki permission band hai. Utho kholkar mic allow karein.")
        }
        val readyBy = System.currentTimeMillis() + 5000
        while (!ready && problem == null && System.currentTimeMillis() < readyBy) delay(100)
        val from = System.currentTimeMillis()
        while (stage == Stage.LISTENING) {
            delay(200)
            val elapsed = System.currentTimeMillis() - from
            elapsedSec = (elapsed / 1000).toInt()
            if (timed) {
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
            Stage.RESULT -> if (timed) {
                while (!saved) {
                    val left = closeAt - System.currentTimeMillis()
                    if (left <= 0) break
                    closeIn = ((left + 999) / 1000).toInt()
                    delay(200)
                }
                save()
            }
            Stage.MISSED -> {
                val until = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < until) delay(200)
                onDone()
            }
            else -> {}
        }
    }

    val question = when {
        target != null && interval > 0 -> {
            val a = JournalWriter.clock(target.timeMs - interval * 60_000L, Summaries.zone, lang)
            val b = JournalWriter.clock(target.timeMs, Summaries.zone, lang)
            tr("What did you study from $a to $b?", "$a se $b tak kya padha?")
        }
        timed && interval > 0 -> tr("What did you study in the last $interval min? How's your mood?", "Pichhle $interval min mein kya padha? Mood kaisa hai?")
        else -> tr("What did you study? How's your mood?", "Kya padha? Mood kaisa hai?")
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            when {
                target != null -> tr("Missed check-in", "Missed check-in")
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
                    timed -> "$secondsLeft"
                    else -> "%d:%02d".format(elapsedSec / 60, elapsedSec % 60)
                }
                Ring(if (timed) secondsLeft / listenSeconds.toFloat() else 1f, level, label)
                val live = (raw + listOf(partial)).filter { it.isNotBlank() }.joinToString(" ") { Hinglish.convert(it) }
                Text(
                    live.ifBlank {
                        if (ready) tr("Listening… speak now", "Sun rahe hain… ab bolein") else tr("Getting the mic ready…", "Mic ready ho raha hai…")
                    },
                    fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("checkInLive"),
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                ButtonsRow {
                    if (timed) {
                        SecondButton(tr("Skip", "Skip"), {
                            recognizer.destroy()
                            closeMic()
                            missed()
                        }, Modifier.weight(1f))
                    } else {
                        SecondButton(tr("Type", "Type"), {
                            recognizer.destroy()
                            closeMic()
                            val heard = (raw + listOf(partial)).filter { it.isNotBlank() }
                            typed = if (heard.isEmpty()) "" else Hinglish.note(heard)
                            stage = Stage.TYPING
                        }, Modifier.weight(1f).testTag("checkInTypeInstead"))
                    }
                    BigButton(tr("Done", "Ho gaya"), { stopListening() }, Modifier.weight(1f).testTag("checkInDone"))
                }
                if (!timed) {
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
                    placeholder = { Text("Polity ke fundamental rights padhe, mood accha hai") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("checkInType"),
                )
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                ButtonsRow {
                    SecondButton(tr("Speak", "Bolein"), { listenAgain() }, Modifier.weight(1f))
                    BigButton(tr("Next", "Aage"), {
                        if (typed.isNotBlank()) analyse(typed, Hinglish.note(listOf(typed)))
                    }, Modifier.weight(1f).testTag("checkInNext"), enabled = typed.isNotBlank())
                }
                SmallTextButton(tr("Cancel", "Cancel")) { onDone() }
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
                            closeAt = System.currentTimeMillis() + 5000
                        }, minHeight = 44.dp)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..4).forEach { m ->
                        OptionTile(Moods.label(m, lang), m == mood, {
                            mood = if (mood == m) null else m
                            touched = true
                            closeAt = System.currentTimeMillis() + 5000
                        }, minHeight = 44.dp)
                    }
                }
                if (timed) {
                    BigButton("Save ($closeIn)", {
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

            Stage.MISSED -> {
                Text(tr("Nothing heard", "Kuch sunai nahi diya"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                CardBox {
                    Text("Subject: " + tr("not selected", "select nahi hua"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Mood: " + tr("not said", "nahi bataya"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Muted(
                    tr(
                        "These $interval minutes won't count until you answer. Tap the notification to speak or type it later.",
                        "Jab tak jawaab nahi denge, yeh $interval minute count nahi honge. Baad mein notification par tap karke bolein ya likhein.",
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
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
