package com.riziuzi.utho.ui

import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import kotlinx.coroutines.delay

@Composable
fun StudyScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val prefs = remember { Prefs(ctx) }
    val lang = LocalLang.current
    val store = remember { Store.get(ctx) }
    val session = remember(tick) { store.activeSession() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    var pickNew by remember { mutableStateOf(false) }
    var switchTopic by remember { mutableStateOf(false) }
    var editTiming by remember { mutableStateOf(false) }

    ScreenScaffold(title = tr("Study session", "Padhai session"), onBack = { nav.back() }) {
        if (session == null) {
            Muted(tr("No session is running.", "Abhi koi session nahi chal raha."))
            BigButton(tr("Start studying", "Padhai shuru karein"), { pickNew = true })
            return@ScreenScaffold
        }
        val current = session.checkIns.lastOrNull { it.topic != null }?.topic ?: session.subject
        CardBox {
            Text(
                formatElapsed(now - session.startMs), fontSize = 52.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().testTag("studyTimer"), textAlign = TextAlign.Center,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Muted(tr("Now studying", "Abhi padh rahe hain"))
                    Text(current, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                SmallTextButton(tr("Change", "Badlein")) { switchTopic = true }
            }
        }

        val interval = remember(tick) { prefs.checkInEverySec }
        val span = JournalWriter.span(interval, lang)
        val listenSpan = JournalWriter.span(remember(tick) { prefs.listenSeconds }, lang)
        val next = remember(tick) { prefs.nextCheckInMs }
        CardBox {
            if (interval > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Voice check-in every $span", "Har $span par voice check-in"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    SmallTextButton(tr("Change", "Badlein")) { editTiming = true }
                }
                if (next > now) {
                    Muted(tr("Next at ", "Agla ") + JournalWriter.clock(next, Summaries.zone, lang))
                }
                Muted(
                    tr(
                        "A pop-up listens for $listenSpan. Say anything and study goes on. Say nothing and it pauses at your last answer; answer from the notification to continue.",
                        "Pop-up $listenSpan sunega. Kuch bhi bolein to padhai chalti rahegi. Kuch nahi bola to padhai pichhle jawaab par rukegi; notification se jawaab dekar aage badhaiye.",
                    ),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Voice check-ins are off", "Voice check-ins off hain"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    SmallTextButton(tr("Turn on", "On karein")) { editTiming = true }
                }
            }
            SecondButton(tr("Speak a note now", "Abhi note bolein"), {
                ctx.startActivity(CheckInActivity.noteIntent(ctx))
            })
        }

        val notes = session.checkIns
            .filter { it.kind == CheckInKind.MISSED || (it.kind == CheckInKind.SPOKEN && it.text.isNotBlank()) }
            .sortedByDescending { it.timeMs }
        if (notes.isNotEmpty()) {
            SectionLabel(tr("Check-ins", "Check-ins"))
            NotesList(notes, prefs.subjects, "")
        }

        BigButton(tr("Stop studying", "Padhai rokein"), {
            endStudy(ctx)
            nav.back()
        }, Modifier.testTag("stopStudy"))
    }

    if (pickNew) {
        SubjectDialog(prefs.subjects, { pickNew = false }) { s ->
            pickNew = false
            beginStudy(ctx, s)
        }
    }
    if (switchTopic && session != null) {
        SubjectDialog(prefs.subjects, { switchTopic = false }) { s ->
            switchTopic = false
            store.addCheckIn(session.id, CheckIn(System.currentTimeMillis(), "", s, CheckInKind.SWITCH))
        }
    }
    if (editTiming) {
        AlertDialog(
            onDismissRequest = { editTiming = false },
            title = { Text(tr("Voice check-ins", "Voice check-ins")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { CheckInTiming() } },
            confirmButton = { TextButton(onClick = { editTiming = false }) { Text(tr("Done", "Ho gaya")) } },
        )
    }
}

