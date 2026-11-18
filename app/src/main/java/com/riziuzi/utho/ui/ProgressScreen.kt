package com.riziuzi.utho.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riziuzi.utho.core.JournalWriter
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.core.Stats
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val levelColorsLight = listOf(Color.White, Color(0xFFDDE1FA), Color(0xFFA9B2F0), Color(0xFF5C6AD6), Color(0xFF2B3AAE))

private fun levelOf(min: Int) = when {
    min <= 0 -> 0
    min < 150 -> 1
    min < 270 -> 2
    min < 360 -> 3
    else -> 4
}

@Composable
fun ProgressScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val lang = LocalLang.current
    val prefs = remember { Prefs(ctx) }
    var monthView by rememberSaveable { mutableStateOf(true) }
    var month by remember { mutableStateOf(YearMonth.now(Summaries.zone)) }
    val today = LocalDate.now(Summaries.zone)
    val weekStart = today.with(DayOfWeek.MONDAY)

    val perDay = remember(tick) { Summaries.minutesPerDay(ctx) }
    val workoutDays = remember(tick) { Store.get(ctx).workouts.filter { it.done > 0 }.map { Summaries.dateOf(it.timeMs) }.toSet() }
    val active = remember(tick) { Summaries.activeDays(ctx) }
    val week = remember(tick) { Summaries.week(ctx, weekStart) }
    val byTopic = remember(tick) { Summaries.weekByTopic(ctx, weekStart) }
    val streak = remember(tick) { Stats.streak(active, today) }
    val restLeft = remember(tick) { Stats.restDaysLeft(active, today) }
    val exercise = remember(tick) { prefs.exercise }
    val target = remember(tick) { prefs.alarmTarget }
    val best = remember(tick) { Stats.bestSeconds(Store.get(ctx).workouts, exercise, target) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Progress", "Progress"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Segmented(
                listOf(false to tr("Week", "Hafta"), true to tr("Month", "Mahina")), monthView, { monthView = it },
                Modifier.width(180.dp),
            )
        }

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(tr("${week.activeDays} of 7", "${week.activeDays} / 7"), tr("days this week", "din is hafte"), Modifier.weight(1f).fillMaxHeight())
            Tile(JournalWriter.duration(week.studyMinutes, lang), tr("studied this week", "padhai is hafte"), Modifier.weight(1f).fillMaxHeight())
            Tile(tr("$streak days", "$streak din"), tr("current run", "lagatar"), Modifier.weight(1f).fillMaxHeight())
        }

        if (monthView) {
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = tr("Previous month", "Pichhla mahina"))
                    }
                    Text(
                        month.atDay(1).format(dateFormatter(lang, "MMMM yyyy")),
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }, enabled = month.isBefore(YearMonth.from(today))) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = tr("Next month", "Agla mahina"))
                    }
                }
                MonthGrid(month, today, perDay, workoutDays) { d -> nav.go(Route.Journal(d.toEpochDay())) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Muted("0")
                    levelColorsLight.forEachIndexed { i, c ->
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(if (i == 0) MaterialTheme.colorScheme.surface else c).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)))
                    }
                    Muted(tr("6 h+", "6 ghante+"))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                    Muted(tr("workout", "workout"))
                }
                Muted(tr("Tap a day to open its journal", "Journal dekhne ke liye din par tap karein"))
            }
        } else {
            CardBox {
                Text(tr("This week", "Yeh hafta"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                WeekBars(weekStart, today, perDay, workoutDays) { d -> nav.go(Route.Journal(d.toEpochDay())) }
            }
        }

        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("This week by subject", "Is hafte subject ke hisaab se"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Muted(tr("$restLeft rest days left", "$restLeft rest days baaki"))
            }
            if (byTopic.isEmpty()) Muted(tr("Start a study session to see this.", "Yeh dekhne ke liye padhai shuru karein."))
            val max = byTopic.maxOfOrNull { it.second } ?: 1
            byTopic.take(8).forEach { (t, m) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t, fontSize = 14.sp, modifier = Modifier.width(110.dp), maxLines = 1)
                    Bar(m / max.toFloat(), Modifier.weight(1f))
                    Text(JournalWriter.duration(m, Lang.EN), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(78.dp), textAlign = TextAlign.End)
                }
            }
        }

        CardBox(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(JournalWriter.week(week, lang), fontSize = 15.sp)
            if (best != null) {
                Text(
                    if (exercise.isHold) tr("Longest plank: $best s", "Sabse lamba plank: $best second")
                    else tr(
                        "Best $target ${JournalWriter.exerciseName(exercise, Lang.EN)}: ${JournalWriter.seconds(best, Lang.EN)}",
                        "$target ${JournalWriter.exerciseName(exercise, Lang.HI)} ka best time: ${JournalWriter.seconds(best, Lang.HI)}",
                    ),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Tile(big: String, small: String, modifier: Modifier) {
    CardBox(modifier) {
        Text(big, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(small, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, perDay: Map<LocalDate, Int>, workoutDays: Set<LocalDate>, onDay: (LocalDate) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val first = month.atDay(1)
    val blanks = first.dayOfWeek.value - 1
    val cells: List<LocalDate?> = List(blanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(tr("M", "M"), tr("T", "T"), tr("W", "W"), tr("T", "T"), tr("F", "F"), tr("S", "S"), tr("S", "S")).forEach {
                Text(it, fontSize = 12.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until 7) {
                    val d = week.getOrNull(i)
                    if (d == null) {
                        Spacer(Modifier.weight(1f).height(40.dp))
                        continue
                    }
                    val future = d.isAfter(today)
                    val lv = levelOf(perDay[d] ?: 0)
                    val bg = if (future || lv == 0) cs.surface else levelColorsLight[lv]
                    val fg = if (lv >= 3 && !future) Color.White else if (future) cs.onSurfaceVariant else cs.onSurface
                    val fgFixed = if (!future && lv in 1..2) Color(0xFF1B1B1F) else fg
                    Column(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                            .border(if (d == today) 2.dp else 1.dp, if (d == today) cs.onSurface else cs.outlineVariant, RoundedCornerShape(10.dp))
                            .clickable(enabled = !future) { onDay(d) }
                            .testTag("day-${d.dayOfMonth}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("${d.dayOfMonth}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fgFixed)
                        if (d in workoutDays) Box(Modifier.size(5.dp).clip(CircleShape).background(fgFixed))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekBars(weekStart: LocalDate, today: LocalDate, perDay: Map<LocalDate, Int>, workoutDays: Set<LocalDate>, onDay: (LocalDate) -> Unit) {
    val lang = LocalLang.current
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }
    val max = (days.maxOfOrNull { perDay[it] ?: 0 } ?: 0).coerceAtLeast(60)
    Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        days.forEach { d ->
            val m = perDay[d] ?: 0
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable(enabled = !d.isAfter(today)) { onDay(d) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(if (m > 0) "%.1f".format(m / 60f) else "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier.fillMaxWidth().height((110f * m / max).dp.coerceAtLeast(3.dp)).clip(RoundedCornerShape(6.dp))
                        .background(if (m > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (d in workoutDays) MaterialTheme.colorScheme.secondary else Color.Transparent))
                Text(d.format(dateFormatter(lang, "EEE")).take(3), fontSize = 12.sp, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
