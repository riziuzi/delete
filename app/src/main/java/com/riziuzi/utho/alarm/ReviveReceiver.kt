package com.riziuzi.utho.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.riziuzi.utho.core.CheckIn
import com.riziuzi.utho.core.CheckInKind
import com.riziuzi.utho.core.Hinglish
import com.riziuzi.utho.core.Moods
import com.riziuzi.utho.core.t
import com.riziuzi.utho.data.Prefs
import com.riziuzi.utho.data.Store
import com.riziuzi.utho.data.Summaries
import java.time.Instant

/**
 * The answer typed right into the "Study paused" notification: subject and mood are picked from it, it's
 * saved for that check-in, and study goes on (see [CheckIns.revive]).
 */
class ReviveReceiver : BroadcastReceiver() {
    companion object {
        const val KEY_TEXT = "answer"

        /** Saves [typed] as the answer to the waiting check-in. Returns the saved check-in and whether study goes on. */
        fun answer(ctx: Context, typed: String, now: Long = System.currentTimeMillis()): Pair<CheckIn, Boolean> {
            val written = Hinglish.note(listOf(typed.trim()))
            val topic = Summaries.classifier(ctx).classify(written).best
            val mood = Moods.detect(written)
            val c = CheckIn(now, written, topic, CheckInKind.SPOKEN, mood = mood)
            val time = CheckIns.waiting(ctx, now)?.timeMs ?: now
            val on = CheckIns.revive(ctx, c, now)
            val store = Store.get(ctx)
            mood?.let { store.setDayMood(Instant.ofEpochMilli(time).atZone(Summaries.zone).toLocalDate(), it) }
            if (topic != null) store.learn(topic, written, strong = false)
            return c to on
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        val ctx = context.applicationContext
        val lang = Prefs(ctx).lang
        if (typed.isBlank()) {
            // Nothing typed: leave the notification as it was.
            CheckIns.showWaiting(ctx)
            return
        }
        val pending = goAsync()
        Thread {
            try {
                val (c, on) = answer(ctx, typed)
                val what = listOfNotNull(c.topic, c.mood?.let { Moods.label(it, lang) }).joinToString(" · ")
                Notifications.showReviveDone(
                    ctx,
                    if (on) lang.t("Saved. Study goes on.", "Save ho gaya. Padhai chalti rahegi.")
                    else lang.t("Saved as a note", "Note mein save ho gaya"),
                    what.ifBlank { c.text },
                )
            } finally {
                pending.finish()
            }
        }.start()
    }
}
