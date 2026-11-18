package com.riziuzi.utho

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
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

    private fun startActivity(intent: Intent) {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun tour() {
        // ---- setup
        shot("01-setup-step1")
        tag("nameField").performTextInput("Rishi")
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
        click("Hinglish")
        shot("14-journal-hinglish")
        click("Good")
        shot("15-journal-mood")
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

        // ---- camera workout (practice)
        startActivity(WorkoutActivity.workoutIntent(ctx, Exercise.PUSHUP, 40, Level.EASY, record = false))
        shot("26-workout-pushups", 5000)
        back()
        startActivity(WorkoutActivity.workoutIntent(ctx, Exercise.PLANK, 60, Level.EASY, record = false))
        shot("27-workout-plank", 4000)
        back()

        // ---- voice check-in from the Speak button (session is still running)
        val store = com.riziuzi.utho.data.Store.get(ctx)
        tag("speakNote").performScrollTo().performClick()
        shot("28-checkin-listening", 2500)
        tag("checkInDone").performClick()
        shot("29-checkin-review", 300)
        // Nothing was heard on the emulator: fix the subject and mood by hand, then save.
        rule.onNodeWithText("History", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Good", useUnmergedTree = true).performClick()
        shot("29b-checkin-fixed", 300)
        tag("checkInSave").performClick()
        Thread.sleep(800)
        store.activeSession()!!.checkIns.last().let {
            org.junit.Assert.assertEquals("History", it.topic)
            org.junit.Assert.assertEquals(1, it.mood)
        }

        // ---- the timed pop-up when nobody answers: waits 15 s, says "not selected / not said", closes by itself
        val before = store.activeSession()!!.checkIns.size
        startActivity(Intent(ctx, CheckInActivity::class.java))
        // The test pauses Compose timers unless its clock is moved; the pop-up itself counts wall time.
        fun passTime(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                rule.mainClock.advanceTimeBy(250)
                Thread.sleep(250)
            }
        }
        passTime(7_000)
        shot("29c-checkin-waiting", 100)
        passTime(9_000)
        shot("29d-checkin-no-answer", 100)
        val deadline = System.currentTimeMillis() + 15_000
        while (store.activeSession()!!.checkIns.size == before && System.currentTimeMillis() < deadline) passTime(500)
        store.activeSession()!!.checkIns.let {
            org.junit.Assert.assertEquals(before + 1, it.size)
            org.junit.Assert.assertNull(it.last().topic)
            org.junit.Assert.assertNull(it.last().mood)
            org.junit.Assert.assertEquals("", it.last().text)
        }

        // ---- still awake?
        startActivity(Intent(ctx, AwakeCheckActivity::class.java))
        shot("30-awake-check", 2000)
        runCatching { rule.onNodeWithTag("imAwake").performClick() }
        Thread.sleep(800)

        endStudy(ctx)
        shot("31-end")
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
