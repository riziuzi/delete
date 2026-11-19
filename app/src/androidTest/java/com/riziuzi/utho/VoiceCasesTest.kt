package com.riziuzi.utho

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.riziuzi.utho.alarm.AlarmReceiver
import com.riziuzi.utho.alarm.AlarmScheduler
import com.riziuzi.utho.alarm.CheckIns
import com.riziuzi.utho.alarm.Notifications
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.ui.CheckInActivity
import com.riziuzi.utho.ui.MainActivity
import com.riziuzi.utho.ui.beginStudy
import com.riziuzi.utho.ui.endStudy
import com.riziuzi.utho.voice.AudioGuard
import com.riziuzi.utho.voice.FakeVoice
import com.riziuzi.utho.voice.VoiceLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Every way the voice turns on and off, checked for sound:
 *  - Utho's own mic is opened only while media is muted, and media comes back only well after it closed;
 *  - every mic that opens is closed again; no session is left running;
 *  - only Utho's own soft sounds play (one when the mic opens, one when it closes), nothing else;
 *  - the phone's speech service (Google voice) is never asked to open the mic unless allowed in Settings.
 * Plus the new rule: silence stops study, and an answer by voice or typing (also from the lock-screen
 * notification) continues it.
 *
 * The emulator can't hear, so a pretend engine reads Utho's real microphone pipe and "hears" a script.
 */
@RunWith(AndroidJUnit4::class)
class VoiceCasesTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val name = TestName()

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private val store get() = Store.get(ctx)
    private val am get() = ctx.getSystemService(AudioManager::class.java)
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    /** Sounds other than Utho's own (alarm-type) ones heard while the test ran. */
    private val otherSounds = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var watching = false
    private var watcher: Thread? = null

    @Before fun setUp() {
        FakeVoice.script = emptyList()
        FakeVoice.noSilentEngines = false
        endStudy(ctx)
        CheckIns.clearWaiting(ctx)
        store.clearAll()
        Prefs(ctx).apply {
            checkInOn = true
            checkInSec = 60
            listenSeconds = 5
            subjects = listOf("Polity", "History", "Geography", "Economy", "CSAT", "Ethics")
            checkInSound = "bowl"
            googleVoice = false
        }
        waitGuardIdle()
        // Media on and audible, so a mute shows.
        instr.runOnMainSync {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
            }
        }
        VoiceLog.clear()
        watching = true
        watcher = Thread {
            while (watching) {
                runCatching {
                    am.activePlaybackConfigurations.forEach {
                        val u = it.audioAttributes.usage
                        if (u != AudioAttributes.USAGE_ALARM) otherSounds += "usage=$u"
                    }
                }
                Thread.sleep(40)
            }
        }.apply { start() }
    }

    @After fun tearDown() {
        watching = false
        watcher?.join(500)
        dump()
        FakeVoice.script = null
        FakeVoice.noSilentEngines = false
        endStudy(ctx)
        AlarmScheduler.cancelDeadline(ctx)
        CheckIns.clearWaiting(ctx)
        store.clearAll()
        Prefs(ctx).clearAll()
    }

    // ---------- helpers ----------

    private fun passTime(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            runCatching { rule.mainClock.advanceTimeBy(250) }
            Thread.sleep(250)
        }
    }

    private fun waitForTag(tag: String, ms: Long = 15_000) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching { rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)) return
            passTime(250)
        }
        throw AssertionError("'$tag' never appeared. Voice log: ${events()}")
    }

    private fun waitUntil(what: String, ms: Long = 8_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (ok()) return
            passTime(250)
        }
        throw AssertionError("$what never happened. Voice log: ${events()}")
    }

    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true)

    private fun broadcast(action: String) = ctx.sendBroadcast(Intent(ctx, AlarmReceiver::class.java).setAction(action))

    private fun start(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun events(): List<String> = VoiceLog.all().map { it.what }

    private fun count(prefix: String) = events().count { it.startsWith(prefix) }

    /** Media back on: nobody holds the quiet window and its hold time has passed. */
    private fun waitGuardIdle() {
        val end = System.currentTimeMillis() + AudioGuard.HOLD_MS + 6_000
        while (System.currentTimeMillis() < end) {
            var idle = false
            instr.runOnMainSync { idle = !AudioGuard.mutedByUs && AudioGuard.holders == 0 }
            if (idle) return
            Thread.sleep(250)
        }
        fail("media was never unmuted. Voice log: ${events()}")
    }

    private fun shot(name: String) {
        runCatching { rule.waitForIdle() }
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The notification shade, to see the lock-screen notification as the phone shows it. */
    private fun shotShade(name: String) {
        instr.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        Thread.sleep(1500)
        shot(name)
        instr.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(800)
    }

    private fun dump() {
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        val t0 = VoiceLog.all().firstOrNull()?.atMs ?: 0L
        File(dir, "voice-${name.methodName}.txt").writeText(
            VoiceLog.all().joinToString("\n") { "%6d ms  %s".format(it.atMs - t0, it.what) } +
                "\nother sounds: $otherSounds\n",
        )
    }

    /**
     * The sound rules, over everything the voice parts did in this test:
     * mic only while muted; unmute only after the mic has been closed for the whole hold time; every mic and
     * session closed; Utho's two sounds paired; never Google's own mic; no sound but Utho's.
     */
    private fun checkSilence(micRounds: Int? = null) {
        waitGuardIdle()
        val ev = VoiceLog.all()
        var muted = false
        var mic = false
        var lastClose = -1L
        for (e in ev) {
            when {
                e.what.startsWith("mic:open") -> {
                    assertTrue("the mic opened while media wasn't muted: ${events()}", muted)
                    mic = true
                }
                e.what == "mic:close" -> {
                    mic = false
                    lastClose = e.atMs
                }
                e.what == "guard:mute" -> muted = true
                e.what == "guard:unmute" -> {
                    assertFalse("media came back while the mic was open: ${events()}", mic)
                    if (lastClose >= 0) {
                        assertTrue(
                            "media came back ${e.atMs - lastClose} ms after the mic closed: ${events()}",
                            e.atMs - lastClose >= AudioGuard.HOLD_MS - 300,
                        )
                    }
                    muted = false
                }
                e.what.startsWith("engine:GOOGLE_MIC") -> fail("Google's own mic was used: ${events()}")
            }
        }
        assertFalse("the mic is still open: ${events()}", mic)
        assertEquals("every mic that opened was closed: ${events()}", count("mic:open"), count("mic:close"))
        assertEquals("every session ended: ${events()}", count("voice:start"), count("voice:end") + count("voice:destroy"))
        assertEquals("Utho's open and close sounds come in pairs: ${events()}", count("earcon:open"), count("earcon:close"))
        assertTrue("no more open sounds than mic openings: ${events()}", count("earcon:open") <= count("mic:open"))
        micRounds?.let {
            assertEquals("listening rounds: ${events()}", it, count("mic:open"))
            assertEquals("one open sound per listening: ${events()}", it, count("earcon:open"))
        }
        assertEquals("media muted once and back once: ${events()}", count("guard:mute"), count("guard:unmute"))
        instr.runOnMainSync { assertFalse("media is still muted", am.isStreamMute(AudioManager.STREAM_MUSIC)) }
        assertTrue("sounds other than Utho's: $otherSounds", otherSounds.isEmpty())
        // The pretend engine really got audio from Utho's mic (at least half a second per round).
        ev.filter { it.what.startsWith("fake:read=") }.forEach {
            assertTrue("mic audio reached the engine: ${it.what}", it.what.removePrefix("fake:read=").toLong() >= 16_000)
        }
    }

    private fun waitingNotification() = nm.activeNotifications.firstOrNull { it.id == Notifications.ID_MISSED }?.notification

    /** Types [text] into the notification's reply box, the way the phone's notification shade sends it. */
    private fun replyInNotification(text: String) {
        val n = waitingNotification() ?: throw AssertionError("no 'study paused' notification")
        val action = n.actions.first { it.remoteInputs != null }
        val fill = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply { putCharSequence(com.riziuzi.utho.alarm.ReviveReceiver.KEY_TEXT, text) })
        action.actionIntent.send(ctx, 0, fill)
    }

    private fun checkInActivity(): Activity? {
        var a: Activity? = null
        instr.runOnMainSync {
            a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is CheckInActivity }
        }
        return a
    }

    /** Study, the interval ends, nothing is said: study stops. Returns the session id and the check-in time. */
    private fun stopBySilence(): Pair<Long, Long> {
        beginStudy(ctx, "Polity")
        val id = store.activeSession()!!.id
        broadcast(AlarmScheduler.ACTION_CHECK_IN)
        waitForTag("checkInLive")
        val t = store.activeSession()!!.checkIns.single().timeMs
        waitForTag("checkInStopped", 20_000)
        assertNull("study stopped", store.activeSession())
        assertTrue("no entry for the unanswered interval", store.sessions.single { it.id == id }.checkIns.isEmpty())
        return id to t
    }

    // ---------- the timed check-in ----------

    @Test fun timedCheckIn_somethingSaid_keepsStudying_noOtherSound() {
        FakeVoice.script = listOf(900L to "polity ke fundamental rights padhe mood accha hai")
        beginStudy(ctx, "History")
        broadcast(AlarmScheduler.ACTION_CHECK_IN)
        waitForTag("checkInSubject", 20_000)
        tag("checkInSubject").assertTextEquals("Subject: Polity")
        passTime(5_000) // saves and closes by itself
        val c = store.activeSession()!!.checkIns.single()
        assertEquals(CheckInKind.SPOKEN, c.kind)
        assertEquals("Polity", c.topic)
        assertEquals(1, c.mood)
        checkSilence(micRounds = 1)
    }

    @Test fun timedCheckIn_silence_stopsStudy_andTheLockScreenNotificationOffersSpeakAndType() {
        stopBySilence()
        shot("voice-1-stopped-screen")
        val n = waitingNotification()
        assertNotNull("'study paused' notification", n)
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, n!!.visibility)
        assertEquals("Speak and Type", 2, n.actions.size)
        assertTrue("Type has a reply box", n.actions.any { it.remoteInputs?.isNotEmpty() == true })
        assertNull("the notification makes no sound", nm.getNotificationChannel(Notifications.CH_CHECKIN).sound)
        shotShade("voice-2-paused-notification")
        checkSilence(micRounds = 1)
    }

    @Test fun answerTypedInTheNotification_continuesStudy() {
        val (id, t) = stopBySilence()
        rule.onNodeWithText("Close", useUnmergedTree = true).performClick()
        replyInNotification("economy mein inflation aur repo rate padha, mood accha hai")
        waitUntil("study going on again") { store.activeSession() != null }
        val s = store.activeSession()!!
        assertEquals("the same session goes on", id, s.id)
        val c = s.checkIns.single { it.timeMs == t }
        assertEquals(CheckInKind.SPOKEN, c.kind)
        assertEquals("Economy", c.topic)
        assertEquals(1, c.mood)
        assertTrue("next check-in planned", Prefs(ctx).nextCheckInMs > System.currentTimeMillis())
        assertNull("nothing waiting any more", CheckIns.waiting(ctx))
        shotShade("voice-3-typed-answer-saved")
        checkSilence(micRounds = 1)
    }

    @Test fun answerSpokenOnTheStoppedScreen_continuesStudy() {
        val (id, t) = stopBySilence()
        FakeVoice.script = listOf(900L to "geography mein monsoon padha")
        tag("reviveSpeak").performClick()
        waitForTag("checkInDone")
        passTime(2_500)
        shot("voice-4-answer-listening")
        tag("checkInDone").performClick()
        waitForTag("checkInSubject")
        tag("checkInSubject").assertTextEquals("Subject: Geography")
        tag("checkInSave").performClick()
        waitUntil("study going on again") { store.activeSession() != null }
        assertEquals(id, store.activeSession()!!.id)
        assertEquals("Geography", store.activeSession()!!.checkIns.single { it.timeMs == t }.topic)
        checkSilence(micRounds = 2)
    }

    @Test fun answerTypedOnTheStoppedScreen_continuesStudy() {
        val (id, t) = stopBySilence()
        tag("reviveType").performClick()
        tag("checkInType").performTextInput("ethics ke case studies kiye")
        tag("checkInNext").performClick()
        waitForTag("checkInSubject")
        tag("checkInSubject").assertTextEquals("Subject: Ethics")
        tag("checkInSave").performClick()
        waitUntil("study going on again") { store.activeSession() != null }
        assertEquals(id, store.activeSession()!!.id)
        assertEquals("Ethics", store.activeSession()!!.checkIns.single { it.timeMs == t }.topic)
        checkSilence(micRounds = 1)
    }

    @Test fun answerSpokenFromTheNotification_continuesStudy() {
        val (id, t) = stopBySilence()
        rule.onNodeWithText("Close", useUnmergedTree = true).performClick()
        passTime(1_000)
        FakeVoice.script = listOf(900L to "history mein mughal empire padha, thak gaya")
        val speak = waitingNotification()!!.actions.first { it.remoteInputs == null }
        speak.actionIntent.send()
        waitForTag("checkInDone")
        passTime(2_500)
        tag("checkInDone").performClick()
        waitForTag("checkInSubject")
        tag("checkInSubject").assertTextEquals("Subject: History")
        tag("checkInMood").assertTextEquals("Mood: Tired")
        shot("voice-5-answer-from-notification")
        tag("checkInSave").performClick()
        waitUntil("study going on again") { store.activeSession() != null }
        assertEquals(id, store.activeSession()!!.id)
        assertEquals("History", store.activeSession()!!.checkIns.single { it.timeMs == t }.topic)
        checkSilence(micRounds = 2)
    }

    @Test fun aLateAnswer_isKeptAsANote_andStudyStaysStopped() {
        stopBySilence()
        rule.onNodeWithText("Close", useUnmergedTree = true).performClick()
        Prefs(ctx).reviveUntil = System.currentTimeMillis() - 1_000
        replyInNotification("csat mein percentage padha")
        waitUntil("the note is saved") { store.freeNotes.isNotEmpty() }
        assertNull(store.activeSession())
        assertEquals("CSAT", store.freeNotes.single().topic)
    }

    @Test fun startingStudyAgain_dropsTheWaitingAnswer() {
        stopBySilence()
        rule.onNodeWithText("Close", useUnmergedTree = true).performClick()
        beginStudy(ctx, "History")
        assertNull(CheckIns.waiting(ctx))
        passTime(500)
        assertNull("notification gone", waitingNotification())
    }

    @Test fun popupRecreatedWhileListening_mediaStaysMutedTheWholeTime() {
        beginStudy(ctx, "Polity")
        broadcast(AlarmScheduler.ACTION_CHECK_IN)
        waitForTag("checkInLive")
        passTime(1_500)
        val a = checkInActivity() ?: throw AssertionError("pop-up not found")
        instr.runOnMainSync { a.recreate() }
        waitForTag("checkInStopped", 20_000)
        checkSilence(micRounds = 2)
    }

    // ---------- notes you start yourself ----------

    @Test fun note_listensUntilDone_withNoTimeLimit() {
        FakeVoice.script = listOf(1_000L to "aaj ethics padha", 20_000L to "aur case studies bhi kiye")
        start(CheckInActivity.noteIntent(ctx))
        waitForTag("checkInDone")
        passTime(23_000) // longer than any pop-up listens
        tag("checkInDone").assertExists() // still listening
        tag("checkInDone").performClick()
        waitForTag("checkInSubject")
        tag("checkInSubject").assertTextEquals("Subject: Ethics")
        tag("checkInSave").performClick()
        waitUntil("note saved") { store.freeNotes.isNotEmpty() }
        assertTrue(store.freeNotes.single().text.contains("case studies"))
        checkSilence(micRounds = 1)
    }

    @Test fun typeThenSpeakAgainQuickly_mediaStaysMutedInBetween() {
        start(CheckInActivity.noteIntent(ctx))
        waitForTag("checkInTypeInstead")
        passTime(1_000)
        tag("checkInTypeInstead").performClick() // mic closes
        passTime(500)
        tag("typingSpeak").performClick() // and opens again right away
        waitForTag("checkInDone")
        passTime(1_500)
        tag("checkInDone").performClick() // nothing heard: typing
        waitForTag("checkInType")
        rule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        checkSilence(micRounds = 2)
        assertEquals("muted once for both rounds: ${events()}", 1, count("guard:mute"))
    }

    @Test fun cancelWhileListening_closesTheMic() {
        start(CheckInActivity.noteIntent(ctx))
        waitForTag("checkInDone")
        passTime(1_500)
        rule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        checkSilence(micRounds = 1)
    }

    @Test fun leavingANoteWhileListening_closesTheMicAndKeepsWhatWasSaid() {
        FakeVoice.script = listOf(800L to "history mein revolt of 1857 padha")
        start(CheckInActivity.noteIntent(ctx))
        waitForTag("checkInDone")
        passTime(2_500)
        instr.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(3_000)
        assertTrue("mic closed on leaving: ${events()}", count("mic:close") >= 1)
        start(CheckInActivity.noteIntent(ctx)) // back to the open pop-up
        waitForTag("checkInSubject")
        tag("checkInSubject").assertTextEquals("Subject: History")
        tag("checkInSave").performClick()
        waitUntil("note saved") { store.freeNotes.isNotEmpty() }
        checkSilence(micRounds = 1)
    }

    @Test fun testPopup_listensAndSavesNothing() {
        FakeVoice.script = listOf(700L to "geography mein monsoon padha")
        start(CheckInActivity.testIntent(ctx))
        waitForTag("checkInSubject")
        passTime(5_000)
        assertTrue(store.sessions.isEmpty())
        assertTrue(store.freeNotes.isEmpty())
        checkSilence(micRounds = 1)
    }

    // ---------- phones without a silent engine ----------

    @Test fun noSilentEngine_typingInstead_neverGoogleVoice() {
        FakeVoice.script = null
        FakeVoice.noSilentEngines = true
        start(CheckInActivity.noteIntent(ctx))
        waitForTag("checkInType", 10_000)
        rule.onNodeWithText("Silent voice isn't ready", substring = true, useUnmergedTree = true).assertExists()
        shot("voice-6-no-silent-engine")
        rule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        checkSilence(micRounds = 0)
    }

    @Test fun noSilentEngine_timedCheckInCanBeAnsweredByTyping() {
        FakeVoice.script = null
        FakeVoice.noSilentEngines = true
        beginStudy(ctx, "History")
        broadcast(AlarmScheduler.ACTION_CHECK_IN)
        waitForTag("checkInType", 20_000)
        tag("checkInType").performTextInput("polity ke fundamental rights padhe")
        tag("checkInNext").performClick()
        waitForTag("checkInSubject")
        passTime(5_000)
        assertEquals("Polity", store.activeSession()!!.checkIns.single().topic)
        checkSilence(micRounds = 0)
    }

    /** The real engines of this device (emulator): whatever happens, never Google's own mic, never a sound. */
    @Test fun realEnginesOnThisDevice_neverGoogleMic() {
        FakeVoice.script = null
        start(CheckInActivity.noteIntent(ctx))
        passTime(6_000)
        shot("voice-7-real-engines")
        if (runCatching { tag("checkInDone").assertExists() }.isSuccess) tag("checkInDone").performClick()
        passTime(3_000)
        runCatching { rule.onNodeWithText("Cancel", useUnmergedTree = true).performClick() }
        checkSilence()
    }
}
