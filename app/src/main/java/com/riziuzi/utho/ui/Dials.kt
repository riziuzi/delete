package com.riziuzi.utho.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private val ItemHeight = 44.dp

/** One scrolling dial (like an alarm clock's): the number in the middle band is the value. */
@Composable
fun Wheel(range: IntRange, value: Int, onChange: (Int) -> Unit, label: String, tag: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val values = remember(range) { range.toList() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (value - range.first).coerceIn(0, values.lastIndex))
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val selected by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: state.firstVisibleItemIndex
        }
    }
    LaunchedEffect(selected) { onChange(values[selected.coerceIn(0, values.lastIndex)]) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(72.dp).height(ItemHeight * 3), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(ItemHeight).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer))
            LazyColumn(
                state = state, flingBehavior = fling, contentPadding = PaddingValues(vertical = ItemHeight),
                horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().testTag("wheel-$tag"),
            ) {
                items(values.size) { i ->
                    val on = i == selected
                    Box(Modifier.height(ItemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "%02d".format(values[i]),
                            fontSize = if (on) 26.sp else 19.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
    }
}

/**
 * Pick a time span on dials: hours, minutes and seconds, or (without [hours]) minutes and seconds.
 * The result is at least [minSec] and at most [maxSec] seconds.
 */
@Composable
fun DurationDialog(
    title: String,
    initialSec: Int,
    hours: Boolean,
    minSec: Int,
    maxSec: Int,
    note: String?,
    onDismiss: () -> Unit,
    seconds: Boolean = true,
    onPick: (Int) -> Unit,
) {
    var h by remember { mutableIntStateOf(if (hours) initialSec / 3600 else 0) }
    var m by remember { mutableIntStateOf(if (hours) initialSec % 3600 / 60 else initialSec / 60) }
    var s by remember { mutableIntStateOf(initialSec % 60) }
    val total = h * 3600 + m * 60 + if (seconds) s else 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    if (hours) Wheel(0..23, h, { h = it }, tr("hours", "ghante"), "hours")
                    Wheel(0..(if (hours) 59 else maxSec / 60), m, { m = it }, tr("min", "minute"), "min")
                    if (seconds) Wheel(0..59, s, { s = it }, tr("sec", "second"), "sec")
                }
                when {
                    total < minSec -> Text(tr("Shortest is ", "Kam se kam ") + com.riziuzi.utho.core.JournalWriter.span(minSec, LocalLang.current), color = MaterialTheme.colorScheme.error)
                    total > maxSec -> Text(tr("Longest is ", "Zyada se zyada ") + com.riziuzi.utho.core.JournalWriter.span(maxSec, LocalLang.current), color = MaterialTheme.colorScheme.error)
                    note != null -> Muted(note)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(total.coerceIn(minSec, maxSec)) }, modifier = Modifier.testTag("dialOk")) { Text(tr("OK", "OK")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "Cancel")) } },
    )
}
