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
import com.riziuzi.utho.alarm.Notifications
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.Hinglish
import com.riziuzi.utho.core.Moods
import com.riziuzi.utho.core.Punctuator
import com.riziuzi.utho.core.StudySession
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import com.riziuzi.utho.voice.VoiceRecognizer
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * The voice check-in. Pops up every N minutes while studying (also over the lock screen), or when the
 * user taps Speak. Listens up to 15 seconds, writes what was said in Hinglish, and picks the subject
 * and the mood by itself. Closes on its own; if nothing was said it records "not selected / not said".
 */
class CheckInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Notifications.cancel(this, Notifications.ID_CHECKIN)
        val session = Store.get(this).activeSession()
        val manual = intent.getBooleanExtra(EXTRA_MANUAL, false)
        if (session == null && !manual) {
            finish()
            return
        }
        val prefs = Prefs(this)
        setContent {
            UthoTheme(prefs.theme, prefs.lang) {
                VoiceCheckIn(session, prefs.checkInMin, prefs.beepVolume) { finish() }
            }
        }
    }

    companion object {
        const val EXTRA_MANUAL = "manual"
        const val LISTEN_SECONDS = 15

        fun manualIntent(ctx: Context): Intent = Intent(ctx, CheckInActivity::class.java).putExtra(EXTRA_MANUAL, true)
    }
}

private enum class Stage { LISTENING, RESULT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceCheckIn(session: StudySession?, interval: Int, beepVolume: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { Store.get(ctx) }
    val subjects = remember { prefs.subjects.ifEmpty { listOfNotNull(session?.subject) } }
    val startedAt = remember { System.currentTimeMillis() }

    var stage by remember { mutableStateOf(Stage.LISTENING) }
    var secondsLeft by remember { mutableIntStateOf(CheckInActivity.LISTEN_SECONDS) }
    val raw = remember { mutableStateListOf<String>() }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var lastSpeech by remember { mutableLongStateOf(0L) }
    var problem by remember { mutableStateOf<String?>(null) }

    var text by remember { mutableStateOf("") }
    var guessedTopic by remember { mutableStateOf<String?>(null) }
    var topic by remember { mutableStateOf<String?>(null) }
    var mood by remember { mutableStateOf<Int?>(null) }
    var closeIn by remember { mutableIntStateOf(5) }
    var closeAt by remember { mutableLongStateOf(0L) }
    var saved by remember { mutableStateOf(false) }

    val micOk = remember { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

    fun save() {
        if (saved) return
        saved = true
        val chosen = topic
        val corrected = chosen != guessedTopic
        val c = CheckIn(startedAt, text, chosen, corrected = corrected, mood = mood)
        if (session != null) store.addCheckIn(session.id, c) else store.addFreeNote(c)
        mood?.let { store.setDayMood(LocalDate.now(Summaries.zone), it) }
        if (corrected && chosen != null && text.isNotBlank()) store.learn(chosen, text)
        onDone()
    }

    /** Turn what was heard into Hinglish text, a subject and a mood. */
    fun analyse() {
        val heard = (raw + listOf(partial)).filter { it.isNotBlank() }
        val original = heard.joinToString(" ")
        text = Punctuator.paragraph(heard.map { Hinglish.convert(it) })
        val found = if (text.isBlank()) null else Summaries.classifier(ctx).classify("$original $text").best
        guessedTopic = found
        topic = found
        mood = if (text.isBlank()) null else Moods.detect(text) ?: Moods.detect(original)
        closeIn = if (text.isBlank()) 3 else 5
        closeAt = System.currentTimeMillis() + closeIn * 1000L
        stage = Stage.RESULT
    }

    val recognizer = remember {
        VoiceRecognizer(ctx, prefs.voiceLang, object : VoiceRecognizer.Listener {
            override fun onPartial(text: String) {
                partial = text
                lastSpeech = System.currentTimeMillis()
            }
            override fun onSegment(text: String) {
                raw += text
                partial = ""
                lastSpeech = System.currentTimeMillis()
            }
            override fun onLevel(level: Float) {
                setLevel(level)
            }
            override fun onProblem(problem: VoiceRecognizer.Problem, code: Int) {
                setProblem(problemText(problem, lang))
            }
            override fun onFinished() {
                // After a problem, keep the message on screen; the 15 s timer (or Done) moves on.
                if (stage == Stage.LISTENING && problem == null) analyse()
            }

            private fun setLevel(v: Float) {
                level = v
            }
            private fun setProblem(v: String) {
                problem = v
            }
        })
    }

    DisposableEffect(Unit) {
        val beeper = Beeper(beepVolume)
        beeper.chime()
        if (micOk) {
            recognizer.start()
        } else {
            problem = lang.t2(
                "Microphone permission is off. Open Utho once and allow the microphone.",
                "Mic ki permission band hai. Utho kholkar mic allow karein.",
            )
        }
        onDispose {
            recognizer.destroy()
            beeper.release()
        }
    }

    // Listen up to 15 seconds; stop early 2.5 s after the user stops talking.
    LaunchedEffect(Unit) {
        while (stage == Stage.LISTENING && secondsLeft > 0) {
            delay(250)
            val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            secondsLeft = (CheckInActivity.LISTEN_SECONDS - elapsed).coerceAtLeast(0)
            val paused = raw.isNotEmpty() && partial.isBlank() && System.currentTimeMillis() - lastSpeech > 2500
            if (paused) break
        }
        if (stage == Stage.LISTENING) {
            if (micOk && problem == null) recognizer.stop() else analyse()
        }
    }

    // Show the result briefly, then save and close by itself.
    LaunchedEffect(stage) {
        if (stage != Stage.RESULT) return@LaunchedEffect
        while (!saved) {
            val left = closeAt - System.currentTimeMillis()
            if (left <= 0) break
            closeIn = ((left + 999) / 1000).toInt()
            delay(200)
        }
        save()
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Quick check-in", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        if (stage == Stage.LISTENING) {
            Text(
                if (session != null && interval > 0) {
                    tr("What did you study in the last $interval min? How's your mood?", "Pichhle $interval min mein kya padha? Mood kaisa hai?")
                } else {
                    tr("What did you study? How's your mood?", "Kya padha? Mood kaisa hai?")
                },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Muted(tr("e.g. \"Polity, fundamental rights. Feeling good.\"", "jaise: \"Polity ke fundamental rights padhe, mood accha hai\""), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Ring(secondsLeft / CheckInActivity.LISTEN_SECONDS.toFloat(), level, "$secondsLeft")
            val live = (raw + listOf(partial)).filter { it.isNotBlank() }.joinToString(" ") { Hinglish.convert(it) }
            Text(
                live.ifBlank { tr("Listening… Hindi or English", "Sun rahe hain… Hindi ya English mein bolein") },
                fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("checkInLive"),
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            ButtonsRow {
                SecondButton("Skip", { onDone() }, Modifier.weight(1f))
                BigButton(tr("Done", "Ho gaya"), {
                    if (micOk && problem == null) recognizer.stop() else analyse()
                }, Modifier.weight(1f).testTag("checkInDone"))
            }
        } else {
            if (text.isBlank()) {
                Text(tr("No answer", "Koi jawaab nahi"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            } else {
                Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.testTag("checkInText"))
            }
            problem?.let { Muted(it, textAlign = TextAlign.Center) }
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
                        closeAt = System.currentTimeMillis() + 4000
                    }, minHeight = 44.dp)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..4).forEach { m ->
                    OptionTile(Moods.label(m, lang), m == mood, {
                        mood = if (mood == m) null else m
                        closeAt = System.currentTimeMillis() + 4000
                    }, minHeight = 44.dp)
                }
            }
            BigButton("Save ($closeIn)", { save() }, Modifier.testTag("checkInSave"))
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
        Text(label, fontSize = 44.sp, fontWeight = FontWeight.Bold)
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
