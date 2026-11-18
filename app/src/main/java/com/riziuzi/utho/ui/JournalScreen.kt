package com.riziuzi.utho.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.riziuzi.utho.core.DayNote
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.core.Punctuator
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import com.riziuzi.utho.voice.VoiceRecognizer
import java.time.DayOfWeek
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(nav: Nav, date: LocalDate) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val appLang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { Store.get(ctx) }
    var viewLang by remember { mutableStateOf(appLang) }
    val summary = remember(tick, date) { Summaries.day(ctx, date) }
    val saved = remember(tick, date) { store.note(date) }
    var note by remember(date) { mutableStateOf(saved.note) }
    var typing by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var problem by remember { mutableStateOf<String?>(null) }
    val segments = remember { mutableListOf<String>() }

    val recognizer = remember {
        VoiceRecognizer(ctx, prefs.voiceLang, object : VoiceRecognizer.Listener {
            override fun onPartial(text: String) {
                partial = text
            }
            override fun onSegment(text: String) {
                segments += text
                partial = ""
            }
            override fun onLevel(level0: Float) {
                level = level0
            }
            override fun onProblem(problem0: VoiceRecognizer.Problem, code: Int) {
                problem = problemText(problem0, appLang)
                listening = false
            }
            override fun onFinished() {
                val added = Punctuator.paragraph(segments)
                segments.clear()
                if (added.isNotBlank()) {
                    note = listOf(note.trim(), added).filter { it.isNotBlank() }.joinToString(" ")
                    store.setNote(date, store.note(date).copy(note = note))
                }
                partial = ""
                listening = false
            }
        })
    }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            problem = null
            listening = true
            recognizer.start()
        } else {
            problem = appLang.t2("Microphone permission is needed for voice notes.", "आवाज़ से नोट के लिए माइक की अनुमति चाहिए।")
        }
    }

    fun toggleMic() {
        if (listening) {
            recognizer.stop()
        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            problem = null
            listening = true
            recognizer.start()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ScreenScaffold(
        title = date.format(dateFormatter(appLang, "EEEE, d MMM")),
        onBack = { nav.back() },
        actions = {
            Segmented(listOf(Lang.EN to "EN", Lang.HI to "हिं"), viewLang, { viewLang = it }, Modifier.width(110.dp).testTag("journalLang"))
        },
    ) {
        CardBox {
            Text(
                viewLang.t2("Written automatically", "ऐप ने अपने आप लिखा"),
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            )
            JournalWriter.write(summary, Summaries.zone, viewLang).forEach { p ->
                val isHeading = p.endsWith(":") && !p.contains("·")
                Text(p, fontSize = 16.sp, lineHeight = 25.sp, fontWeight = if (isHeading) FontWeight.SemiBold else FontWeight.Normal)
            }
        }

        Text(tr("How was the day?", "दिन कैसा रहा?"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        val moods = listOf(tr("Great", "बढ़िया"), tr("Good", "अच्छा"), tr("Okay", "ठीक"), tr("Tired", "थका हुआ"), tr("Bad", "खराब"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            moods.forEachIndexed { i, m ->
                OptionTile(m, saved.mood == i, {
                    store.setNote(date, store.note(date).copy(mood = if (saved.mood == i) null else i))
                }, minHeight = 44.dp, modifier = Modifier.testTag("mood-$i"))
            }
        }

        Text(tr("A line for tomorrow (optional)", "कल के लिए एक लाइन (ज़रूरी नहीं)"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        if (!typing) {
            CardBox(onClick = { toggleMic() }, modifier = Modifier.testTag("micButton")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(56.dp).scale(if (listening) 1f + level * 0.25f else 1f).clip(CircleShape)
                            .background(if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (listening) tr("Listening… tap to stop", "सुन रहे हैं… रोकने के लिए टैप करें") else tr("Tap and speak", "टैप करें और बोलें"),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Muted(if (listening && partial.isNotBlank()) partial else tr("Hindi or English, punctuation is added for you", "हिंदी या अंग्रेज़ी, विराम चिह्न अपने आप लगेंगे"))
                    }
                }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
            if (note.isNotBlank()) {
                CardBox {
                    Text(note, fontSize = 16.sp, lineHeight = 24.sp)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { typing = true }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                SmallTextButton(tr("Type instead", "टाइप करें")) { typing = true }
            }
        } else {
            OutlinedTextField(
                value = note, onValueChange = { note = it }, minLines = 3,
                modifier = Modifier.fillMaxWidth(), label = { Text(tr("Your note", "आपका नोट")) },
            )
            ButtonsRow {
                SecondButton(tr("Use voice", "आवाज़ से"), { typing = false }, Modifier.weight(1f))
                BigButton(tr("Save", "सेव करें"), {
                    store.setNote(date, DayNote(store.note(date).mood, note.trim()))
                    typing = false
                }, Modifier.weight(1f))
            }
        }

        if (date.dayOfWeek == DayOfWeek.SUNDAY) {
            val week = remember(tick, date) { Summaries.week(ctx, date.with(DayOfWeek.MONDAY)) }
            CardBox(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(viewLang.t2("Week summary", "हफ्ते का सार"), fontWeight = FontWeight.SemiBold)
                Text(JournalWriter.week(week, viewLang), fontSize = 15.sp)
            }
        }
    }
}

fun Lang.t2(en: String, hi: String) = if (this == Lang.HI) hi else en

fun problemText(p: VoiceRecognizer.Problem, lang: Lang): String = when (p) {
    VoiceRecognizer.Problem.NO_RECOGNIZER -> lang.t2(
        "This phone has no speech recogniser. Install or update the Google app, then try again.",
        "इस फोन में आवाज़ पहचानने वाला सिस्टम नहीं है। Google ऐप इंस्टॉल/अपडेट करें।",
    )
    VoiceRecognizer.Problem.NO_PERMISSION -> lang.t2("Microphone permission is needed.", "माइक की अनुमति चाहिए।")
    VoiceRecognizer.Problem.LANGUAGE_MISSING -> lang.t2(
        "The Hindi/English voice pack isn't on this phone yet. Open Settings › Voice test to download it.",
        "हिंदी/अंग्रेज़ी वॉइस पैक अभी फोन में नहीं है। सेटिंग्स › वॉइस टेस्ट से डाउनलोड करें।",
    )
    VoiceRecognizer.Problem.NETWORK_NEEDED -> lang.t2(
        "Offline voice isn't ready. Download the voice pack in Settings › Voice test, or connect to the internet once.",
        "ऑफलाइन आवाज़ तैयार नहीं है। सेटिंग्स › वॉइस टेस्ट से पैक डाउनलोड करें या एक बार इंटरनेट चालू करें।",
    )
    VoiceRecognizer.Problem.OTHER -> lang.t2("Couldn't hear that. Try again.", "सुनाई नहीं दिया। फिर से कोशिश करें।")
}
