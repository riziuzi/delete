package com.riziuzi.utho.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.riziuzi.utho.alarm.AlarmScheduler
import com.riziuzi.utho.alarm.Notifications
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.data.Exams
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Starts a study session: saves it, shows the timer notification, plans the first voice check-in. */
fun beginStudy(ctx: Context, subject: String) {
    val s = Store.get(ctx).startSession(subject)
    Notifications.showSession(ctx, s)
    AlarmScheduler.scheduleNextCheckIn(ctx, s.startMs)
}

fun endStudy(ctx: Context) {
    Store.get(ctx).activeSession()?.let { Store.get(ctx).endSession(it.id) }
    AlarmScheduler.cancelCheckIn(ctx)
    Notifications.cancel(ctx, Notifications.ID_SESSION)
    Notifications.cancel(ctx, Notifications.ID_CHECKIN)
}

fun needsFullScreenPermission(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= 34 && !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

fun openFullScreenSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
}

@Composable
fun TodayScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val prefs = remember { Prefs(ctx) }
    val lang = LocalLang.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val today = LocalDate.now(Summaries.zone)
    val minuteKey = now / 30_000
    val summary = remember(tick, minuteKey) { Summaries.day(ctx, today, now) }
    val active = remember(tick) { Store.get(ctx).activeSession() }
    val name = remember(tick) { prefs.name }
    val goal = remember(tick) { prefs.dailyGoalMin }
    var pickSubject by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Muted(today.format(dateFormatter(lang, "EEEE, d MMM")))
                Text(
                    if (name.isBlank()) tr("Hi there", "नमस्ते") else tr("Hi, $name", "नमस्ते, $name"),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                )
            }
            Avatar(name, 44.dp, Modifier.clip(CircleShape).clickable { nav.go(Route.Profile) }.testTag("avatar"))
        }

        if (remember(tick) { prefs.alarmEnabled } && needsFullScreenPermission(ctx)) {
            CardBox(color = MaterialTheme.colorScheme.secondaryContainer, onClick = { openFullScreenSettings(ctx) }) {
                Text(tr("Allow the alarm screen over the lock screen", "लॉक स्क्रीन पर अलार्म दिखाने की अनुमति दें"), fontWeight = FontWeight.SemiBold)
                Muted(tr("Tap, then turn on full-screen notifications for Utho.", "टैप करें और Utho के लिए फुल-स्क्रीन नोटिफिकेशन चालू करें।"))
            }
        }

        val examDate = remember(tick) { prefs.examDate }
        if (examDate >= 0) {
            val date = LocalDate.ofEpochDay(examDate)
            val left = ChronoUnit.DAYS.between(today, date)
            val exam = Exams.byKey(remember(tick) { prefs.exam })
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Muted(if (lang == Lang.HI) exam.hi else exam.en)
                        Text(date.format(dateFormatter(lang, "d MMM yyyy")), fontWeight = FontWeight.SemiBold)
                    }
                    Text("${left.coerceAtLeast(0)}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Muted(" " + tr("days left", "दिन बाकी"))
                }
            }
        }

        AlarmCard(nav)

        // The one main action.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary)
                .clickable { if (active != null) nav.go(Route.Study) else pickSubject = true }
                .padding(20.dp)
                .testTag("startStudy"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column {
                if (active != null) {
                    Text(tr("Studying", "पढ़ाई चल रही है") + " · " + active.subject, color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(formatElapsed(now - active.startMs), color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
                } else {
                    Text(tr("Start studying", "पढ़ाई शुरू करें"), color = MaterialTheme.colorScheme.onPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    val left = (goal - summary.studyMinutes).coerceAtLeast(0)
                    Text(
                        if (left == 0) tr("Today's goal is done", "आज का लक्ष्य पूरा") else tr(
                            "${JournalWriter.duration(left, Lang.EN)} left of today's ${JournalWriter.duration(goal, Lang.EN)}",
                            "आज के ${JournalWriter.duration(goal, Lang.HI)} में से ${JournalWriter.duration(left, Lang.HI)} बाकी",
                        ),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f), fontSize = 13.sp,
                    )
                }
            }
        }

        SectionLabel(tr("Today", "आज"))
        CardBox {
            val workout = summary.workouts.firstOrNull { it.completed }
            TodayRow(
                done = workout != null,
                partial = false,
                title = tr("Morning workout", "सुबह की एक्सरसाइज़"),
                sub = if (workout != null) {
                    if (workout.exercise.isHold) tr("${workout.done}-second plank", "${workout.done} सेकंड का प्लैंक")
                    else "${workout.done} ${JournalWriter.exerciseName(workout.exercise, lang)} · ${JournalWriter.seconds(workout.durationSec, lang)}"
                } else {
                    tr("Not done yet", "अभी नहीं हुई")
                },
                action = if (workout == null) tr("Workout now", "अभी करें") else null,
                onAction = {
                    ctx.startActivity(WorkoutActivity.workoutIntent(ctx, prefs.exercise, prefs.alarmTarget, prefs.level, record = true))
                },
            )
            Divider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatusDot(done = summary.studyMinutes >= goal, partial = summary.studyMinutes > 0)
                    Text(tr("Study", "पढ़ाई"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Muted(tr("${JournalWriter.duration(summary.studyMinutes, lang)} of ${JournalWriter.duration(goal, lang)}", "${JournalWriter.duration(goal, lang)} में से ${JournalWriter.duration(summary.studyMinutes, lang)}"))
                }
                Bar(if (goal > 0) summary.studyMinutes / goal.toFloat() else 0f)
            }
            Divider()
            val note = remember(tick) { Store.get(ctx).note(today) }
            TodayRow(
                done = note.mood != null,
                partial = false,
                title = tr("Journal", "डायरी"),
                sub = if (note.mood != null) tr("Mood added", "मूड जोड़ा गया") else tr("Writes itself. You add your mood.", "अपने आप लिखी जाती है। आप बस मूड जोड़ें।"),
                action = null,
                onAction = {},
                modifier = Modifier.clickable { nav.go(Route.Journal(today.toEpochDay())) }.testTag("journalRow"),
            )
        }
    }

    if (pickSubject) {
        SubjectDialog(
            subjects = prefs.subjects,
            onDismiss = { pickSubject = false },
            onPick = { s ->
                pickSubject = false
                if (prefs.checkInMin > 0 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                beginStudy(ctx, s)
                nav.go(Route.Study)
            },
        )
    }
}

@Composable
private fun AlarmCard(nav: Nav) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val prefs = remember { Prefs(ctx) }
    val lang = LocalLang.current
    val next = remember(tick) { AlarmScheduler.nextWakeMs(ctx) }
    CardBox(onClick = { nav.go(Route.Alarm) }, modifier = Modifier.testTag("alarmCard")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) }
            Column(Modifier.weight(1f)) {
                if (next != null) {
                    val date = Summaries.dateOf(next)
                    val today = LocalDate.now(Summaries.zone)
                    val day = when (date) {
                        today -> tr("Today", "आज")
                        today.plusDays(1) -> tr("Tomorrow", "कल")
                        else -> date.format(dateFormatter(lang, "EEEE"))
                    }
                    val t = Instant.ofEpochMilli(next).atZone(Summaries.zone)
                    Text("$day, ${clockText(t.hour, t.minute)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    val target = remember(tick) { prefs.alarmTarget }
                    val ex = remember(tick) { prefs.exercise }
                    Muted(
                        if (ex.isHold) tr("Stops after a $target-second plank", "$target सेकंड के प्लैंक के बाद बंद होगा")
                        else tr("Stops after $target ${JournalWriter.exerciseName(ex, Lang.EN)}", "$target ${JournalWriter.exerciseName(ex, Lang.HI)} के बाद बंद होगा"),
                    )
                } else {
                    Text(tr("Set a wake-up alarm", "सुबह का अलार्म लगाएं"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Muted(tr("It stops only after your exercise", "एक्सरसाइज़ के बाद ही बंद होगा"))
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatusDot(done: Boolean, partial: Boolean) {
    val cs = MaterialTheme.colorScheme
    when {
        done -> Box(Modifier.size(32.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
        }
        partial -> Box(Modifier.size(32.dp).border(2.dp, cs.primary, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(cs.primary))
        }
        else -> Box(Modifier.size(32.dp).border(2.dp, cs.outline, CircleShape))
    }
}

@Composable
private fun TodayRow(
    done: Boolean,
    partial: Boolean,
    title: String,
    sub: String,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StatusDot(done, partial)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Muted(sub)
        }
        if (action != null) SmallTextButton(action, onAction)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectDialog(subjects: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("What are you starting with?", "किससे शुरू कर रहे हैं?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Muted(tr("The voice check-ins will update this as you go.", "चेक-इन के दौरान यह अपने आप बदलता रहेगा।"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    subjects.ifEmpty { listOf("General") }.forEach { s ->
                        OptionTile(s, false, { onPick(s) }, minHeight = 44.dp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "रद्द करें")) } },
    )
}
