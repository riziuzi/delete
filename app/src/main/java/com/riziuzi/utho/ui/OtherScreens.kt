package com.riziuzi.utho.ui

import android.Manifest
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
        Text(tr("Group", "Group"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        CardBox(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(tr("Coming in the next version", "Agle version mein aayega"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Muted(
                tr(
                    "Study with a small group of up to 8 friends. Join with a code or a WhatsApp link.",
                    "8 doston tak ke chhote group ke saath padhein. Code ya WhatsApp link se judein.",
                ),
            )
        }
        CardBox {
            listOf(
                tr("See who is studying right now", "Dekhein abhi kaun padh raha hai"),
                tr("Today's hours and morning workout for each member", "Har member ke aaj ke ghante aur subah ki exercise"),
                tr("A gentle nudge button", "Ek halka 'yaad dilao' button"),
                tr("A weekly goal you reach together", "Saath milkar poora karne wala hafte ka target"),
                tr("No rankings, just who is showing up", "Koi ranking nahi, bas kaun aa raha hai"),
            ).forEach { Text("•  $it", fontSize = 15.sp) }
        }
        Muted(tr("Everything else works without an account.", "Baaki sab bina account ke chalta hai."))
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
        title = tr("Profile", "Profile"),
        onBack = { nav.back() },
        actions = {
            IconButton(onClick = { nav.go(Route.Settings) }, modifier = Modifier.testTag("openSettings")) {
                Icon(Icons.Outlined.Tune, contentDescription = tr("Settings", "Settings"))
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(name, 72.dp)
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { tr("Your name", "Aapka naam") }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Muted(exam.name)
            }
            SecondButton(tr("Edit", "Badlein"), { editName = true }, Modifier.size(width = 96.dp, height = 48.dp))
        }

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(JournalWriter.duration(totalMin, lang), tr("studied in total", "kul padhai"), Modifier.weight(1f).fillMaxHeight())
            StatTile("$reps", tr("reps done", "reps kiye"), Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("$mornings", tr("mornings up on time", "time par uthe"), Modifier.weight(1f).fillMaxHeight())
            StatTile(tr("$streak days", "$streak din"), tr("current run", "lagatar"), Modifier.weight(1f).fillMaxHeight())
        }

        CardBox {
            NavRow(tr("Exam", "Exam"), exam.name, { pickExam = true })
            Divider()
            NavRow(
                tr("Exam date", "Exam ki date"),
                if (examDate >= 0) LocalDate.ofEpochDay(examDate).format(dateFormatter(lang, "d MMM yyyy")) else tr("Not set", "Set nahi"),
                { pickDate = true },
            )
            Divider()
            NavRow(tr("Daily study goal", "Roz ka target"), JournalWriter.duration(goal, lang), { editGoal = true })
        }

        SectionLabel(tr("My subjects", "Mere subjects"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            subjects.forEach { s ->
                InputChip(
                    selected = false, onClick = { prefs.subjects = subjects - s },
                    label = { Text(s, fontSize = 14.sp) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = tr("Remove", "Hatayein"), Modifier.size(16.dp)) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
            InputChip(
                selected = false, onClick = { addSubject = true },
                label = { Text(tr("Add", "Jodein"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
        Muted(tr("Tap a subject to remove it.", "Hatane ke liye subject par tap karein."))
    }

    if (editName) {
        TextDialog(tr("Your name", "Aapka naam"), name, { editName = false }) {
            prefs.name = it
            editName = false
        }
    }
    if (addSubject) {
        TextDialog(tr("New subject", "Naya subject"), "", { addSubject = false }) {
            if (it.isNotBlank()) prefs.subjects = subjects + it.trim()
            addSubject = false
        }
    }
    if (pickExam) {
        AlertDialog(
            onDismissRequest = { pickExam = false },
            title = { Text(tr("Exam", "Exam")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Exams.all.forEach { e ->
                        OptionTile(e.name, e.key == exam.key, {
                            prefs.exam = e.key
                            pickExam = false
                        }, Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickExam = false }) { Text(tr("Cancel", "Cancel")) } },
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
                }) { Text(tr("OK", "OK")) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(tr("Cancel", "Cancel")) } },
        ) { DatePicker(state = state) }
    }
    if (editGoal) {
        DurationDialog(
            tr("Daily study goal", "Roz ka target"), goal * 60, hours = true, minSec = 15 * 60, maxSec = 16 * 3600,
            note = null, onDismiss = { editGoal = false }, seconds = false,
        ) {
            prefs.dailyGoalMin = it / 60
            editGoal = false
        }
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
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(tr("Save", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "Cancel")) } },
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
            exported = if (ok) lang.t2("Backup saved.", "Backup save ho gaya.") else lang.t2("Couldn't save the backup.", "Backup save nahi ho saka.")
        }
    }

    ScreenScaffold(title = tr("Settings", "Settings"), onBack = { nav.back() }) {
        SectionLabel(tr("General", "General"))
        CardBox {
            Text(tr("Language", "Bhasha"), fontSize = 16.sp)
            Segmented(listOf(Lang.EN to "English", Lang.HI to "Hinglish"), lang, { prefs.lang = it }, Modifier.fillMaxWidth().testTag("langSetting"))
            Divider()
            Text(tr("Theme", "Theme"), fontSize = 16.sp)
            Segmented(
                listOf("light" to tr("Light", "Light"), "dark" to tr("Dark", "Dark"), "system" to tr("Phone", "Phone jaisa")),
                theme, { prefs.theme = it }, Modifier.fillMaxWidth(),
            )
        }

        SectionLabel(tr("Voice check-ins", "Voice check-ins"))
        CardBox {
            CheckInTiming()
            Divider()
            Text(tr("I speak in", "Main bolta/bolti hoon"), fontSize = 16.sp)
            Segmented(
                listOf(VoiceLang.MIX to tr("Both", "Dono"), VoiceLang.HINDI to tr("Hindi", "Hindi"), VoiceLang.ENGLISH to tr("English", "English")),
                voice, { prefs.voiceLang = it }, Modifier.fillMaxWidth(),
            )
            Muted(tr("Both = Hindi and English mixed in one sentence.", "Dono = ek hi sentence mein Hindi aur English."))
            Divider()
            Box(Modifier.testTag("openPopupSetup")) {
                NavRow(
                    tr("Pop-up opens by itself", "Pop-up apne aap khule"),
                    if (popupReady(ctx)) tr("Ready", "Ready") else tr("Set up", "Set up karein"), { nav.go(Route.PopupSetup) },
                )
            }
            Divider()
            CheckInSound()
            Divider()
            NavRow(tr("What Utho has learned", "Utho ne kya seekha"), null, { nav.go(Route.Learned) })
            Divider()
            OfflineVoice()
        }

        SectionLabel(tr("Alarm", "Alarm"))
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Beep on each rep", "Har rep par beep"), fontSize = 16.sp, modifier = Modifier.weight(1f))
                Muted(if (beep < 1f) tr("Off", "Off") else "${beep.toInt()}%")
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
            Text(tr("Emergency stop: hold for", "Emergency stop: itni der dabayein"), fontSize = 16.sp)
            Segmented(listOf(10 to "10s", 20 to "20s", 30 to "30s", 60 to "60s"), emergency, { prefs.emergencySec = it }, Modifier.fillMaxWidth())
            Divider()
            NavRow(tr("Let alarms work in battery saver", "Battery saver mein bhi alarm chale"), null, {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
            if (Build.VERSION.SDK_INT >= 34) {
                Divider()
                NavRow(tr("Show alarm over lock screen", "Lock screen par alarm dikhayein"), if (needsFullScreenPermission(ctx)) tr("Off", "Off") else tr("On", "On"), {
                    openFullScreenSettings(ctx)
                })
            }
        }

        SectionLabel(tr("Reminders", "Reminders"))
        CardBox {
            ToggleRow(
                tr("Study reminder", "Padhai reminder"),
                tr("If not started by ", "Agar ") + clockText(studyMin / 60, studyMin % 60) + tr("", " tak shuru na ho"),
                studyOn,
            ) {
                prefs.studyReminderOn = it
                AlarmScheduler.scheduleReminders(ctx)
            }
            if (studyOn) SmallTextButton(tr("Change time", "Time badlein")) { pickStudyTime = true }
            Divider()
            ToggleRow(
                tr("Journal reminder", "Journal reminder"),
                clockText(journalMin / 60, journalMin % 60) + tr(", to add your mood", ", mood batane ke liye"),
                journalOn,
            ) {
                prefs.journalReminderOn = it
                AlarmScheduler.scheduleReminders(ctx)
            }
            if (journalOn) SmallTextButton(tr("Change time", "Time badlein")) { pickJournalTime = true }
        }

        SectionLabel(tr("Your data", "Aapka data"))
        CardBox {
            NavRow(tr("Back up to a file", "File mein backup"), null, { exporter.launch("utho-backup.json") })
            exported?.let { Muted(it) }
            Divider()
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().testTag("deleteAll")) {
                Text(tr("Delete all my data", "Mera saara data delete karein"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        }
        Muted(
            tr("Everything stays on this phone. Camera video and voice audio are never saved.", "Sab kuch isi phone par rehta hai. Camera video aur awaaz kabhi save nahi hoti."),
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
            title = { Text(tr("Delete everything?", "Sab kuch delete karein?")) },
            text = { Text(tr("Study history, workouts, journal and settings will be removed from this phone. This can't be undone.", "Padhai, exercise, journal aur settings is phone se hat jaayengi. Yeh wapas nahi hoga.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    endStudy(ctx)
                    Store.get(ctx).clearAll()
                    prefs.clearAll()
                    AlarmScheduler.rescheduleAll(ctx)
                    nav.reset(Route.Setup)
                }) { Text(tr("Delete", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "Cancel")) } },
        )
    }
}

/**
 * Voice without Google's sounds: Utho records with its own mic and an on-device model turns it into text,
 * so the phone's speech service never opens the mic. Shows whether that model is on the phone (with a
 * one-time download), the offline packs Android's own recogniser can use instead, and the opt-in for
 * Google voice when neither is there.
 */
@Composable
private fun OfflineVoice() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val onDevice = remember { VoiceRecognizer.onDeviceAvailable(ctx) }
    var google by remember { mutableStateOf(prefs.googleVoice) }
    Text(tr("Voice without Google sounds", "Google ki awaaz ke bina voice"), fontSize = 16.sp)
    Muted(
        tr(
            "Utho records with its own mic and the phone turns it into text offline, so Google's mic beep never plays.",
            "Utho apne mic se record karta hai aur phone offline text banata hai, isliye Google ki mic beep kabhi nahi bajti.",
        ),
    )
    if (Build.VERSION.SDK_INT >= 31) {
        SilentModelRow("Hindi + Hinglish", VoiceLang.HINDI, "silent-hi")
        SilentModelRow("English", VoiceLang.ENGLISH, "silent-en")
    }
    if (Build.VERSION.SDK_INT >= 33 && onDevice) {
        Muted(tr("Backup: Android's offline packs", "Backup: Android ke offline pack"))
        PackRow("Hindi", "hi-IN")
        PackRow("English (India)", "en-IN")
    }
    if (Build.VERSION.SDK_INT < 31) {
        Muted(
            tr(
                "This Android version has no silent voice. Turn on Google voice below, or type your check-ins.",
                "Is Android version mein silent voice nahi hai. Neeche Google voice on karein, ya check-in type karein.",
            ),
        )
    }
    Box(Modifier.testTag("googleVoice")) {
        ToggleRow(
            tr("Use Google voice if silent voice isn't ready", "Silent voice ready na ho to Google voice use karein"),
            tr("Google plays its mic sound then.", "Tab Google ki mic awaaz bajegi."),
            google,
        ) {
            google = it
            prefs.googleVoice = it
        }
    }
}

private enum class SilentUi { CHECKING, READY, DOWNLOADABLE, DOWNLOADING, FAILED, UNAVAILABLE }

/** ML Kit's on-device speech model for one language: ✓ Ready, Download (with progress), or not on this phone. */
@Composable
private fun SilentModelRow(name: String, lang: VoiceLang, tag: String) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf(SilentUi.CHECKING) }
    var percent by remember { mutableStateOf<Int?>(null) }
    var check by remember { mutableIntStateOf(0) }
    LaunchedEffect(check) {
        val st = com.riziuzi.utho.voice.SilentVoice.mlKitStatus(lang)
        ui = when (st) {
            com.google.mlkit.genai.common.FeatureStatus.AVAILABLE -> SilentUi.READY
            com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE -> if (ui == SilentUi.DOWNLOADING || ui == SilentUi.FAILED) ui else SilentUi.DOWNLOADABLE
            com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING -> SilentUi.DOWNLOADING
            else -> SilentUi.UNAVAILABLE
        }
    }
    LaunchedEffect(ui) {
        if (ui == SilentUi.DOWNLOADING) {
            kotlinx.coroutines.delay(4000)
            check++
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 15.sp, modifier = Modifier.weight(1f))
        when (ui) {
            SilentUi.CHECKING -> Muted("…")
            SilentUi.READY -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val green = if (cs.background.luminance() < 0.5f) androidx.compose.ui.graphics.Color(0xFF81C784) else androidx.compose.ui.graphics.Color(0xFF2E7D32)
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = green, modifier = Modifier.size(20.dp))
                Text(tr("Ready", "Ready"), color = green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            SilentUi.DOWNLOADABLE, SilentUi.FAILED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui == SilentUi.FAILED) Text(tr("Didn't finish", "Poora nahi hua"), color = cs.error, fontSize = 13.sp)
                OutlinedButton(
                    onClick = {
                        ui = SilentUi.DOWNLOADING
                        percent = null
                        com.riziuzi.utho.voice.SilentVoice.download(lang, scope, { percent = it }) { ok ->
                            ui = if (ok) SilentUi.READY else SilentUi.FAILED
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 36.dp).testTag("$tag-download"),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" " + tr(if (ui == SilentUi.FAILED) "Retry" else "Download", if (ui == SilentUi.FAILED) "Phir try" else "Download"), fontSize = 14.sp)
                }
            }
            SilentUi.DOWNLOADING -> Column(Modifier.width(130.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val p = percent
                if (p != null) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Muted(if (p != null) "$p%" else tr("Downloading…", "Download ho raha hai…"))
            }
            SilentUi.UNAVAILABLE -> Muted(tr("Not on this phone", "Is phone mein nahi"))
        }
    }
}

private enum class PackUi { CHECKING, INSTALLED, NOT_INSTALLED, DOWNLOADING, WAITING, FAILED, UNAVAILABLE }

/** One voice pack: ✓ Installed, a Download button, or a progress bar while it downloads. */
@androidx.annotation.RequiresApi(33)
@Composable
private fun PackRow(name: String, tag: String) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var ui by remember { mutableStateOf(PackUi.CHECKING) }
    var percent by remember { mutableStateOf<Int?>(null) }
    var check by remember { mutableIntStateOf(0) }

    LaunchedEffect(check) {
        VoiceRecognizer.packStatus(ctx, tag) { st ->
            if (st == VoiceRecognizer.Companion.Pack.INSTALLED) VoiceRecognizer.retryOnDevice(ctx)
            ui = when (st) {
                VoiceRecognizer.Companion.Pack.INSTALLED -> PackUi.INSTALLED
                VoiceRecognizer.Companion.Pack.DOWNLOADING -> if (ui == PackUi.WAITING) PackUi.WAITING else PackUi.DOWNLOADING
                VoiceRecognizer.Companion.Pack.NOT_INSTALLED -> if (ui == PackUi.DOWNLOADING || ui == PackUi.WAITING || ui == PackUi.FAILED) ui else PackUi.NOT_INSTALLED
                VoiceRecognizer.Companion.Pack.UNAVAILABLE -> PackUi.UNAVAILABLE
                null -> if (ui == PackUi.CHECKING) PackUi.UNAVAILABLE else ui
            }
        }
    }
    // While it downloads, look again every few seconds until it's installed.
    LaunchedEffect(ui) {
        if (ui == PackUi.DOWNLOADING || ui == PackUi.WAITING) {
            kotlinx.coroutines.delay(3000)
            check++
        }
    }

    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pack-$tag"), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 15.sp, modifier = Modifier.weight(1f))
        when (ui) {
            PackUi.CHECKING -> Muted("…")
            PackUi.INSTALLED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val green = if (cs.background.luminance() < 0.5f) androidx.compose.ui.graphics.Color(0xFF81C784) else androidx.compose.ui.graphics.Color(0xFF2E7D32)
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = green, modifier = Modifier.size(20.dp))
                Text(tr("Installed", "Installed"), color = green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            PackUi.NOT_INSTALLED, PackUi.FAILED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui == PackUi.FAILED) Text(tr("Didn't finish", "Poora nahi hua"), color = cs.error, fontSize = 13.sp)
                OutlinedButton(
                    onClick = {
                        ui = PackUi.DOWNLOADING
                        percent = null
                        VoiceRecognizer.downloadPack(ctx, tag, object : VoiceRecognizer.Companion.DownloadListener {
                            override fun onProgress(percent: Int) = setPercent(percent)
                            override fun onScheduled() = setUi(PackUi.WAITING)
                            override fun onDone() = setUi(PackUi.INSTALLED)
                            override fun onFailed() = setUi(PackUi.FAILED)
                            private fun setPercent(p: Int) {
                                percent = p
                                if (p >= 100) ui = PackUi.INSTALLED
                            }
                            private fun setUi(v: PackUi) {
                                ui = v
                            }
                        })
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 36.dp).testTag("download-$tag"),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" " + tr(if (ui == PackUi.FAILED) "Retry" else "Download", if (ui == PackUi.FAILED) "Phir try" else "Download"), fontSize = 14.sp)
                }
            }
            PackUi.DOWNLOADING -> Column(Modifier.width(130.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val p = percent
                if (p != null) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Muted(if (p != null) "$p%" else tr("Downloading…", "Download ho raha hai…"))
            }
            PackUi.WAITING -> Muted(tr("Waiting for Wi-Fi", "Wi-Fi ka intezaar"))
            PackUi.UNAVAILABLE -> Muted(tr("Not offered offline", "Offline nahi milta"))
        }
    }
}

/** Pick the sound for "mic on" / "mic off"; tapping one plays it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInSound() {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    var chosen by remember { mutableStateOf(prefs.checkInSound) }
    Text(tr("Sound when the mic opens and closes", "Mic khulne aur band hone ki awaaz"), fontSize = 16.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.riziuzi.utho.voice.Earcons.options.forEach { key ->
            OptionTile(com.riziuzi.utho.voice.Earcons.label(key, lang), key == chosen, {
                chosen = key
                prefs.checkInSound = key
                com.riziuzi.utho.voice.Earcons.preview(ctx, key)
            }, minHeight = 40.dp, modifier = Modifier.testTag("sound-$key"))
        }
    }
    Muted(tr("Tap to hear it. Plays once when listening starts and once when it stops.", "Tap karke sunein. Sunna shuru hone par ek baar aur band hone par ek baar bajegi."))
}

/** The words Utho picked up from your notes, per subject. Tap a word to drop it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LearnedScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val store = remember { Store.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    val learned = remember(tick) { com.riziuzi.utho.core.Learning.learnedWords(store.wordCounts) }
    val examples = remember(tick) { store.examples }
    ScreenScaffold(title = tr("What Utho has learned", "Utho ne kya seekha"), onBack = { nav.back() }) {
        Muted(
            tr(
                "Subjects are picked in three ways: built-in words for each subject, a small AI on your phone that understands meaning, " +
                    "and the words below, learned from notes you saved or fixed. A word is added once it shows up at least twice, mostly in one subject.",
                "Subject teen tarah se chuna jaata hai: har subject ke built-in words, phone par chalne wala chhota AI jo matlab samajhta hai, " +
                    "aur neeche wale words jo aapke save ya theek kiye notes se seekhe gaye. Koi word do baar, zyada tar ek hi subject mein aaye to jud jaata hai.",
            ),
        )
        prefs.subjects.forEach { subject ->
            val words = learned[subject] ?: emptyList()
            val notes = examples[subject]?.size ?: 0
            CardBox(modifier = Modifier.testTag("learned-$subject")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subject, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Muted(if (notes == 1) "1 note" else "$notes notes")
                }
                if (words.isEmpty()) {
                    Muted(tr("Nothing learned yet.", "Abhi kuch nahi seekha."))
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        words.forEach { w ->
                            InputChip(
                                selected = false, onClick = { store.forgetWord(subject, w) }, label = { Text(w) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = tr("Remove", "Hatayein"), Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                if (notes > 0 || words.isNotEmpty()) SmallTextButton(tr("Forget this subject", "Yeh subject bhool jao")) { store.forgetTopic(subject) }
            }
        }
    }
}

/** How often the check-in pop-up comes while studying (hour/minute/second dial), and how long it listens. */
@Composable
fun CheckInTiming() {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    var on by remember { mutableStateOf(prefs.checkInOn) }
    var every by remember { mutableIntStateOf(prefs.checkInSec) }
    var listen by remember { mutableIntStateOf(prefs.listenSeconds) }
    var pickEvery by remember { mutableStateOf(false) }
    var pickListen by remember { mutableStateOf(false) }
    ToggleRow(tr("Check-ins while studying", "Padhai ke dauraan check-in"), null, on) {
        on = it
        prefs.checkInOn = it
        AlarmScheduler.scheduleNextCheckIn(ctx)
    }
    if (on) {
        Box(Modifier.testTag("checkInEvery")) {
            NavRow(tr("Ask me every", "Kitni der mein poochhein"), com.riziuzi.utho.core.JournalWriter.span(every, lang), { pickEvery = true })
        }
        Box(Modifier.testTag("checkInListen")) {
            NavRow(tr("The pop-up listens for", "Pop-up kitni der sunega"), com.riziuzi.utho.core.JournalWriter.span(listen, lang), { pickListen = true })
        }
    }
    Muted(tr("Notes you start yourself (Speak) keep listening until you tap Done.", "Jo note aap khud shuru karte hain (Bolein), woh Done dabane tak sunta rahega."))
    if (pickEvery) {
        DurationDialog(
            tr("Ask me every", "Kitni der mein poochhein"), every, hours = true, minSec = 60, maxSec = 23 * 3600 + 59 * 60 + 59,
            note = null, onDismiss = { pickEvery = false },
        ) {
            every = it
            prefs.checkInSec = it
            if (listen > it / 2) {
                listen = (it / 2).coerceAtLeast(5)
                prefs.listenSeconds = listen
            }
            AlarmScheduler.scheduleNextCheckIn(ctx)
            pickEvery = false
        }
    }
    if (pickListen) {
        DurationDialog(
            tr("The pop-up listens for", "Pop-up kitni der sunega"), listen, hours = false, minSec = 5, maxSec = minOf(5 * 60, every / 2),
            note = tr("If you stop talking, it finishes early.", "Bolna band karenge to pehle hi ruk jaayega."), onDismiss = { pickListen = false },
        ) {
            listen = it
            prefs.listenSeconds = it
            pickListen = false
        }
    }
}
