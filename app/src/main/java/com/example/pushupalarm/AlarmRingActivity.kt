package com.example.pushupalarm

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.delay

/**
 * Full-screen alarm UI: camera preview with skeleton, depth bar, live rep count. When the count
 * reaches the target the alarm service is stopped. In practice mode no alarm is involved.
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val practice = intent.getBooleanExtra(EXTRA_PRACTICE, false)
        val prefs = AlarmPrefs(this)
        // In practice mode the phone's volume buttons adjust the beep (alarm stream).
        if (practice) volumeControlStream = AudioManager.STREAM_ALARM

        // While the alarm rings, Back must not dismiss the screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (practice) finish()
            }
        })

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RingScreen(this, practice, prefs.target, prefs.beepVolume) {
                    if (!practice) stopService(Intent(this, AlarmService::class.java))
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_PRACTICE = "practice"
    }
}

@Composable
private fun RingScreen(owner: LifecycleOwner, practice: Boolean, target: Int, beepVolume: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val counter = remember { PushUpCounter() }
    var result by remember { mutableStateOf<PushUpCounter.Result?>(null) }
    var skeleton by remember { mutableStateOf<Map<Int, Pt>>(emptyMap()) }
    val count = result?.count ?: 0

    val beeper = remember { RepBeeper(beepVolume) }
    DisposableEffect(Unit) { onDispose { beeper.release() } }

    LaunchedEffect(count) {
        if (count > 0) beeper.beep()
        if (count >= target) {
            delay(300) // let the last beep play
            onDone()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraWithPose(owner) { pose ->
                skeleton = pose?.let { PoseUtils.landmarks(it) } ?: emptyMap()
                result = counter.update(pose?.let { PoseUtils.leftArm(it) }, pose?.let { PoseUtils.rightArm(it) })
            }
            SkeletonOverlay(skeleton, Modifier.fillMaxSize())
        }

        DepthBar(
            angle = result?.elbowAngle,
            phase = result?.phase ?: PushUpCounter.Phase.UNKNOWN,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .fillMaxHeight(0.7f)
                .width(56.dp),
        )

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$count / $target", fontSize = 64.sp, color = Color.White)
            Text(
                if (granted) result?.hint ?: "Set the phone on the floor, side-on to you" else "Camera permission needed",
                fontSize = 20.sp,
                color = Color.White,
            )
        }
        if (!practice) {
            HoldToStop(20, Modifier.align(Alignment.BottomEnd).padding(16.dp), onDone)
        }
    }
}

/**
 * Runs the on-device ML Kit pose model on the front camera. CameraX's MlKitAnalyzer returns
 * landmark coordinates already mapped onto the PreviewView (scaled and mirrored), so the
 * skeleton can be drawn directly on top.
 */
@Composable
private fun CameraWithPose(owner: LifecycleOwner, onPose: (Pose?) -> Unit) {
    val ctx = LocalContext.current
    val latestOnPose by rememberUpdatedState(onPose)
    val detector = remember {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
        )
    }
    val controller = remember { LifecycleCameraController(ctx) }

    DisposableEffect(Unit) {
        val main = ContextCompat.getMainExecutor(ctx)
        controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisAnalyzer(
            main,
            MlKitAnalyzer(listOf(detector), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, main) { r ->
                latestOnPose(r.getValue(detector))
            },
        )
        controller.bindToLifecycle(owner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            detector.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c -> PreviewView(c).also { it.controller = controller } },
    )
}

@Composable
private fun SkeletonOverlay(points: Map<Int, Pt>, modifier: Modifier) {
    Canvas(modifier) {
        fun bone(a: Int, b: Int, color: Color, width: Float) {
            val p1 = points[a] ?: return
            val p2 = points[b] ?: return
            drawLine(color, Offset(p1.x, p1.y), Offset(p2.x, p2.y), strokeWidth = width, cap = StrokeCap.Round)
        }
        PoseUtils.BODY_BONES.forEach { (a, b) -> bone(a, b, Color(0xCC00E5FF), 8f) }
        PoseUtils.ARM_BONES.forEach { (a, b) -> bone(a, b, Color(0xFFFFEB3B), 12f) }
        points.values.forEach { drawCircle(Color.White, radius = 9f, center = Offset(it.x, it.y)) }
    }
}

/**
 * Vertical bar on the left. Fills from the top as the elbows bend: empty = arms straight,
 * full = deep enough for the rep to count. Turns green once the bottom is reached.
 */
@Composable
private fun DepthBar(angle: Float?, phase: PushUpCounter.Phase, modifier: Modifier) {
    val up = PushUpCounter.UP_THRESHOLD
    val down = PushUpCounter.DOWN_THRESHOLD
    val depth = if (angle == null) 0f else ((up - angle) / (up - down)).coerceIn(0f, 1f)
    val shown by animateFloatAsState(depth, label = "depth")
    val reached = phase == PushUpCounter.Phase.DOWN
    val color = when {
        reached -> Color(0xFF4CAF50)
        shown > 0.7f -> Color(0xFFFFC107)
        else -> Color(0xFF2196F3)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                angle == null -> "-"
                reached -> "UP!"
                else -> "DOWN"
            },
            color = Color.White,
            fontSize = 14.sp,
        )
        Box(
            Modifier
                .weight(1f)
                .width(36.dp)
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0x55FFFFFF)),
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(shown).background(color))
        }
        Text("${(shown * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
    }
}

/** Safety valve: if the camera or model fails you must still be able to stop the alarm. */
@Composable
private fun HoldToStop(seconds: Int, modifier: Modifier, onDone: () -> Unit) {
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
        modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                holding = true
                tryAwaitRelease()
                holding = false
            })
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Emergency stop: hold ${seconds}s", color = Color.White, fontSize = 14.sp)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(180.dp))
    }
}
