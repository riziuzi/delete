package com.riziuzi.utho.ui

import android.Manifest
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
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
import com.riziuzi.utho.core.Punctuator
import com.riziuzi.utho.core.StudySession
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import com.riziuzi.utho.voice.VoiceRecognizer
import kotlinx.coroutines.delay

/**
 * Pops up every N minutes while studying (also over the lock screen), listens for 15 seconds,
 * writes down what was said with punctuation and files the stretch of time under a subject.
 */
class CheckInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Notifications.cancel(this, Notifications.ID_CHECKIN)
        val session = Store.get(this).activeSession()
        if (session == null) {
            finish()
            return
        }
        val prefs = Prefs(this)
        setContent {
            UthoTheme(prefs.theme, prefs.lang) {
                CheckInScreen(session, prefs.checkInMin, prefs.beepVolume) { finish() }
            }
        }
    }

    companion object {
        const val EXTRA_MANUAL = "manual"
        const val LISTEN_SECONDS = 15
    }
}

private enum class Stage { LISTENING, REVIEW }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInScreen(session: StudySession, interval: Int, beepVolume: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { Store.get(ctx) }
    val subjects = remember { prefs.subjects.ifEmpty { listOf(session.subject) } }
    val previous = session.checkIns.lastOrNull()?.topic ?: session.subject
    val startedAt = remember { System.currentTimeMillis() }

    var stage by remember { mutableStateOf(Stage.LISTENING) }
    var secondsLeft by remember { mutableIntStateOf(CheckInActivity.LISTEN_SECONDS) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    val segments = remember { mutableStateListOf<String>() }
    var text by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    var guessed by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var autoLeft by remember { mutableIntStateOf(8) }
    var saved by remember { mutableStateOf(false) }

    val micOk = remember { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

    fun save(userPicked: Boolean) {
        if (saved) return
        saved = true
        val topic = chosen ?: if (text.isBlank()) null else previous
        store.addCheckIn(session.id, CheckIn(startedAt, text, topic, corrected = userPicked && topic != guessed))
        if (userPicked && topic != null && topic != guessed && text.isNotBlank()) store.learn(topic, text)
        onDone()
    }

    val recognizer = remember {
        VoiceRecognizer(ctx, prefs.voiceLang, object : VoiceRecognizer.Listener {
            override fun onPartial(text: String) {
                partial = text
            }
            override fun onSegment(text: String) {
                segments += text
                partial = ""
            }
            override fun onLevel(level0: Float) {
                level = level0
            }
            override fun onProblem(problem0: VoiceRecognizer.Problem, code: Int) {
                problem = problemText(problem0, lang)
            }
            override fun onFinished() {
                val said = Punctuator.paragraph(segments.toList() + listOf(partial).filter { it.isNotBlank() })
                text = said
                val result = Summaries.classifier(ctx).classify(said)
                guessed = result.best
                chosen = result.best ?: if (said.isNotBlank()) previous else null
                stage = Stage.REVIEW
            }
        })
    }

    DisposableEffect(Unit) {
        val beeper = Beeper(beepVolume)
        beeper.chime()
        if (micOk) recognizer.start() else problem = lang.t2(
            "Microphone permission is off. Open Utho once and allow the microphone.",
            "माइक की अनुमति बंद है। Utho खोलकर माइक की अनुमति दें।",
        )
        onDispose {
            recognizer.destroy()
            beeper.release()
        }
    }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0 && stage == Stage.LISTENING) {
            delay(1000)
            secondsLeft--
        }
        if (stage == Stage.LISTENING) {
            if (micOk && problem == null) recognizer.stop() else {
                text = ""
                chosen = null
                stage = Stage.REVIEW
            }
        }
    }

    LaunchedEffect(stage) {
        if (stage != Stage.REVIEW) return@LaunchedEffect
        while (autoLeft > 0 && !saved) {
            delay(1000)
            autoLeft--
        }
        save(userPicked = false)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(tr("Quick check-in", "छोटा चेक-इन"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        if (stage == Stage.LISTENING) {
            Text(
                if (interval > 0) tr("What did you study in the last $interval min?", "पिछले $interval मिनट में क्या पढ़ा?") else tr("What are you studying?", "क्या पढ़ रहे हैं?"),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Ring(secondsLeft / CheckInActivity.LISTEN_SECONDS.toFloat(), level, "$secondsLeft")
            Text(
                (segments + listOf(partial)).filter { it.isNotBlank() }.joinToString(" ").ifBlank { tr("Listening… speak in Hindi or English", "सुन रहे हैं… हिंदी या अंग्रेज़ी में बोलें") },
                fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("checkInLive"),
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            ButtonsRow {
                SecondButton(tr("Skip", "छोड़ें"), { onDone() }, Modifier.weight(1f))
                BigButton(tr("Done", "हो गया"), {
                    if (micOk && problem == null) recognizer.stop() else stage = Stage.REVIEW
                }, Modifier.weight(1f))
            }
        } else {
            Text(
                if (text.isBlank()) tr("Didn't catch anything", "कुछ सुनाई नहीं दिया") else text,
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.testTag("checkInText"),
            )
            Text(
                tr("Filed under", "इस विषय में रखा"), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                subjects.forEach { s ->
                    OptionTile(s, s == chosen, {
                        chosen = s
                        save(userPicked = true)
                    }, minHeight = 44.dp)
                }
            }
            if (text.isBlank()) {
                Muted(tr("This stretch stays under \"$previous\". Tap another subject to change it.", "यह समय \"$previous\" में ही रहेगा। बदलना हो तो दूसरा विषय चुनें।"), textAlign = TextAlign.Center)
            }
            if (guessed == null && text.isNotBlank()) {
                Muted(tr("Not sure, so it kept \"$previous\". Tap the right one.", "पक्का नहीं, इसलिए \"$previous\" रखा। सही विषय चुनें।"))
            }
            BigButton(tr("Save ($autoLeft)", "सेव करें ($autoLeft)"), { save(userPicked = chosen != guessed) })
        }
    }
}

@Composable
private fun Ring(fraction: Float, level: Float, label: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(180.dp)) {
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
        Text(tr("Still awake?", "जाग रहे हैं?"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Ring(left / 60f, 0f, "$left")
        Muted(tr("If you don't tap, the alarm rings again.", "टैप नहीं किया तो अलार्म फिर बजेगा।"))
        BigButton(tr("I'm awake", "मैं जाग रहा/रही हूँ"), {
            answered = true
            onAwake()
        }, Modifier.testTag("imAwake"))
    }
}
