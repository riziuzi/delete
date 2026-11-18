package com.riziuzi.utho.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riziuzi.utho.core.DayNote
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.core.Moods
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
    val store = remember { Store.get(ctx) }
    var viewLang by remember { mutableStateOf(appLang) }
    val summary = remember(tick, date) { Summaries.day(ctx, date) }
    val saved = remember(tick, date) { store.note(date) }
    var typing by remember { mutableStateOf(false) }
    var note by remember(date) { mutableStateOf(saved.note) }
    val isToday = date == LocalDate.now(Summaries.zone)

    ScreenScaffold(
        title = date.format(dateFormatter(appLang, "EEEE, d MMM")),
        onBack = { nav.back() },
        actions = {
            Segmented(listOf(Lang.EN to "EN", Lang.HI to "Hinglish"), viewLang, { viewLang = it }, Modifier.width(150.dp).testTag("journalLang"))
        },
    ) {
        CardBox {
            Text(
                viewLang.t2("Written automatically", "App ne apne aap likha"),
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            )
            JournalWriter.write(summary, Summaries.zone, viewLang).forEach { p ->
                val isHeading = p.endsWith(":") && !p.contains("·")
                Text(p, fontSize = 16.sp, lineHeight = 25.sp, fontWeight = if (isHeading) FontWeight.SemiBold else FontWeight.Normal)
            }
            if (saved.note.isNotBlank()) {
                Text(tr("Your note: ", "Aapka note: ") + saved.note, fontSize = 16.sp, lineHeight = 25.sp)
            }
        }

        if (isToday) {
            CardBox(onClick = { ctx.startActivity(CheckInActivity.manualIntent(ctx)) }, modifier = Modifier.testTag("micButton")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Speak a note", "Bolkar note karein"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Muted(tr("Say what you studied and how you feel. Subject and mood are picked for you.", "Bataiye kya padha aur mood kaisa hai. Subject aur mood apne aap select honge."))
                    }
                }
            }
        }

        Text(tr("Mood", "Mood"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..4).forEach { i ->
                OptionTile(Moods.label(i, appLang), saved.mood == i, {
                    store.setNote(date, store.note(date).copy(mood = if (saved.mood == i) null else i))
                }, minHeight = 44.dp, modifier = Modifier.testTag("mood-$i"))
            }
        }
        if (saved.mood == null) Muted(tr("Picked automatically when you mention it in a voice note.", "Voice note mein bataenge to apne aap select hoga."))

        if (typing) {
            OutlinedTextField(
                value = note, onValueChange = { note = it }, minLines = 3,
                modifier = Modifier.fillMaxWidth(), label = { Text(tr("Your note", "Aapka note")) },
            )
            ButtonsRow {
                SecondButton(tr("Cancel", "Cancel"), { typing = false }, Modifier.weight(1f))
                BigButton(tr("Save", "Save"), {
                    store.setNote(date, DayNote(store.note(date).mood, note.trim()))
                    typing = false
                }, Modifier.weight(1f))
            }
        } else {
            SmallTextButton(tr("Type a note instead", "Type karke note likhein")) { typing = true }
        }

        if (date.dayOfWeek == DayOfWeek.SUNDAY) {
            val week = remember(tick, date) { Summaries.week(ctx, date.with(DayOfWeek.MONDAY)) }
            CardBox(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(viewLang.t2("Week summary", "Hafte ka summary"), fontWeight = FontWeight.SemiBold)
                Text(JournalWriter.week(week, viewLang), fontSize = 15.sp)
            }
        }
    }
}

fun Lang.t2(en: String, hi: String) = if (this == Lang.HI) hi else en

fun problemText(p: VoiceRecognizer.Problem, lang: Lang): String = when (p) {
    VoiceRecognizer.Problem.NO_RECOGNIZER -> lang.t2(
        "This phone has no speech recogniser. Install or update the Google app, then try again.",
        "Is phone mein voice pehchaanne wala system nahi hai. Google app install/update karein.",
    )
    VoiceRecognizer.Problem.NO_PERMISSION -> lang.t2("Microphone permission is needed.", "Mic ki permission chahiye.")
    VoiceRecognizer.Problem.LANGUAGE_MISSING -> lang.t2(
        "The Hindi voice pack isn't on this phone yet. Download it in Settings › Offline voice.",
        "Hindi voice pack abhi phone mein nahi hai. Settings › Offline voice se download karein.",
    )
    VoiceRecognizer.Problem.NETWORK_NEEDED -> lang.t2(
        "Offline voice isn't ready. Download the Hindi pack in Settings › Offline voice, or turn on internet once.",
        "Offline voice ready nahi hai. Settings › Offline voice se Hindi pack download karein, ya ek baar internet on karein.",
    )
    VoiceRecognizer.Problem.OTHER -> lang.t2("Couldn't hear that. Try again.", "Sunai nahi diya. Phir se try karein.")
}
