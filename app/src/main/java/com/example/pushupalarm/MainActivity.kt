package com.example.pushupalarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

private fun missingPermissions(ctx: Context): List<String> {
    val needed = mutableListOf(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
    return needed.filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val prefs = remember { AlarmPrefs(ctx) }
    val is24 = remember { DateFormat.is24HourFormat(ctx) }
    val timeState = rememberTimePickerState(prefs.hour, prefs.minute, is24)
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var target by remember { mutableIntStateOf(prefs.target) }
    var message by remember { mutableStateOf(if (prefs.enabled) "Alarm is on" else "") }
    var beepVolume by remember { mutableIntStateOf(prefs.beepVolume) }
    var level by remember { mutableStateOf(prefs.level) }
    val scope = rememberCoroutineScope()

    fun enable() {
        prefs.hour = timeState.hour
        prefs.minute = timeState.minute
        prefs.target = target
        prefs.enabled = true
        AlarmScheduler.schedule(ctx)
        enabled = true
        message = "Alarm set for %02d:%02d. It rings until you do $target push-ups."
            .format(timeState.hour, timeState.minute)
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                // Needed so the alarm screen opens over the lock screen.
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")),
                )
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (missingPermissions(ctx).isEmpty()) enable()
        else message = "Camera and notification permissions are required for the alarm."
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PushUp Alarm", style = MaterialTheme.typography.headlineMedium)
        TimePicker(state = timeState)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { target = (target - 5).coerceAtLeast(5) }) { Text("-5") }
            Text("$target push-ups", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { target = (target + 5).coerceAtMost(200) }) { Text("+5") }
        }

        Text("How deep must you go?", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Level.entries.forEach { l ->
                val pick = {
                    level = l
                    prefs.level = l
                }
                if (l == level) Button(onClick = pick) { Text(l.label) }
                else OutlinedButton(onClick = pick) { Text(l.label) }
            }
        }
        Text(
            when (level) {
                Level.EASY -> "Easy: a small bend counts. Most forgiving."
                Level.NORMAL -> "Normal: elbows bent to about 105 degrees."
                Level.STRICT -> "Strict: deep push-ups only (about 90 degrees)."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            "Rep beep volume: " + if (beepVolume == 0) "Off" else "$beepVolume%",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = beepVolume.toFloat(),
            onValueChange = { beepVolume = it.toInt() },
            onValueChangeFinished = {
                prefs.beepVolume = beepVolume
                scope.launch {
                    val b = RepBeeper(beepVolume)
                    b.beep()
                    delay(400)
                    b.release()
                }
            },
            valueRange = 0f..100f,
        )
        Text(
            "Beep plays at your phone's alarm volume. Practice screen: volume buttons adjust it too.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val missing = missingPermissions(ctx)
                if (missing.isEmpty()) enable() else permLauncher.launch(missing.toTypedArray())
            },
        ) { Text(if (enabled) "Update alarm" else "Set alarm") }

        if (enabled) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    prefs.enabled = false
                    AlarmScheduler.cancel(ctx)
                    enabled = false
                    message = "Alarm turned off"
                },
            ) { Text("Turn alarm off") }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                prefs.target = target
                ctx.startActivity(
                    Intent(ctx, AlarmRingActivity::class.java).putExtra(AlarmRingActivity.EXTRA_PRACTICE, true),
                )
            },
        ) { Text("Practice (test the camera, no alarm)") }

        if (message.isNotEmpty()) Text(message)
        Text(
            "Tip: put the phone on the floor 1.5-2 m away, upright or sideways. Side-on to the " +
                "camera counts best; facing it works too. Keep your arms in view.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
