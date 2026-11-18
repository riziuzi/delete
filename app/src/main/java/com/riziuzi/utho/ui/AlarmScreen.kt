package com.riziuzi.utho.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riziuzi.utho.alarm.AlarmScheduler
import com.riziuzi.utho.core.Level
import com.riziuzi.utho.data.Prefs

@Composable
fun AlarmScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val prefs = remember { Prefs(ctx) }
    val enabled = remember(tick) { prefs.alarmEnabled }
    val hour = remember(tick) { prefs.alarmHour }
    val minute = remember(tick) { prefs.alarmMinute }
    val days = remember(tick) { prefs.alarmDays }
    val exercise = remember(tick) { prefs.exercise }
    val target = remember(tick) { prefs.alarmTarget }
    val level = remember(tick) { prefs.level }
    val awake = remember(tick) { prefs.awakeCheck }
    var showTime by remember { mutableStateOf(false) }

    fun reschedule() = AlarmScheduler.scheduleWake(ctx)

    ScreenScaffold(
        title = tr("Morning alarm", "Subah ka alarm"),
        onBack = { nav.back() },
        actions = {
            Switch(
                checked = enabled,
                onCheckedChange = {
                    prefs.alarmEnabled = it
                    reschedule()
                    if (it && needsFullScreenPermission(ctx)) openFullScreenSettings(ctx)
                },
                modifier = Modifier.semantics { contentDescription = "alarmSwitch" },
            )
        },
    ) {
        CardBox(onClick = { showTime = true }) {
            Text(clockText(hour, minute), fontSize = 52.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Muted(tr("Tap to change", "Badalne ke liye tap karein"), Modifier.fillMaxWidth(), TextAlign.Center)
        }

        val names = listOf(
            tr("M", "M"), tr("T", "T"), tr("W", "W"), tr("T", "T"), tr("F", "F"), tr("S", "S"), tr("S", "S"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            names.forEachIndexed { i, n ->
                val on = days and (1 shl i) != 0
                val cs = MaterialTheme.colorScheme
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (on) cs.primary else cs.surface)
                        .border(1.dp, if (on) cs.primary else cs.outline, CircleShape)
                        .clickable(role = Role.Checkbox) {
                            prefs.alarmDays = days xor (1 shl i)
                            reschedule()
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(n, color = if (on) cs.onPrimary else cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (days == 0) Muted(tr("No day picked: rings once.", "Koi din select nahi: ek hi baar bajega."))

        SectionLabel(tr("To stop it, do", "Band karne ke liye karein"))
        ExerciseGrid(exercise) { e ->
            prefs.exercise = e
            prefs.alarmTarget = if (e.isHold) 60 else 40
        }

        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (exercise.isHold) tr("Seconds", "Second") else tr("How many", "Kitne"), fontSize = 16.sp, modifier = Modifier.weight(1f))
                val step = if (exercise.isHold) 15 else 5
                Stepper(
                    "$target",
                    { prefs.alarmTarget = (target - step).coerceAtLeast(step) },
                    { prefs.alarmTarget = (target + step).coerceAtMost(if (exercise.isHold) 600 else 200) },
                )
            }
            Divider()
            Text(tr("How strict", "Kitni sakhti"), fontSize = 16.sp)
            Segmented(
                listOf(Level.EASY to tr("Easy", "Aasaan"), Level.NORMAL to tr("Normal", "Normal"), Level.STRICT to tr("Strict", "Sakht")),
                level, { prefs.level = it }, Modifier.fillMaxWidth(),
            )
            Divider()
            ToggleRow(
                tr("\"Still awake?\" check", "\"Jaag rahe ho?\" check"),
                tr("5 min later, rings again unless you tap", "5 minute baad poochhega, tap nahi kiya to phir bajega"),
                awake,
            ) { prefs.awakeCheck = it }
        }

        ButtonsRow {
            SecondButton(tr("Try it now", "Abhi try karein"), {
                ctx.startActivity(WorkoutActivity.workoutIntent(ctx, exercise, target, level, record = false))
            }, Modifier.weight(1f).testTag("tryNow"))
            BigButton(tr("Done", "Ho gaya"), { nav.back() }, Modifier.weight(1f))
        }
        Column {}
    }

    if (showTime) {
        TimeDialog(hour, minute, { showTime = false }) { h, m ->
            prefs.alarmHour = h
            prefs.alarmMinute = m
            if (!prefs.alarmEnabled) prefs.alarmEnabled = true
            reschedule()
            showTime = false
        }
    }
}
