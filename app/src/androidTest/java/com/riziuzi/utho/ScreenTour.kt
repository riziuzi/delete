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
        click("हिं")
        shot("14-journal-hi-text")
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
        tag("deleteAll").performScrollTo()
        shot("19-settings-bottom")
        rule.onNodeWithText("Voice test").performScrollTo().performClick()
        shot("20-voice-test")
        tag("voiceTestButton").performClick()
        shot("21-voice-test-listening", 3000)
        tag("voiceTestButton").performClick()
        shot("22-voice-test-result", 3500)
        back()

        // ---- Hindi app
        rule.onNodeWithTag("langSetting").performScrollTo()
        click("हिंदी")
        shot("23-settings-hindi")
        back()
        back()
        shot("24-today-hindi")
        tag("tab-progress").performClick()
        shot("25-progress-hindi")
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

        // ---- voice check-in (session is still running)
        startActivity(Intent(ctx, CheckInActivity::class.java))
        shot("28-checkin-listening", 2500)
        Thread.sleep(16_000)
        shot("29-checkin-review", 1500)
        Thread.sleep(9_000)

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
        store.addCheckIn(s.id, com.riziuzi.utho.core.CheckIn(s.startMs + 1, "मुगल साम्राज्य पढ़ा", "History"))
        endStudy(ctx)
        val ended = store.sessions.last()
        org.junit.Assert.assertFalse(ended.active)
        org.junit.Assert.assertEquals("History", ended.checkIns.single().topic)
        store.clearAll()
    }
}
