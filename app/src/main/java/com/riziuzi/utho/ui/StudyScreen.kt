package com.riziuzi.utho.ui

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
    var fixing by remember { mutableStateOf<CheckIn?>(null) }

    ScreenScaffold(title = tr("Study session", "पढ़ाई सेशन"), onBack = { nav.back() }) {
        if (session == null) {
            Muted(tr("No session is running.", "अभी कोई सेशन नहीं चल रहा।"))
            BigButton(tr("Start studying", "पढ़ाई शुरू करें"), { pickNew = true })
            return@ScreenScaffold
        }
        val current = session.checkIns.lastOrNull()?.topic ?: session.subject
        CardBox {
            Text(
                formatElapsed(now - session.startMs), fontSize = 52.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().testTag("studyTimer"), textAlign = TextAlign.Center,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Muted(tr("Now studying", "अभी पढ़ रहे हैं"))
                    Text(current, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                SmallTextButton(tr("Change", "बदलें")) { switchTopic = true }
            }
        }

        val interval = remember(tick) { prefs.checkInMin }
        val next = remember(tick) { prefs.nextCheckInMs }
        CardBox {
            if (interval > 0) {
                Text(tr("Voice check-in every $interval min", "हर $interval मिनट पर आवाज़ से चेक-इन"), fontWeight = FontWeight.SemiBold)
                if (next > now) {
                    Muted(tr("Next at ", "अगला ") + JournalWriter.clock(next, Summaries.zone, lang))
                }
                Muted(
                    tr(
                        "The screen wakes up and listens for 15 seconds. Just say what you studied.",
                        "स्क्रीन जागेगी और 15 सेकंड सुनेगी। बस बताइए क्या पढ़ा।",
                    ),
                )
            } else {
                Text(tr("Voice check-ins are off", "आवाज़ चेक-इन बंद हैं"), fontWeight = FontWeight.SemiBold)
                Muted(tr("Turn them on in Settings.", "सेटिंग्स में चालू करें।"))
            }
            SecondButton(tr("Check in now", "अभी चेक-इन करें"), {
                ctx.startActivity(Intent(ctx, CheckInActivity::class.java).putExtra(CheckInActivity.EXTRA_MANUAL, true))
            })
        }

        if (session.checkIns.any { it.kind == CheckInKind.SPOKEN }) {
            SectionLabel(tr("Check-ins", "चेक-इन"))
            CardBox {
                session.checkIns.filter { it.kind == CheckInKind.SPOKEN }.sortedByDescending { it.timeMs }.forEachIndexed { i, c ->
                    if (i > 0) Divider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Muted(JournalWriter.clock(c.timeMs, Summaries.zone, lang), Modifier.weight(1f))
                            OptionTile(c.topic ?: tr("Not sure", "पता नहीं"), selected = c.topic != null, onClick = { fixing = c }, minHeight = 36.dp)
                        }
                        Text(c.text.ifBlank { tr("(nothing said)", "(कुछ नहीं कहा)") }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        BigButton(tr("Stop studying", "पढ़ाई रोकें"), {
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
    val fix = fixing
    if (fix != null && session != null) {
        SubjectDialog(prefs.subjects, { fixing = null }) { s ->
            fixing = null
            store.correctCheckIn(session.id, fix.timeMs, s)
        }
    }
}

