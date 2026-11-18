package com.riziuzi.utho.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.riziuzi.utho.alarm.AlarmScheduler
import com.riziuzi.utho.alarm.Notifications
import com.riziuzi.utho.data.Changes
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Summaries
import java.time.LocalDate

sealed interface Route {
    data object Setup : Route
    data class Home(val tab: Tab = Tab.TODAY) : Route
    data object Alarm : Route
    data object Study : Route
    data class Journal(val epochDay: Long) : Route
    data object Profile : Route
    data object Settings : Route
}

enum class Tab { TODAY, PROGRESS, GROUP }

class Nav(start: Route) {
    val stack = mutableStateListOf(start)
    val current: Route get() = stack.last()
    fun go(r: Route) {
        stack.add(r)
    }
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    fun reset(r: Route) {
        stack.clear()
        stack.add(r)
    }
    fun setTab(t: Tab) {
        stack[stack.lastIndex] = Route.Home(t)
    }
}

/** Current change counter: read it in a screen to recompute when data changes. */
@Composable
fun rememberTick(): Int = Changes.tick.collectAsState().value

class MainActivity : ComponentActivity() {
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannels(this)
        AlarmScheduler.rescheduleAll(this)
        openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
        setContent { AppRoot(openRequest) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra(EXTRA_OPEN)
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_STUDY = "study"
        const val OPEN_JOURNAL = "journal"
    }
}

@Composable
fun AppRoot(open: MutableState<String?>) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val prefs = remember { Prefs(ctx) }
    val lang = remember(tick) { prefs.lang }
    val theme = remember(tick) { prefs.theme }
    val nav = remember { Nav(if (prefs.setupDone) Route.Home() else Route.Setup) }

    LaunchedEffect(open.value) {
        val req = open.value ?: return@LaunchedEffect
        if (prefs.setupDone) {
            when (req) {
                MainActivity.OPEN_STUDY -> if (nav.current != Route.Study) nav.go(Route.Study)
                MainActivity.OPEN_JOURNAL -> nav.go(Route.Journal(LocalDate.now(Summaries.zone).toEpochDay()))
            }
        }
        open.value = null
    }

    UthoTheme(theme, lang) {
        BackHandler(enabled = nav.stack.size > 1) { nav.back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val r = nav.current) {
                Route.Setup -> SetupScreen(onDone = { nav.reset(Route.Home()) })
                is Route.Home -> HomeScreen(nav, r.tab)
                Route.Alarm -> AlarmScreen(nav)
                Route.Study -> StudyScreen(nav)
                is Route.Journal -> JournalScreen(nav, LocalDate.ofEpochDay(r.epochDay))
                Route.Profile -> ProfileScreen(nav)
                Route.Settings -> SettingsScreen(nav)
            }
        }
    }
}

@Composable
fun HomeScreen(nav: Nav, tab: Tab) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == Tab.TODAY, onClick = { nav.setTab(Tab.TODAY) },
                    icon = { Icon(Icons.Outlined.WbSunny, contentDescription = null) },
                    label = { Text(tr("Today", "Aaj")) }, modifier = Modifier.testTag("tab-today"),
                )
                NavigationBarItem(
                    selected = tab == Tab.PROGRESS, onClick = { nav.setTab(Tab.PROGRESS) },
                    icon = { Icon(Icons.Outlined.BarChart, contentDescription = null) },
                    label = { Text(tr("Progress", "Progress")) }, modifier = Modifier.testTag("tab-progress"),
                )
                NavigationBarItem(
                    selected = tab == Tab.GROUP, onClick = { nav.setTab(Tab.GROUP) },
                    icon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                    label = { Text(tr("Group", "Group")) }, modifier = Modifier.testTag("tab-group"),
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.TODAY -> TodayScreen(nav)
                Tab.PROGRESS -> ProgressScreen(nav)
                Tab.GROUP -> GroupScreen()
            }
        }
    }
}
