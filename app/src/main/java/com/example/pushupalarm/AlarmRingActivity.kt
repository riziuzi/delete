package com.example.pushupalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * Full-screen alarm UI: camera preview, live rep count. When the count reaches the target
 * the alarm service is stopped. In practice mode no alarm is involved.
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val practice = intent.getBooleanExtra(EXTRA_PRACTICE, false)
        val target = AlarmPrefs(this).target

        // While the alarm rings, Back must not dismiss the screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (practice) finish()
            }
        })

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RingScreen(this, practice, target) {
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
private fun RingScreen(owner: LifecycleOwner, practice: Boolean, target: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var count by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf("Set the phone on the floor, side-on to you") }
    val counter = remember { PushUpCounter() }

    LaunchedEffect(count) { if (count >= target) onDone() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraWithPose(owner, counter) { r ->
                count = r.count
                hint = r.hint
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$count / $target", fontSize = 64.sp, color = Color.White)
            Text(if (granted) hint else "Camera permission needed", fontSize = 20.sp, color = Color.White)
        }
        if (!practice) {
            HoldToStop(20, Modifier.align(Alignment.BottomEnd).padding(16.dp), onDone)
        }
    }
}

@Composable
private fun CameraWithPose(owner: LifecycleOwner, counter: PushUpCounter, onResult: (PushUpCounter.Result) -> Unit) {
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { PoseAnalyzer(counter, onResult) }
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            analyzer.close()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = androidx.camera.core.Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
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
