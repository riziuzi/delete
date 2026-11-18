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
        var hours by remember { mutableIntStateOf(goal / 60) }
        AlertDialog(
            onDismissRequest = { editGoal = false },
            title = { Text(tr("Daily study goal", "Roz ka target")) },
            text = { Stepper("$hours " + tr("hours", "ghante"), { hours = (hours - 1).coerceAtLeast(1) }, { hours = (hours + 1).coerceAtMost(16) }) },
            confirmButton = {
                TextButton(onClick = {
                    prefs.dailyGoalMin = hours * 60
                    editGoal = false
                }) { Text(tr("Save", "Save")) }
            },
            dismissButton = { TextButton(onClick = { editGoal = false }) { Text(tr("Cancel", "Cancel")) } },
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
            Text(tr("While studying, ask me every", "Padhai ke dauraan har"), fontSize = 16.sp)
            Segmented(
                listOf(0 to tr("Off", "Off"), 15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m"),
                interval, {
                    prefs.checkInMin = it
                    AlarmScheduler.scheduleNextCheckIn(ctx)
                }, Modifier.fillMaxWidth(),
            )
            Divider()
            Text(tr("I speak in", "Main bolta/bolti hoon"), fontSize = 16.sp)
            Segmented(
                listOf(VoiceLang.MIX to tr("Both", "Dono"), VoiceLang.HINDI to tr("Hindi", "Hindi"), VoiceLang.ENGLISH to tr("English", "English")),
                voice, { prefs.voiceLang = it }, Modifier.fillMaxWidth(),
            )
            Muted(tr("Both = Hindi and English mixed in one sentence.", "Dono = ek hi sentence mein Hindi aur English."))
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

@Composable
private fun OfflineVoice() {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val onDevice = remember { VoiceRecognizer.onDeviceAvailable(ctx) }
    var status by remember { mutableStateOf<String?>(null) }
    var requested by remember { mutableStateOf(false) }
    Text(tr("Offline voice", "Offline voice"), fontSize = 16.sp)
    if (Build.VERSION.SDK_INT >= 33 && onDevice) {
        LaunchedEffect(requested) {
            VoiceRecognizer.checkSupport(ctx, VoiceLang.MIX) { s ->
                status = if (s == null) null else {
                    val hi = s.installed.any { it.startsWith("hi") }
                    val pending = s.pending.any { it.startsWith("hi") }
                    when {
                        hi -> lang.t2("Hindi offline voice: installed", "Hindi offline voice: installed hai")
                        pending -> lang.t2("Hindi offline voice: downloading…", "Hindi offline voice: download ho raha hai…")
                        else -> lang.t2("Hindi offline voice: not installed", "Hindi offline voice: install nahi hai")
                    }
                }
            }
        }
        status?.let { Muted(it) }
        ButtonsRow {
            SecondButton("Hindi pack", {
                VoiceRecognizer.downloadModel(ctx, "hi-IN")
                requested = !requested
            }, Modifier.weight(1f))
            SecondButton("English pack", {
                VoiceRecognizer.downloadModel(ctx, "en-IN")
                requested = !requested
            }, Modifier.weight(1f))
        }
        Muted(tr("Tap to download once on Wi-Fi, then check-ins work without internet.", "Ek baar Wi-Fi par tap karke download karein, phir bina internet chalega."))
    } else {
        Muted(
            tr(
                "Voice uses your phone's recogniser. For offline use, download Hindi in Google app › Settings › Voice › Offline speech recognition.",
                "Voice aapke phone ka system use karta hai. Offline ke liye Google app › Settings › Voice › Offline speech recognition mein Hindi download karein.",
            ),
        )
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
                    Muted(tr("$notes notes", "$notes notes"))
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
