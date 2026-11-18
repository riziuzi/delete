package com.riziuzi.utho.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.riziuzi.utho.core.Exercise
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.data.Exams
import com.riziuzi.utho.data.Prefs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

fun dateFormatter(lang: Lang, pattern: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(pattern, if (lang == Lang.HI) Locale("hi", "IN") else Locale.ENGLISH)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val lang = LocalLang.current
    var step by remember { mutableIntStateOf(1) }

    var name by remember { mutableStateOf(prefs.name) }
    var examKey by remember { mutableStateOf(prefs.exam) }
    var examDate by remember { mutableLongStateOf(prefs.examDate) }
    var subjects by remember { mutableStateOf(prefs.subjects) }
    var subjectsTouched by remember { mutableStateOf(prefs.subjects.isNotEmpty()) }
    var goalHours by remember { mutableIntStateOf(prefs.dailyGoalMin / 60) }
    var alarmOn by remember { mutableStateOf(true) }
    var hour by remember { mutableIntStateOf(prefs.alarmHour) }
    var minute by remember { mutableIntStateOf(prefs.alarmMinute) }
    var exercise by remember { mutableStateOf(prefs.exercise) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var newSubject by remember { mutableStateOf("") }

    fun defaultSubjects(): List<String> {
        val e = Exams.byKey(examKey)
        return if (lang == Lang.HI) e.subjectsHi else e.subjectsEn
    }

    fun currentSubjects(): List<String> = if (subjectsTouched) subjects else defaultSubjects()

    fun finish() {
        prefs.name = name
        prefs.exam = examKey
        prefs.examDate = examDate
        prefs.subjects = currentSubjects()
        prefs.dailyGoalMin = goalHours * 60
        prefs.alarmEnabled = alarmOn
        prefs.alarmHour = hour
        prefs.alarmMinute = minute
        prefs.exercise = exercise
        prefs.alarmTarget = if (exercise.isHold) 60 else 40
        prefs.setupDone = true
        AlarmScheduler.scheduleWake(ctx)
        AlarmScheduler.scheduleReminders(ctx)
        onDone()
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }

    fun requestThenFinish() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) finish() else permLauncher.launch(missing.toTypedArray())
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                for (i in 1..3) {
                    Box(
                        Modifier.width(28.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    )
                }
            }
            Segmented(
                listOf(Lang.EN to "English", Lang.HI to "हिंदी"), lang,
                { l -> prefs.lang = l },
                Modifier.width(180.dp),
            )
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Utho · उठो", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            when (step) {
                1 -> {
                    Text(
                        tr("Which exam are you preparing for?", "आप किस परीक्षा की तैयारी कर रहे हैं?"),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(tr("Your name", "आपका नाम")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("nameField"),
                    )
                    val exams = Exams.all
                    exams.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { e ->
                                OptionTile(
                                    text = if (lang == Lang.HI) e.hi else e.en,
                                    selected = e.key == examKey,
                                    onClick = { examKey = e.key },
                                    modifier = Modifier.weight(1f),
                                    minHeight = 56.dp,
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text(tr("Exam date (optional)", "परीक्षा की तारीख (ज़रूरी नहीं)"), fontWeight = FontWeight.SemiBold)
                    SecondButton(
                        if (examDate >= 0) LocalDate.ofEpochDay(examDate).format(dateFormatter(lang, "d MMMM yyyy")) else tr("Pick a date", "तारीख चुनें"),
                        { showDate = true },
                    )
                }
                2 -> {
                    Text(tr("Your subjects", "आपके विषय"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Muted(
                        tr(
                            "When you say what you studied, the app files it under one of these.",
                            "आप जो पढ़ाई बताएंगे, ऐप उसे इनमें से किसी विषय में रखेगा।",
                        ),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        currentSubjects().forEach { s ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    subjects = currentSubjects() - s
                                    subjectsTouched = true
                                },
                                label = { Text(s, fontSize = 15.sp) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = tr("Remove", "हटाएं"), Modifier.size(18.dp)) },
                                modifier = Modifier.heightIn(min = 40.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newSubject, onValueChange = { newSubject = it },
                            label = { Text(tr("Add a subject", "विषय जोड़ें")) }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            if (newSubject.isNotBlank()) {
                                subjects = currentSubjects() + newSubject.trim()
                                subjectsTouched = true
                                newSubject = ""
                            }
                        }) { Icon(Icons.Filled.Add, contentDescription = tr("Add", "जोड़ें")) }
                    }
                    Text(tr("Daily study goal", "रोज़ का पढ़ाई लक्ष्य"), fontWeight = FontWeight.SemiBold)
                    Stepper("$goalHours " + tr("hours", "घंटे"), { goalHours = (goalHours - 1).coerceAtLeast(1) }, { goalHours = (goalHours + 1).coerceAtMost(16) })
                }
                3 -> {
                    Text(tr("Morning alarm", "सुबह का अलार्म"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Muted(
                        tr(
                            "It keeps ringing until the camera counts your exercise. You can change this any time.",
                            "जब तक कैमरा आपकी एक्सरसाइज़ नहीं गिनता, अलार्म बजता रहेगा। इसे कभी भी बदल सकते हैं।",
                        ),
                    )
                    CardBox {
                        ToggleRow(tr("Turn on wake-up alarm", "अलार्म चालू करें"), null, alarmOn) { alarmOn = it }
                        if (alarmOn) {
                            Divider()
                            NavRow(tr("Time", "समय"), clockText(hour, minute), { showTime = true })
                        }
                    }
                    if (alarmOn) {
                        Text(tr("To stop it, do", "बंद करने के लिए करें"), fontWeight = FontWeight.SemiBold)
                        ExerciseGrid(exercise) { exercise = it }
                    }
                    Muted(
                        tr(
                            "Next, Android will ask for camera (counts reps), microphone (voice journal) and notifications (alarm). Everything stays on this phone.",
                            "अब Android कैमरा (गिनती), माइक (आवाज़ से डायरी) और नोटिफिकेशन (अलार्म) की अनुमति मांगेगा। सब कुछ इसी फोन पर रहता है।",
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 1) SecondButton(tr("Back", "पीछे"), { step-- }, Modifier.weight(1f))
            BigButton(
                if (step < 3) tr("Continue", "आगे") else tr("Finish", "शुरू करें"),
                { if (step < 3) step++ else requestThenFinish() },
                Modifier.weight(1f),
            )
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = if (examDate >= 0) LocalDate.ofEpochDay(examDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else null,
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { examDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    showDate = false
                }) { Text(tr("OK", "ठीक है")) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(tr("Cancel", "रद्द करें")) } },
        ) { DatePicker(state = state) }
    }
    if (showTime) {
        TimeDialog(hour, minute, { showTime = false }) { h, m ->
            hour = h
            minute = m
            showTime = false
        }
    }
}

@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        androidx.compose.material3.OutlinedIconButton(onClick = onMinus, modifier = Modifier.size(48.dp)) {
            Text("−", fontSize = 22.sp)
        }
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(110.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        androidx.compose.material3.OutlinedIconButton(onClick = onPlus, modifier = Modifier.size(48.dp)) {
            Text("+", fontSize = 22.sp)
        }
    }
}

@Composable
fun exerciseLabel(e: Exercise): String = when (e) {
    Exercise.PUSHUP -> tr("Push-ups", "पुश-अप")
    Exercise.SQUAT -> tr("Squats", "स्क्वैट")
    Exercise.JUMPING_JACK -> tr("Jumping jacks", "जंपिंग जैक")
    Exercise.PLANK -> tr("Plank", "प्लैंक")
}

@Composable
fun ExerciseGrid(selected: Exercise, onPick: (Exercise) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Exercise.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { e ->
                    OptionTile(
                        text = exerciseLabel(e),
                        sub = if (e.isHold) tr("Hold for a set time", "तय समय तक रुकें") else tr("Camera counts reps", "कैमरा गिनता है"),
                        selected = e == selected,
                        onClick = { onPick(e) },
                        modifier = Modifier.weight(1f),
                        minHeight = 64.dp,
                    )
                }
            }
        }
    }
}
