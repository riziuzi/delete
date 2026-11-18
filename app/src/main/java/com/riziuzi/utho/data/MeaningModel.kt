package com.riziuzi.utho.data

import android.content.Context
import com.riziuzi.utho.core.Meaning

/** Loads the on-device meaning table from the app's assets once (about 10 MB, a fraction of a second). */
object MeaningModel {
    @Volatile private var loaded: Meaning? = null
    @Volatile private var failed = false

    fun get(ctx: Context): Meaning? {
        loaded?.let { return it }
        if (failed) return null
        synchronized(this) {
            loaded?.let { return it }
            loaded = runCatching {
                ctx.assets.open("meaning/words.txt").use { w ->
                    ctx.assets.open("meaning/vectors.bin").use { v -> Meaning.load(w, v) }
                }
            }.onFailure { failed = true }.getOrNull()
            return loaded
        }
    }

    /** Start loading in the background so it's ready by the time a note is finished. */
    fun preload(ctx: Context) {
        if (loaded == null && !failed) Thread { get(ctx.applicationContext) }.apply { isDaemon = true }.start()
    }
}
