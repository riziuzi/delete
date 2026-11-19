package com.riziuzi.utho

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.Exercise
import com.riziuzi.utho.core.Lang
import com.riziuzi.utho.core.Level
import com.riziuzi.utho.data.DemoData
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.ui.AwakeCheckActivity
import com.riziuzi.utho.ui.CheckInActivity
import com.riziuzi.utho.ui.MainActivity
import com.riziuzi.utho.ui.WorkoutActivity
import com.riziuzi.utho.ui.beginStudy
import com.riziuzi.utho.ui.endStudy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Walks through every screen of a fresh install and saves a screenshot of each.
 * Run on CI; the PNGs are pushed to the ci-output branch for review.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTour {

    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext

    private fun shot(name: String, waitMs: Long = 700) {
        rule.waitForIdle()
        Thread.sleep(waitMs)
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun click(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).performClick()
    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true)

    /** System back gesture; unlike Espresso it doesn't need the window to have focus. */
    private fun back() {
        instr.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(600)
        rule.waitForIdle()
    }

    private val noteRow = androidx.compose.ui.test.SemanticsMatcher("a note row") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("note-") == true
    }

    /** The test pauses Compose timers unless its clock is moved; the pop-up itself counts wall time. */
    private fun passTime(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            rule.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
    }

    /** The keyboard would otherwise eat the next back gesture. */
    private fun hideKeyboard() {
        rule.runOnUiThread {
            val w = rule.activity.window
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        Thread.sleep(400)
        rule.waitForIdle()
    }

    private fun startActivity(intent: Intent) {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @org.junit.After
    fun after() {
        com.riziuzi.utho.voice.FakeVoice.script = null
    }

    @Test
    fun tour() {
        // The emulator can't hear: a pretend engine reads Utho's mic and hears nothing, like a quiet room.
        com.riziuzi.utho.voice.FakeVoice.script = emptyList()
        // ---- setup
        shot("01-setup-step1")
        tag("nameField").performTextInput("Rishi")
        hideKeyboard()
        click("UPSC CSE")
        shot("02-setup-step1-filled")
        click("Continue")
        shot("03-setup-step2")
        click("Continue")
        shot("04-setup-step3")
        click("Finish")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Start studying")).fetchSemanticsNodes().isNotEmpty() }

        // ---- sample history so the progress and journal screens have something to show
        DemoData.seed(ctx)
        Prefs(ctx).examDate = java.time.LocalDate.now().plusDays(186).toEpochDay()
        shot("05-today")

        // ---- alarm
        tag("alarmCard").performClick()
        shot("06-alarm")
        click("Squats")
        shot("07-alarm-squats")
        click("Push-ups")
        back()

        // ---- study session
        tag("startStudy").performClick()
        shot("08-pick-subject")
        click("Polity")
        shot("09-study")
        back()
        shot("10-today-studying")

        // ---- progress
        tag("tab-progress").performClick()
        shot("11-progress-month")
        click("Week")
        shot("12-progress-week")
        click("Month")
        tag("day-${java.time.LocalDate.now().minusDays(1).dayOfMonth}").let {
            runCatching { it.performClick() }.onFailure { tag("day-${java.time.LocalDate.now().dayOfMonth}").performClick() }
        }
        shot("13-journal-en")
        // Tap a line to read it.
        rule.onAllNodes(noteRow, useUnmergedTree = true)[0].performClick()
        shot("14-journal-note-open")
        click("Hinglish")
        shot("15-journal-hinglish")
        // Answer yesterday's missed check-in by typing.
        val store = com.riziuzi.utho.data.Store.get(ctx)
        val missedAt = store.sessions.flatMap { it.checkIns }.first { it.kind == CheckInKind.MISSED }.timeMs
        tag("note-$missedAt").performScrollTo().performClick()
        shot("15b-answer-missed", 1500)
        tag("checkInTypeInstead").performClick()
        tag("checkInType").performTextInput("polity ke fundamental rights padhe, mood accha hai")
        tag("checkInNext").performClick()
        shot("15c-answer-result", 300)
        tag("checkInSubject").assertTextEquals("Subject: Polity")
        tag("checkInMood").assertTextEquals("Mood: Good")
        tag("checkInSave").performClick()
        Thread.sleep(800)
        rule.waitForIdle()
        store.findCheckIn(missedAt)!!.let {
            org.junit.Assert.assertEquals(CheckInKind.SPOKEN, it.kind)
            org.junit.Assert.assertEquals("Polity", it.topic)
            org.junit.Assert.assertEquals(1, it.mood)
        }
        shot("15d-journal-answered")
        tag("mood-1").performScrollTo().performClick()
        shot("15e-journal-mood")
        back()

        // ---- group
        tag("tab-group").performClick()
        shot("16-group")
        tag("tab-today").performClick()

        // ---- profile and settings
        tag("avatar").performClick()
        shot("17-profile")
        tag("openSettings").performClick()
        shot("18-settings-top")
        rule.onNodeWithText("Offline voice").performScrollTo()
        shot("19-settings-voice")
        tag("openPopupSetup").performScrollTo().performClick()
        shot("19d-popup-setup")
        back()
        // Interval on the hour / minute / second dials: 1 h 45 min.
        tag("checkInEvery").performScrollTo().performClick()
        shot("19a-dial-open", 500)
        tag("wheel-hours").performScrollToIndex(1)
        tag("wheel-min").performScrollToIndex(45)
        shot("19a-dial-set", 500)
        tag("dialOk").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(3600 + 45 * 60, Prefs(ctx).checkInSec)
        tag("checkInListen").performScrollTo().performClick()
        shot("19a-listen-dial", 500)
        rule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        Prefs(ctx).checkInSec = 30 * 60
        tag("sound-chime").performScrollTo().performClick()
        shot("19b-settings-sound")
        rule.onNodeWithText("What Utho has learned").performScrollTo().performClick()
        shot("19c-learned")
        back()
        tag("deleteAll").performScrollTo()
        shot("20-settings-bottom")

        // ---- Hinglish app
        rule.onNodeWithTag("langSetting").performScrollTo()
        click("Hinglish")
        shot("23-settings-hinglish")
        back()
        back()
        shot("24-today-hinglish")
        tag("tab-progress").performClick()
        shot("25-progress-hinglish")
        tag("tab-today").performClick()
        Prefs(ctx).lang = Lang.EN
        rule.waitForIdle()

        // ---- every main screen in the dark theme
        Prefs(ctx).theme = "dark"
        rule.waitForIdle()
        tag("tab-today").performClick()
        shot("40-dark-today")
        tag("startStudy").performClick()
        shot("41-dark-study")
        back()
        tag("alarmCard").performClick()
        shot("42-dark-alarm")
        back()
        tag("tab-progress").performClick()
        shot("43-dark-progress")
        tag("day-${java.time.LocalDate.now().minusDays(1).dayOfMonth}").let {
            runCatching { it.performClick() }.onFailure { tag("day-${java.time.LocalDate.now().dayOfMonth}").performClick() }
        }
        shot("44-dark-journal")
        back()
        tag("tab-group").performClick()
        shot("45-dark-group")
        tag("tab-today").performClick()
        tag("avatar").performClick()
        shot("46-dark-profile")
        tag("openSettings").performClick()
        shot("47-dark-settings")
        tag("openPopupSetup").performScrollTo().performClick()
        shot("48-dark-popup-setup")
        back()
        back()
        back()
        Prefs(ctx).theme = "system"
        rule.waitForIdle()

        // ---- camera workout (practice)
        startActivity(WorkoutActivity.workoutIntent(ctx, Exercise.PUSHUP, 40, Level.EASY, record = false))
        shot("26-workout-pushups", 5000)
        back()
        startActivity(WorkoutActivity.workoutIntent(ctx, Exercise.PLANK, 60, Level.EASY, record = false))
        shot("27-workout-plank", 4000)
        back()

        // ---- a note from the Speak button (session is still running); no time limit, stop with Done
        tag("speakNote").performScrollTo().performClick()
        shot("28-note-listening", 2500)
        tag("checkInDone").performClick()
        // The emulator can't hear: it offers typing instead.
        shot("29-note-type", 300)
        tag("checkInType").performTextInput("aaj history ke mughal empire padhe, thoda thak gaya")
        tag("checkInNext").performClick()
        shot("29b-note-result", 300)
        tag("checkInSubject").assertTextEquals("Subject: History")
        tag("checkInMood").assertTextEquals("Mood: Tired")
        tag("checkInSave").performClick()
        Thread.sleep(800)
        rule.waitForIdle()
        store.activeSession()!!.checkIns.last().let {
            org.junit.Assert.assertEquals("History", it.topic)
            org.junit.Assert.assertEquals(3, it.mood)
        }

        // ---- the timed pop-up when nobody answers: listens, then study stops and the interval leaves no entry
        val sessionBefore = store.activeSession()!!
        startActivity(Intent(ctx, CheckInActivity::class.java))
        passTime(7_000)
        shot("29c-checkin-waiting", 100)
        passTime(9_000)
        shot("29d-checkin-no-answer-stops-study", 100)
        val stopDeadline = System.currentTimeMillis() + 15_000
        while (store.activeSession() != null && System.currentTimeMillis() < stopDeadline) passTime(500)
        org.junit.Assert.assertNull("study stops when nothing is said", store.activeSession())
        store.sessions.single { it.id == sessionBefore.id }.let {
            org.junit.Assert.assertTrue("no entry for the unanswered interval", it.checkIns.none { c -> c.kind == CheckInKind.MISSED })
            org.junit.Assert.assertEquals(com.riziuzi.utho.core.Stats.lastConfirmedMs(sessionBefore), it.endMs)
        }
        passTime(16_000) // the "study stopped" screen closes by itself

        // ---- still awake?
        startActivity(Intent(ctx, AwakeCheckActivity::class.java))
        shot("30-awake-check", 2000)
        runCatching { rule.onNodeWithTag("imAwake").performClick() }
        Thread.sleep(800)

        endStudy(ctx)
        shot("31-end")

        // ---- dark theme: the pop-up's text must be readable
        Prefs(ctx).theme = "dark"
        startActivity(CheckInActivity.noteIntent(ctx, type = true))
        Thread.sleep(1500)
        tag("checkInType").performTextInput("political parties padhi, mood badhiya")
        tag("checkInNext").performClick()
        shot("32-note-dark", 500)
        tag("checkInSubject").assertTextEquals("Subject: Polity")
        tag("checkInMood").assertTextEquals("Mood: Great")
        tag("checkInSave").performClick()
        Thread.sleep(800)
        Prefs(ctx).theme = "system"
    }

    @Test
    fun studyLogicWorksEndToEnd() {
        // Not a screenshot test: session -> check-in -> topic minutes, through the real store.
        val store = com.riziuzi.utho.data.Store.get(ctx)
        store.clearAll()
        beginStudy(ctx, "Polity")
        val s = store.activeSession()!!
        store.addCheckIn(s.id, com.riziuzi.utho.core.CheckIn(s.startMs + 1, "Mughal samrajya padha", "History"))
        endStudy(ctx)
        val ended = store.sessions.last()
        org.junit.Assert.assertFalse(ended.active)
        org.junit.Assert.assertEquals("History", ended.checkIns.single().topic)

        // A note spoken outside a session still lands in today's journal, with its mood.
        val today = java.time.LocalDate.now(com.riziuzi.utho.data.Summaries.zone)
        store.addFreeNote(com.riziuzi.utho.core.CheckIn(System.currentTimeMillis(), "Polity revision ki, mood accha hai", "Polity", mood = 1))
        store.setDayMood(today, 1)
        val day = com.riziuzi.utho.data.Summaries.day(ctx, today)
        org.junit.Assert.assertTrue(day.checkIns.any { it.text.startsWith("Polity revision") })
        org.junit.Assert.assertEquals(1, store.note(today).mood)
        store.clearAll()
    }
}
