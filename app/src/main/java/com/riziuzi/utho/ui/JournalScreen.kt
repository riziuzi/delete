package com.riziuzi.utho.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.core.Moods
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import com.riziuzi.utho.voice.VoiceRecognizer
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A day at a glance: a short summary the app writes, then the study notes as one line each
 * (time, subject, mood; tap a line to read it), then the day's mood.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(nav: Nav, date: LocalDate) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val appLang = LocalLang.current
    val store = remember { Store.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    var viewLang by remember { mutableStateOf(appLang) }
    val summary = remember(tick, date) { Summaries.day(ctx, date) }
    val notes = remember(tick, date) { Summaries.notes(ctx, date) }
    val subjects = remember(tick) { prefs.subjects }
    val saved = remember(tick, date) { store.note(date) }
    val isToday = date == LocalDate.now(Summaries.zone)

    ScreenScaffold(
        title = date.format(dateFormatter(appLang, "EEE, d MMM")),
        onBack = { nav.back() },
        actions = {
            Segmented(listOf(Lang.EN to "EN", Lang.HI to "Hinglish"), viewLang, { viewLang = it }, Modifier.width(150.dp).testTag("journalLang"))
        },
    ) {
        // ---- the day in a few lines (notes are listed below, so they're left out here)
        val overview = JournalWriter.write(summary.copy(checkIns = emptyList(), checkInsAsked = 0), Summaries.zone, viewLang)
        CardBox(modifier = Modifier.testTag("daySummary")) {
            Text(viewLang.t2("Summary", "Summary"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            overview.forEach { Text(it, fontSize = 16.sp, lineHeight = 24.sp) }
        }

        // ---- notes: time · subject · mood, tap to read
        val missed = notes.count { it.kind == CheckInKind.MISSED }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Study notes", "Padhai ke notes"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (notes.isNotEmpty()) Muted(tr("Tap a line to read it", "Padhne ke liye line par tap karein"))
        }
        NotesList(
            notes, subjects,
            if (isToday) tr("No notes yet. Tap Speak and say what you studied.", "Abhi koi note nahi. Bolein dabakar bataiye kya padha.")
            else tr("No notes on this day.", "Is din koi note nahi."),
        )
        if (missed > 0) {
            Muted(
                tr(
                    "Missed check-ins don't count as study time until you answer them.",
                    "Missed check-in ka jawaab dene tak woh time padhai mein count nahi hota.",
                ),
            )
        }
        if (isToday) {
            ButtonsRow {
                Button(
                    onClick = { ctx.startActivity(CheckInActivity.noteIntent(ctx)) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("micButton"), shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  " + tr("Speak", "Bolein"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { ctx.startActivity(CheckInActivity.noteIntent(ctx, type = true)) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("typeButton"), shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  " + tr("Type", "Type"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ---- the day's mood: picked from what you said, or tap one
        Text(tr("Mood of the day", "Aaj ka mood"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..4).forEach { i ->
                OptionTile(Moods.label(i, appLang), saved.mood == i, {
                    store.setNote(date, store.note(date).copy(mood = if (saved.mood == i) null else i))
                }, minHeight = 44.dp, modifier = Modifier.testTag("mood-$i"))
            }
        }
        Muted(
            if (saved.mood == null) tr("Picked by itself when you say how you feel.", "Mood bolenge to apne aap select hoga.")
            else tr("Tap to change.", "Badalne ke liye tap karein."),
        )
        if (saved.note.isNotBlank()) {
            CardBox { Text(tr("Note: ", "Note: ") + saved.note, fontSize = 15.sp) }
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
        "The Hindi voice pack isn't on this phone yet. Download it in Settings › Voice.",
        "Hindi voice pack abhi phone mein nahi hai. Settings › Voice se download karein.",
    )
    VoiceRecognizer.Problem.NETWORK_NEEDED -> lang.t2(
        "Offline voice isn't ready. Download the Hindi pack in Settings › Voice, or turn on internet once.",
        "Offline voice ready nahi hai. Settings › Voice se Hindi pack download karein, ya ek baar internet on karein.",
    )
    VoiceRecognizer.Problem.OTHER -> lang.t2("Couldn't hear that. Try again.", "Sunai nahi diya. Phir se try karein.")
    VoiceRecognizer.Problem.SILENT_MISSING -> lang.t2(
        "Silent voice isn't ready on this phone yet. Type for now, and download it once in Settings › Voice.",
        "Silent voice abhi phone mein ready nahi hai. Abhi type karein, aur Settings › Voice se ek baar download karein.",
    )
    VoiceRecognizer.Problem.NO_MIC -> lang.t2(
        "The microphone is busy (another app may be using it). Type for now.",
        "Mic abhi busy hai (shayad koi aur app use kar raha hai). Abhi type karein.",
    )
}
