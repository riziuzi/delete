package com.riziuzi.utho.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Moods
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import java.time.Instant

private val TimeCol = 74.dp
private val MoodCol = 96.dp

/** A small rounded label. */
@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Column titles above a list of notes. */
@Composable
fun NotesHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val style = MaterialTheme.typography.labelMedium
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(tr("TIME", "TIME"), style = style, color = color, modifier = Modifier.width(TimeCol))
        Text(tr("SUBJECT", "SUBJECT"), style = style, color = color, modifier = Modifier.weight(1f))
        Text(tr("MOOD", "MOOD"), style = style, color = color, modifier = Modifier.width(MoodCol))
        Box(Modifier.size(24.dp))
    }
}

/**
 * One note in a line: time, subject, mood. Tap to see what was said and to fix the subject or mood.
 * A missed check-in shows "Missed" and opens the answer screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteRow(c: CheckIn, subjects: List<String>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val cs = MaterialTheme.colorScheme
    var open by remember(c.timeMs) { mutableStateOf(false) }
    val missed = c.kind == CheckInKind.MISSED
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                if (missed) ctx.startActivity(CheckInActivity.answerIntent(ctx, c.timeMs)) else open = !open
            }.padding(vertical = 8.dp).testTag("note-${c.timeMs}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(JournalWriter.clock(c.timeMs, Summaries.zone, com.riziuzi.utho.core.Lang.EN), fontSize = 14.sp, modifier = Modifier.width(TimeCol))
            if (missed) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Pill(tr("Missed", "Missed"), cs.errorContainer, cs.onErrorContainer)
                }
                Text(tr("Answer", "Jawaab dein"), color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.width(MoodCol))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
            } else {
                Row(Modifier.weight(1f).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (c.topic != null) {
                        Pill(c.topic, cs.primaryContainer, cs.onPrimaryContainer)
                    } else {
                        Text(tr("Not set", "Set nahi"), color = cs.onSurfaceVariant, fontSize = 14.sp)
                    }
                }
                Box(Modifier.width(MoodCol)) {
                    if (c.mood != null) {
                        Pill(Moods.label(c.mood, lang), cs.secondaryContainer, cs.onSecondaryContainer)
                    } else {
                        Text("—", color = cs.onSurfaceVariant, fontSize = 14.sp)
                    }
                }
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (open) tr("Hide note", "Note chhupayein") else tr("Show note", "Note dekhein"),
                    tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp),
                )
            }
        }
        if (open && !missed) {
            Text(c.text, fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.testTag("noteText"))
            val store = Store.get(ctx)
            Muted(tr("Subject (tap to change)", "Subject (badalne ke liye tap karein)"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                subjects.forEach { s ->
                    OptionTile(s, s == c.topic, {
                        if (s != c.topic) {
                            store.updateCheckIn(c.timeMs) { it.copy(topic = s, corrected = true) }
                            if (c.text.isNotBlank()) {
                                c.topic?.let { old -> store.unlearn(old, c.text) }
                                store.learn(s, c.text, strong = true)
                            }
                        }
                    }, minHeight = 36.dp)
                }
            }
            Muted(tr("Mood (tap to change)", "Mood (badalne ke liye tap karein)"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..4).forEach { m ->
                    OptionTile(Moods.label(m, lang), m == c.mood, {
                        val newMood = if (c.mood == m) null else m
                        store.updateCheckIn(c.timeMs) { it.copy(mood = newMood) }
                        if (newMood != null) store.setDayMood(Instant.ofEpochMilli(c.timeMs).atZone(Summaries.zone).toLocalDate(), newMood)
                    }, minHeight = 36.dp)
                }
            }
        }
    }
}

/** A list of notes with column titles, or a hint when there are none. */
@Composable
fun NotesList(notes: List<CheckIn>, subjects: List<String>, empty: String) {
    CardBox(modifier = Modifier.testTag("notesList")) {
        if (notes.isEmpty()) {
            Muted(empty)
        } else {
            NotesHeader()
            notes.forEachIndexed { i, c ->
                if (i > 0) Divider()
                NoteRow(c, subjects)
            }
        }
    }
}
