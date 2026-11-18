package com.riziuzi.utho.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.riziuzi.utho.alarm.AlarmScheduler
import com.riziuzi.utho.alarm.Beeper
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.core.Punctuator
import com.riziuzi.utho.core.Stats
import com.riziuzi.utho.data.Exams
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import com.riziuzi.utho.data.VoiceLang
import com.riziuzi.utho.voice.VoiceRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------------------------------------------------------------- Group (phase 2)

@Composable
fun GroupScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(tr("Group", "ग्रुप"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        CardBox(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(tr("Coming in the next version", "अगले वर्ज़न में आएगा"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Muted(
                tr(
                    "Study with a small group of up to 8 friends. Join with a code or a WhatsApp link.",
                    "8 दोस्तों तक के छोटे ग्रुप के साथ पढ़ें। कोड या WhatsApp लिंक से जुड़ें।",
                ),
            )
        }
        CardBox {
            listOf(
                tr("See who is studying right now", "देखें अभी कौन पढ़ रहा है"),
                tr("Today's hours and morning workout for each member", "हर सदस्य के आज के घंटे और सुबह की एक्सरसाइज़"),
                tr("A gentle nudge button", "एक हल्का 'याद दिलाओ' बटन"),
                tr("A weekly goal you reach together", "साथ मिलकर पूरा करने वाला हफ्ते का लक्ष्य"),
                tr("No rankings, just who is showing up", "कोई रैंकिंग नहीं, बस कौन आ रहा है"),
            ).forEach { Text("•  $it", fontSize = 15.sp) }
        }
        Muted(tr("Everything else works without an account.", "बाकी सब बिना अकाउंट के चलता है।"))
    }
}

// ---------------------------------------------------------------- Profile

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val lang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { Store.get(ctx) }
    val name = remember(tick) { prefs.name }
    val subjects = remember(tick) { prefs.subjects }
    val exam = remember(tick) { Exams.byKey(prefs.exam) }
    val examDate = remember(tick) { prefs.examDate }
    val goal = remember(tick) { prefs.dailyGoalMin }
    val totalMin = remember(tick) { Summaries.minutesPerDay(ctx).values.sum() }
    val workouts = remember(tick) { store.workouts }
    val reps = workouts.filter { !it.exercise.isHold }.sumOf { it.done }
    val mornings = workouts.filter { it.viaAlarm && it.completed }.map { Summaries.dateOf(it.timeMs) }.toSet().size
    val streak = remember(tick) { Stats.streak(Summaries.activeDays(ctx), LocalDate.now(Summaries.zone)) }

    var editName by remember { mutableStateOf(false) }
    var pickExam by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf(false) }
    var addSubject by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = tr("Profile", "प्रोफ़ाइल"),
        onBack = { nav.back() },
        actions = {
            IconButton(onClick = { nav.go(Route.Settings) }, modifier = Modifier.testTag("openSettings")) {
                Icon(Icons.Outlined.Tune, contentDescription = tr("Settings", "सेटिंग्स"))
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(name, 72.dp)
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { tr("Your name", "आपका नाम") }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Muted(if (lang == Lang.HI) exam.hi else exam.en)
            }
            SecondButton(tr("Edit", "बदलें"), { editName = true }, Modifier.size(width = 96.dp, height = 48.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(JournalWriter.duration(totalMin, lang), tr("studied in total", "कुल पढ़ाई"), Modifier.weight(1f))
            StatTile("$reps", tr("reps done", "रेप्स किए"), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("$mornings", tr("mornings up on time", "समय पर उठे"), Modifier.weight(1f))
            StatTile(tr("$streak days", "$streak दिन"), tr("current run", "लगातार"), Modifier.weight(1f))
        }

        CardBox {
            NavRow(tr("Exam", "परीक्षा"), if (lang == Lang.HI) exam.hi else exam.en, { pickExam = true })
            Divider()
            NavRow(
                tr("Exam date", "परीक्षा की तारीख"),
                if (examDate >= 0) LocalDate.ofEpochDay(examDate).format(dateFormatter(lang, "d MMM yyyy")) else tr("Not set", "तय नहीं"),
                { pickDate = true },
            )
            Divider()
            NavRow(tr("Daily study goal", "रोज़ का लक्ष्य"), JournalWriter.duration(goal, lang), { editGoal = true })
        }

        SectionLabel(tr("My subjects", "मेरे विषय"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            subjects.forEach { s ->
                InputChip(
                    selected = false, onClick = { prefs.subjects = subjects - s },
                    label = { Text(s, fontSize = 14.sp) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = tr("Remove", "हटाएं"), Modifier.size(16.dp)) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
            InputChip(
                selected = false, onClick = { addSubject = true },
                label = { Text(tr("Add", "जोड़ें"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
        Muted(tr("Tap a subject to remove it.", "हटाने के लिए विषय पर टैप करें।"))
    }

    if (editName) {
        TextDialog(tr("Your name", "आपका नाम"), name, { editName = false }) {
            prefs.name = it
            editName = false
        }
    }
    if (addSubject) {
        TextDialog(tr("New subject", "नया विषय"), "", { addSubject = false }) {
            if (it.isNotBlank()) prefs.subjects = subjects + it.trim()
            addSubject = false
        }
    }
    if (pickExam) {
        AlertDialog(
            onDismissRequest = { pickExam = false },
            title = { Text(tr("Exam", "परीक्षा")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Exams.all.forEach { e ->
                        OptionTile(if (lang == Lang.HI) e.hi else e.en, e.key == exam.key, {
                            prefs.exam = e.key
                            pickExam = false
                        }, Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickExam = false }) { Text(tr("Cancel", "रद्द करें")) } },
        )
    }
    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = if (examDate >= 0) LocalDate.ofEpochDay(examDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else null,
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { prefs.examDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    pickDate = false
                }) { Text(tr("OK", "ठीक है")) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(tr("Cancel", "रद्द करें")) } },
        ) { DatePicker(state = state) }
    }
    if (editGoal) {
        var hours by remember { mutableIntStateOf(goal / 60) }
        AlertDialog(
            onDismissRequest = { editGoal = false },
            title = { Text(tr("Daily study goal", "रोज़ का लक्ष्य")) },
            text = { Stepper("$hours " + tr("hours", "घंटे"), { hours = (hours - 1).coerceAtLeast(1) }, { hours = (hours + 1).coerceAtMost(16) }) },
            confirmButton = {
                TextButton(onClick = {
                    prefs.dailyGoalMin = hours * 60
                    editGoal = false
                }) { Text(tr("Save", "सेव करें")) }
            },
            dismissButton = { TextButton(onClick = { editGoal = false }) { Text(tr("Cancel", "रद्द करें")) } },
        )
    }
}

@Composable
private fun StatTile(big: String, small: String, modifier: Modifier) {
    CardBox(modifier) {
        Text(big, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Muted(small)
    }
}

@Composable
fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(tr("Save", "सेव करें")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "रद्द करें")) } },
    )
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val prefs = remember { Prefs(ctx) }
    val lang = remember(tick) { prefs.lang }
    val theme = remember(tick) { prefs.theme }
    val interval = remember(tick) { prefs.checkInMin }
    val voice = remember(tick) { prefs.voiceLang }
    var beep by remember { mutableFloatStateOf(prefs.beepVolume.toFloat()) }
    val emergency = remember(tick) { prefs.emergencySec }
    val studyOn = remember(tick) { prefs.studyReminderOn }
    val studyMin = remember(tick) { prefs.studyReminderMin }
    val journalOn = remember(tick) { prefs.journalReminderOn }
    val journalMin = remember(tick) { prefs.journalReminderMin }
    var pickStudyTime by remember { mutableStateOf(false) }
    var pickJournalTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(Store.get(ctx).exportJson().toByteArray()) }
            }.isSuccess
            exported = if (ok) lang.t2("Backup saved.", "बैकअप सेव हो गया।") else lang.t2("Couldn't save the backup.", "बैकअप सेव नहीं हो सका।")
        }
    }

    ScreenScaffold(title = tr("Settings", "सेटिंग्स"), onBack = { nav.back() }) {
        SectionLabel(tr("General", "सामान्य"))
        CardBox {
            Text(tr("Language", "भाषा"), fontSize = 16.sp)
            Segmented(listOf(Lang.EN to "English", Lang.HI to "हिंदी"), lang, { prefs.lang = it }, Modifier.fillMaxWidth().testTag("langSetting"))
            Divider()
            Text(tr("Theme", "थीम"), fontSize = 16.sp)
            Segmented(
                listOf("light" to tr("Light", "लाइट"), "dark" to tr("Dark", "डार्क"), "system" to tr("Phone", "फोन जैसा")),
                theme, { prefs.theme = it }, Modifier.fillMaxWidth(),
            )
        }

        SectionLabel(tr("Voice check-ins", "आवाज़ चेक-इन"))
        CardBox {
            Text(tr("While studying, ask me every", "पढ़ाई के दौरान हर"), fontSize = 16.sp)
            Segmented(
                listOf(0 to tr("Off", "बंद"), 15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m"),
                interval, {
                    prefs.checkInMin = it
                    AlarmScheduler.scheduleNextCheckIn(ctx)
                }, Modifier.fillMaxWidth(),
            )
            Divider()
            Text(tr("I speak in", "मैं बोलता/बोलती हूँ"), fontSize = 16.sp)
            Segmented(
                listOf(VoiceLang.MIX to tr("Hindi + English", "हिंदी + अंग्रेज़ी"), VoiceLang.HINDI to tr("Hindi", "हिंदी"), VoiceLang.ENGLISH to tr("English", "अंग्रेज़ी")),
                voice, { prefs.voiceLang = it }, Modifier.fillMaxWidth(),
            )
            Divider()
            NavRow(tr("Voice test", "वॉइस टेस्ट"), null, { nav.go(Route.VoiceTest) })
        }

        SectionLabel(tr("Alarm", "अलार्म"))
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Beep on each rep", "हर रेप पर बीप"), fontSize = 16.sp, modifier = Modifier.weight(1f))
                Muted(if (beep < 1f) tr("Off", "बंद") else "${beep.toInt()}%")
            }
            Slider(
                value = beep, onValueChange = { beep = it }, valueRange = 0f..100f,
                onValueChangeFinished = {
                    prefs.beepVolume = beep.toInt()
                    scope.launch {
                        val b = Beeper(beep.toInt())
                        b.beep()
                        delay(400)
                        b.release()
                    }
                },
            )
            Divider()
            Text(tr("Emergency stop: hold for", "आपात स्टॉप: इतनी देर दबाएं"), fontSize = 16.sp)
            Segmented(listOf(10 to "10s", 20 to "20s", 30 to "30s", 60 to "60s"), emergency, { prefs.emergencySec = it }, Modifier.fillMaxWidth())
            Divider()
            NavRow(tr("Let alarms work in battery saver", "बैटरी सेवर में भी अलार्म चलें"), null, {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
            if (Build.VERSION.SDK_INT >= 34) {
                Divider()
                NavRow(tr("Show alarm over lock screen", "लॉक स्क्रीन पर अलार्म दिखाएं"), if (needsFullScreenPermission(ctx)) tr("Off", "बंद") else tr("On", "चालू"), {
                    openFullScreenSettings(ctx)
                })
            }
        }

        SectionLabel(tr("Reminders", "रिमाइंडर"))
        CardBox {
            ToggleRow(
                tr("Study reminder", "पढ़ाई रिमाइंडर"),
                tr("If not started by ", "अगर ") + clockText(studyMin / 60, studyMin % 60) + tr("", " तक शुरू न हो"),
                studyOn,
            ) {
                prefs.studyReminderOn = it
                AlarmScheduler.scheduleReminders(ctx)
            }
            if (studyOn) SmallTextButton(tr("Change time", "समय बदलें")) { pickStudyTime = true }
            Divider()
            ToggleRow(
                tr("Journal reminder", "डायरी रिमाइंडर"),
                clockText(journalMin / 60, journalMin % 60) + tr(", to add your mood", ", मूड जोड़ने के लिए"),
                journalOn,
            ) {
                prefs.journalReminderOn = it
                AlarmScheduler.scheduleReminders(ctx)
            }
            if (journalOn) SmallTextButton(tr("Change time", "समय बदलें")) { pickJournalTime = true }
        }

        SectionLabel(tr("Your data", "आपका डेटा"))
        CardBox {
            NavRow(tr("Back up to a file", "फाइल में बैकअप"), null, { exporter.launch("utho-backup.json") })
            exported?.let { Muted(it) }
            Divider()
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().testTag("deleteAll")) {
                Text(tr("Delete all my data", "मेरा सारा डेटा मिटाएं"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        }
        Muted(
            tr("Everything stays on this phone. Camera video and voice audio are never saved.", "सब कुछ इसी फोन पर रहता है। कैमरा वीडियो और आवाज़ कभी सेव नहीं होती।"),
            Modifier.padding(bottom = 24.dp),
        )
    }

    if (pickStudyTime) {
        TimeDialog(studyMin / 60, studyMin % 60, { pickStudyTime = false }) { h, m ->
            prefs.studyReminderMin = h * 60 + m
            AlarmScheduler.scheduleReminders(ctx)
            pickStudyTime = false
        }
    }
    if (pickJournalTime) {
        TimeDialog(journalMin / 60, journalMin % 60, { pickJournalTime = false }) { h, m ->
            prefs.journalReminderMin = h * 60 + m
            AlarmScheduler.scheduleReminders(ctx)
            pickJournalTime = false
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete everything?", "सब कुछ मिटाएं?")) },
            text = { Text(tr("Study history, workouts, journal and settings will be removed from this phone. This can't be undone.", "पढ़ाई, एक्सरसाइज़, डायरी और सेटिंग्स इस फोन से हट जाएंगी। यह वापस नहीं होगा।")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    endStudy(ctx)
                    Store.get(ctx).clearAll()
                    prefs.clearAll()
                    AlarmScheduler.rescheduleAll(ctx)
                    nav.reset(Route.Setup)
                }) { Text(tr("Delete", "मिटाएं"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "रद्द करें")) } },
        )
    }
}

// ---------------------------------------------------------------- Voice test

@Composable
fun VoiceTestScreen(nav: Nav) {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    val onDevice = remember { VoiceRecognizer.onDeviceAvailable(ctx) }
    val anyRec = remember { VoiceRecognizer.anyAvailable(ctx) }
    var support by remember { mutableStateOf<String?>(null) }
    var engine by remember { mutableStateOf<VoiceRecognizer.Engine?>(null) }
    var listening by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var partial by remember { mutableStateOf("") }
    val segments = remember { mutableStateListOf<String>() }
    var result by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var ranking by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }

    val recognizer = remember {
        VoiceRecognizer(ctx, prefs.voiceLang, object : VoiceRecognizer.Listener {
            override fun onReady(engine0: VoiceRecognizer.Engine) {
                engine = engine0
            }
            override fun onPartial(text: String) {
                partial = text
            }
            override fun onSegment(text: String) {
                segments += text
                partial = ""
            }
            override fun onProblem(problem0: VoiceRecognizer.Problem, code: Int) {
                problem = problemText(problem0, lang) + " (code $code)"
            }
            override fun onFinished() {
                listening = false
                val text = Punctuator.paragraph(segments.toList())
                result = text
                val r = Summaries.classifier(ctx).classify(text)
                ranking = r.ranked.take(3).map { it.topic to it.score }
            }
        })
    }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }

    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        secondsLeft = 15
        while (secondsLeft > 0 && listening) {
            delay(1000)
            secondsLeft--
        }
        if (listening) recognizer.stop()
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            listening = true
            recognizer.start()
        }
    }

    ScreenScaffold(title = tr("Voice test", "वॉइस टेस्ट"), onBack = { nav.back() }) {
        CardBox {
            Text(tr("This phone", "यह फोन"), fontWeight = FontWeight.SemiBold)
            Muted("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            Muted(tr("Offline recogniser: ", "ऑफलाइन पहचान: ") + if (onDevice) tr("available", "उपलब्ध") else tr("not available", "उपलब्ध नहीं"))
            Muted(tr("Any recogniser: ", "कोई भी पहचान: ") + if (anyRec) tr("available", "उपलब्ध") else tr("not available", "उपलब्ध नहीं"))
            if (Build.VERSION.SDK_INT >= 33 && onDevice) {
                ButtonsRow {
                    SecondButton(tr("Check languages", "भाषाएं जांचें"), {
                        VoiceRecognizer.checkSupport(ctx, prefs.voiceLang) { s ->
                            support = if (s == null) lang.t2("Couldn't read language info.", "भाषा जानकारी नहीं मिली।")
                            else "Installed: ${s.installed.joinToString()}\nDownloading: ${s.pending.joinToString()}\nCan download: ${s.supported.filter { it.startsWith("hi") || it.startsWith("en-IN") }.joinToString()}"
                        }
                    }, Modifier.weight(1f))
                }
                ButtonsRow {
                    SecondButton(tr("Get Hindi pack", "हिंदी पैक लें"), { VoiceRecognizer.downloadModel(ctx, "hi-IN") }, Modifier.weight(1f))
                    SecondButton(tr("Get English pack", "अंग्रेज़ी पैक लें"), { VoiceRecognizer.downloadModel(ctx, "en-IN") }, Modifier.weight(1f))
                }
                support?.let { Text(it, fontSize = 13.sp) }
            }
        }

        CardBox {
            Text(tr("Say a sentence about what you studied", "बोलिए आपने क्या पढ़ा"), fontWeight = FontWeight.SemiBold)
            BigButton(
                if (listening) tr("Listening… ${secondsLeft}s (tap to stop)", "सुन रहे हैं… ${secondsLeft}s (रोकें)") else tr("Start 15-second test", "15 सेकंड टेस्ट शुरू करें"),
                {
                    if (listening) {
                        recognizer.stop()
                    } else {
                        segments.clear()
                        result = null
                        problem = null
                        ranking = emptyList()
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            listening = true
                            recognizer.start()
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                Modifier.testTag("voiceTestButton"),
            )
            engine?.let {
                Muted(
                    tr("Engine: ", "इंजन: ") + when (it) {
                        VoiceRecognizer.Engine.ON_DEVICE -> tr("on-device (offline)", "फोन पर (ऑफलाइन)")
                        VoiceRecognizer.Engine.SYSTEM -> tr("phone's recogniser (may use internet)", "फोन का सिस्टम (इंटरनेट लग सकता है)")
                        VoiceRecognizer.Engine.NONE -> tr("none", "कोई नहीं")
                    },
                )
            }
            if (partial.isNotBlank()) Muted(partial)
            if (segments.isNotEmpty()) {
                Text(tr("Raw text", "कच्चा टेक्स्ट"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                segments.forEach { Text("• $it", fontSize = 14.sp) }
            }
            result?.let {
                Text(tr("With punctuation", "विराम चिह्न के साथ"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(it.ifBlank { tr("(nothing heard)", "(कुछ नहीं सुना)") }, fontSize = 16.sp)
            }
            if (ranking.isNotEmpty()) {
                Text(tr("Topic guess", "विषय का अनुमान"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                ranking.forEach { (t, s) -> Text("$t  ·  %.1f".format(s), fontSize = 14.sp) }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        }
        Muted(
            tr(
                "Tip: if the engine says it may use the internet, tap \"Get Hindi pack\" once on Wi-Fi so it works offline.",
                "सुझाव: अगर इंजन इंटरनेट वाला दिखे, तो एक बार Wi-Fi पर \"हिंदी पैक लें\" दबाएं ताकि ऑफलाइन चले।",
            ),
        )
    }
}
