package com.riziuzi.utho.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.riziuzi.utho.alarm.AlarmScheduler
import com.riziuzi.utho.alarm.AlarmService
import com.riziuzi.utho.alarm.Beeper
import com.riziuzi.utho.alarm.Notifications
import com.riziuzi.utho.camera.PoseFrameAnalyzer
import com.riziuzi.utho.core.Body
import com.riziuzi.utho.core.CounterState
import com.riziuzi.utho.core.Counters
import com.riziuzi.utho.core.Exercise
import com.riziuzi.utho.core.Hint
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Joint
import com.riziuzi.utho.core.Level
import com.riziuzi.utho.core.Pt
import com.riziuzi.utho.core.WorkoutRecord
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * Full-screen exercise screen: camera, skeleton, depth bar, count.
 * Alarm mode: the alarm keeps ringing until the target is reached (or the emergency stop is held).
 */
class WorkoutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = Prefs(this)
        val alarm = intent.getStringExtra(EXTRA_MODE) == MODE_ALARM
        val exercise = if (alarm) prefs.exercise else runCatching { Exercise.valueOf(intent.getStringExtra(EXTRA_EXERCISE)!!) }.getOrDefault(prefs.exercise)
        val target = if (alarm) prefs.alarmTarget else intent.getIntExtra(EXTRA_TARGET, prefs.alarmTarget)
        val level = if (alarm) prefs.level else runCatching { Level.valueOf(intent.getStringExtra(EXTRA_LEVEL)!!) }.getOrDefault(prefs.level)
        val record = alarm || intent.getBooleanExtra(EXTRA_RECORD, false)
        if (!alarm) volumeControlStream = AudioManager.STREAM_ALARM

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!alarm) finish()
            }
        })

        setContent {
            UthoTheme("dark", prefs.lang) {
                WorkoutScreen(
                    owner = this, alarm = alarm, exercise = exercise, target = target, level = level,
                    beepVolume = prefs.beepVolume, emergencySec = prefs.emergencySec,
                    onClose = { finish() },
                    onComplete = { done, seconds ->
                        val ctx = this
                        if (record && done > 0) {
                            Store.get(ctx).addWorkout(WorkoutRecord(System.currentTimeMillis(), exercise, target, done, seconds, alarm))
                        }
                        if (alarm) {
                            AlarmService.stop(ctx)
                            Notifications.cancel(ctx, Notifications.ID_ALARM)
                            if (prefs.awakeCheck) AlarmScheduler.scheduleAwakeCheck(ctx)
                        }
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ALARM = "alarm"
        const val MODE_WORKOUT = "workout"
        const val EXTRA_EXERCISE = "exercise"
        const val EXTRA_TARGET = "target"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_RECORD = "record"

        fun alarmIntent(ctx: Context): Intent = Intent(ctx, WorkoutActivity::class.java).putExtra(EXTRA_MODE, MODE_ALARM)

        fun workoutIntent(ctx: Context, exercise: Exercise, target: Int, level: Level, record: Boolean): Intent =
            Intent(ctx, WorkoutActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_WORKOUT)
                .putExtra(EXTRA_EXERCISE, exercise.name)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_LEVEL, level.name)
                .putExtra(EXTRA_RECORD, record)
    }
}

@Composable
fun hintText(h: Hint): String = when (h) {
    Hint.NOT_VISIBLE -> tr("Can't see you. Step back so your whole body is in view", "Aap dikh nahi rahe. Peeche hatiye taaki poori body dikhe")
    Hint.GET_IN_POSITION -> tr("Get into position", "Position mein aaiye")
    Hint.STRAIGHTEN_ARMS -> tr("Straighten your arms to start", "Shuru karne ke liye haath seedhe karein")
    Hint.GO_DOWN -> tr("Go down", "Neeche jaaiye")
    Hint.PUSH_UP -> tr("Push up!", "Upar aaiye!")
    Hint.STAND_TALL -> tr("Stand straight, facing the phone", "Phone ki taraf seedhe khade hoiye")
    Hint.SQUAT_DOWN -> tr("Squat down", "Neeche baithiye")
    Hint.STAND_UP -> tr("Stand up!", "Khade hoiye!")
    Hint.OPEN_UP -> tr("Jump out, arms up", "Koodiye, haath upar")
    Hint.CLOSE_IN -> tr("Jump back in", "Wapas aaiye")
    Hint.HOLD -> tr("Hold it!", "Ruke rahiye!")
    Hint.STRAIGHTEN_BODY -> tr("Keep your body straight", "Body seedhi rakhiye")
}

@Composable
private fun WorkoutScreen(
    owner: LifecycleOwner,
    alarm: Boolean,
    exercise: Exercise,
    target: Int,
    level: Level,
    beepVolume: Int,
    emergencySec: Int,
    onClose: () -> Unit,
    onComplete: (done: Int, seconds: Int) -> Unit,
) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val counter = remember { Counters.create(exercise, level) }
    var state by remember { mutableStateOf(CounterState(0, 0f, false, Hint.NOT_VISIBLE)) }
    var skeleton by remember { mutableStateOf<Map<Joint, Pt>>(emptyMap()) }
    var startMs by remember { mutableLongStateOf(0L) }
    var finished by remember { mutableStateOf(false) }
    val beeper = remember { Beeper(beepVolume) }
    DisposableEffect(Unit) { onDispose { beeper.release() } }
    val count = state.count

    LaunchedEffect(count) {
        if (count <= 0 || finished) return@LaunchedEffect
        if (!exercise.isHold || count % 10 == 0) beeper.beep()
        if (count >= target) {
            finished = true
            val seconds = if (startMs > 0) ((System.currentTimeMillis() - startMs) / 1000).toInt() else 0
            onComplete(count, if (exercise.isHold) count else seconds)
            beeper.chime()
            delay(1500)
            onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraWithPose(owner) { body, points, t ->
                if (finished) return@CameraWithPose
                if (body != null && body.points.isNotEmpty() && startMs == 0L) startMs = t
                skeleton = points
                state = counter.update(body, t)
            }
            Skeleton(skeleton, exercise, Modifier.fillMaxSize())
        }

        DepthBar(
            state, exercise,
            Modifier.align(Alignment.CenterStart).padding(start = 16.dp).fillMaxHeight(0.6f).width(56.dp),
        )

        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val big = if (exercise.isHold) "${JournalWriter.seconds(count, com.riziuzi.utho.core.Lang.EN)} / ${JournalWriter.seconds(target, com.riziuzi.utho.core.Lang.EN)}" else "$count / $target"
            Text(
                big, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xCC17181C))
                    .padding(horizontal = 20.dp, vertical = 2.dp).testTag("workoutCount"),
            )
            Text(
                when {
                    finished -> tr("Done! Great start.", "Ho gaya! Badhiya shuruaat.")
                    !granted -> tr("Camera permission needed", "Camera permission chahiye")
                    else -> hintText(state.hint)
                },
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xCC17181C)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xCC17181C)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(exerciseLabel(exercise) + " · " + levelLabel(level), color = Color.White, fontSize = 14.sp)
                    Text(tr("Place the phone 1.5–2 m away", "Phone 1.5–2 meter door rakhiye"), color = Color(0xFFDADCE3), fontSize = 13.sp)
                }
            }
            if (alarm) {
                HoldToStop(emergencySec) {
                    if (!finished) {
                        finished = true
                        val seconds = if (startMs > 0) ((System.currentTimeMillis() - startMs) / 1000).toInt() else 0
                        onComplete(count, seconds)
                        onClose()
                    }
                }
            } else {
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xCC17181C)),
                ) { Text(tr("Close", "Band karein"), color = Color.White, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun levelLabel(l: Level) = when (l) {
    Level.EASY -> tr("Easy", "Aasaan")
    Level.NORMAL -> tr("Normal", "Normal")
    Level.STRICT -> tr("Strict", "Sakht")
}

@Composable
private fun CameraWithPose(owner: LifecycleOwner, onFrame: (Body?, Map<Joint, Pt>, Long) -> Unit) {
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(onFrame)
    val controller = remember { LifecycleCameraController(ctx) }
    val previewView = remember { PreviewView(ctx) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { PoseFrameAnalyzer(previewView) { b, p, t -> latest(b, p, t) } }

    DisposableEffect(Unit) {
        controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        previewView.controller = controller
        runCatching { controller.bindToLifecycle(owner) }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analyzer.close()
            executor.shutdown()
        }
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
}

@Composable
private fun Skeleton(points: Map<Joint, Pt>, exercise: Exercise, modifier: Modifier) {
    val focus = if (exercise == Exercise.PUSHUP || exercise == Exercise.JUMPING_JACK) PoseFrameAnalyzer.ARM_BONES else PoseFrameAnalyzer.LEG_BONES
    Canvas(modifier) {
        fun bone(a: Joint, b: Joint, color: Color, width: Float) {
            val p1 = points[a] ?: return
            val p2 = points[b] ?: return
            drawLine(color, Offset(p1.x, p1.y), Offset(p2.x, p2.y), strokeWidth = width, cap = StrokeCap.Round)
        }
        (PoseFrameAnalyzer.BODY_BONES + PoseFrameAnalyzer.ARM_BONES).forEach { (a, b) -> bone(a, b, Color(0xCC5BC8E8), 8f) }
        focus.forEach { (a, b) -> bone(a, b, Color(0xFFF2C230), 12f) }
        points.values.forEach { drawCircle(Color.White, radius = 9f, center = Offset(it.x, it.y)) }
    }
}

/** Left-side bar: fills as you get closer to the counting point; green once reached. */
@Composable
private fun DepthBar(state: CounterState, exercise: Exercise, modifier: Modifier) {
    val shown by animateFloatAsState(state.progress, label = "depth")
    val color = when {
        state.reached -> Color(0xFF4CAF50)
        shown > 0.7f -> Color(0xFFF2C230)
        else -> Color(0xFF5B8DEF)
    }
    val top = when {
        exercise.isHold -> if (state.reached) tr("HOLD", "RUKO") else tr("LINE", "SEEDHA")
        state.reached -> when (exercise) {
            Exercise.SQUAT -> tr("UP!", "UTHO!")
            Exercise.JUMPING_JACK -> tr("IN!", "ANDAR!")
            else -> tr("UP!", "UPAR!")
        }
        else -> when (exercise) {
            Exercise.JUMPING_JACK -> tr("OPEN", "KHOLO")
            else -> tr("DOWN", "NEECHE")
        }
    }
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x99000000)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(top, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier.weight(1f).width(36.dp).padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp))
                .background(Color(0x66FFFFFF)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp)),
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(shown).background(color))
        }
        Text("${(shown * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
    }
}

/** Safety valve: if the camera fails you can still stop the alarm by holding this. */
@Composable
private fun HoldToStop(seconds: Int, onDone: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(holding) {
        if (!holding) {
            progress = 0f
            return@LaunchedEffect
        }
        val start = SystemClock.elapsedRealtime()
        while (holding) {
            progress = ((SystemClock.elapsedRealtime() - start) / (seconds * 1000f)).coerceAtMost(1f)
            if (progress >= 1f) {
                onDone()
                break
            }
            delay(50)
        }
    }
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xCC17181C)).border(1.dp, Color(0xFF4A4D57), RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    holding = true
                    tryAwaitRelease()
                    holding = false
                })
            }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(tr("Emergency stop: hold ${seconds}s", "Emergency stop: ${seconds}s dabaiye"), color = Color.White, fontSize = 13.sp)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(170.dp))
    }
}
