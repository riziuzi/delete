package com.riziuzi.utho

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.riziuzi.utho.alarm.AlarmReceiver
import com.riziuzi.utho.alarm.AlarmScheduler
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.ui.CheckInActivity
import com.riziuzi.utho.ui.MainActivity
import com.riziuzi.utho.ui.beginStudy
import com.riziuzi.utho.ui.endStudy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The timed check-in rule, end to end on a real Android system (alarm broadcast → pop-up → store):
 * nothing said stops studying and leaves no entry; something said keeps studying.
 * The emulator can't hear, so "something said" uses the debug-only fake-heard text.
 */
@RunWith(AndroidJUnit4::class)
class CheckInFlowTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private val store get() = Store.get(ctx)

    @Before fun setUp() {
        endStudy(ctx)
        store.clearAll()
        Prefs(ctx).apply {
            checkInOn = true
            checkInSec = 60
            listenSeconds = 5
            subjects = listOf("Polity", "History", "Geography", "Economy", "CSAT")
        }
    }

    @After fun tearDown() {
        endStudy(ctx)
        AlarmScheduler.cancelDeadline(ctx)
        store.clearAll()
        Prefs(ctx).clearAll()
    }

    private fun passTime(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            rule.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
    }

    private fun waitForTag(tag: String, ms: Long = 15_000) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
            passTime(250)
        }
        throw AssertionError("'$tag' never appeared")
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true)

    private fun broadcast(action: String, time: Long? = null) {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action)
        if (time != null) i.putExtra(AlarmScheduler.EXTRA_TIME, time)
        ctx.sendBroadcast(i)
    }

    @Test fun intervalEndOpensThePopupByItselfAndSilenceStopsStudy() {
        beginStudy(ctx, "Polity")
        val session = store.activeSession()!!
        // The interval is over: the alarm fires.
        broadcast(AlarmScheduler.ACTION_CHECK_IN)
        waitForTag("checkInLive") // opened by itself, already listening
        shot("flow-1-popup-opens-and-listens")
        val placeholder = store.activeSession()!!.checkIns.single()
        assertEquals(CheckInKind.MISSED, placeholder.kind)
        // 5 s of silence.
        waitForTag("checkInStopped", 20_000)
        shot("flow-2-silence-stops-study")
        assertNull("study timer should have stopped", store.activeSession())
        val ended = store.sessions.single { it.id == session.id }
        assertEquals("nothing counted after the last answer", session.startMs, ended.endMs)
        assertTrue("no entry for the unanswered interval", ended.checkIns.isEmpty())
        passTime(7_000) // closes by itself
    }

    @Test fun somethingSaidKeepsStudyingAndPicksSubjectAndMood() {
        beginStudy(ctx, "History")
        val session = store.activeSession()!!
        val t = System.currentTimeMillis()
        store.addCheckIn(session.id, CheckIn(t, "", null, CheckInKind.MISSED))
        ctx.startActivity(
            CheckInActivity.timedIntent(ctx, t)
                .putExtra(CheckInActivity.EXTRA_FAKE_HEARD, "polity ke fundamental rights padhe mood accha hai")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitForTag("checkInSubject")
        shot("flow-3-answer-result")
        tag("checkInSubject").assertTextEquals("Subject: Polity")
        tag("checkInMood").assertTextEquals("Mood: Good")
        passTime(5_000) // saves and closes by itself
        val now = store.activeSession()
        assertNotNull("study keeps going", now)
        val c = now!!.checkIns.single { it.timeMs == t }
        assertEquals(CheckInKind.SPOKEN, c.kind)
        assertEquals("Polity", c.topic)
        assertEquals(1, c.mood)
        assertTrue(c.text.startsWith("Polity ke fundamental rights"))
    }

    @Test fun answerWithNoSubjectOrMoodStillKeepsStudying() {
        beginStudy(ctx, "History")
        val session = store.activeSession()!!
        val t = System.currentTimeMillis()
        store.addCheckIn(session.id, CheckIn(t, "", null, CheckInKind.MISSED))
        ctx.startActivity(
            CheckInActivity.timedIntent(ctx, t).putExtra(CheckInActivity.EXTRA_FAKE_HEARD, "haan padh raha hoon").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitForTag("checkInSubject")
        tag("checkInSubject").assertTextEquals("Subject: not selected")
        passTime(5_000)
        val c = store.activeSession()!!.checkIns.single { it.timeMs == t }
        assertEquals(CheckInKind.SPOKEN, c.kind)
        assertNull(c.topic)
    }

    @Test fun typingInATimedPopupKeepsStudying() {
        Prefs(ctx).listenSeconds = 30
        beginStudy(ctx, "History")
        val session = store.activeSession()!!
        val t = System.currentTimeMillis()
        store.addCheckIn(session.id, CheckIn(t, "", null, CheckInKind.MISSED))
        ctx.startActivity(CheckInActivity.timedIntent(ctx, t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForTag("checkInTypeInstead")
        tag("checkInTypeInstead").performClick()
        waitForTag("checkInType")
        tag("checkInType").performTextInput("economy mein inflation aur repo rate padha")
        shot("flow-5-timed-typing")
        tag("checkInNext").performClick()
        waitForTag("checkInSubject")
        tag("checkInSubject").assertTextEquals("Subject: Economy")
        passTime(5_000) // saves and closes by itself
        val c = store.activeSession()!!.checkIns.single { it.timeMs == t }
        assertEquals(CheckInKind.SPOKEN, c.kind)
        assertEquals("Economy", c.topic)
    }

    @Test fun popupNeverShownStopsStudyAtTheDeadline() {
        beginStudy(ctx, "Polity")
        val session = store.activeSession()!!
        val answered = session.startMs + 10_000
        store.addCheckIn(session.id, CheckIn(answered, "polity padhi", "Polity"))
        val t = session.startMs + 20_000
        store.addCheckIn(session.id, CheckIn(t, "", null, CheckInKind.MISSED))
        broadcast(AlarmScheduler.ACTION_CHECK_IN_DEADLINE, t)
        passTime(2_000)
        assertNull(store.activeSession())
        val ended = store.sessions.single { it.id == session.id }
        assertEquals("ends at the last answer", answered, ended.endMs)
        assertTrue(ended.checkIns.none { it.kind == CheckInKind.MISSED })
    }

    @Test fun anAnsweredCheckInIsNotStoppedByItsDeadline() {
        beginStudy(ctx, "Polity")
        val session = store.activeSession()!!
        val t = System.currentTimeMillis()
        store.addCheckIn(session.id, CheckIn(t, "economy padhi", "Economy"))
        broadcast(AlarmScheduler.ACTION_CHECK_IN_DEADLINE, t)
        passTime(2_000)
        assertNotNull(store.activeSession())
    }

    @Test fun testPopupSavesNothing() {
        ctx.startActivity(CheckInActivity.testIntent(ctx).putExtra(CheckInActivity.EXTRA_FAKE_HEARD, "geography mein monsoon padha").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForTag("checkInSubject")
        shot("flow-4-test-popup")
        tag("checkInSubject").assertTextEquals("Subject: Geography")
        passTime(5_000)
        assertTrue(store.sessions.isEmpty())
        assertTrue(store.freeNotes.isEmpty())
    }
}
