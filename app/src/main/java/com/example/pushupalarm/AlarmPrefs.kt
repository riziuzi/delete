package com.example.pushupalarm

import android.content.Context

class AlarmPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("alarm", Context.MODE_PRIVATE)

    var hour: Int
        get() = p.getInt("hour", 6)
        set(v) = p.edit().putInt("hour", v).apply()

    var minute: Int
        get() = p.getInt("minute", 0)
        set(v) = p.edit().putInt("minute", v).apply()

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    /** Rep beep volume, 0-100. 0 turns the beep off. */
    var beepVolume: Int
        get() = p.getInt("beepVolume", 80)
        set(v) = p.edit().putInt("beepVolume", v).apply()

    var level: Level
        get() = runCatching { Level.valueOf(p.getString("level", Level.EASY.name)!!) }.getOrDefault(Level.EASY)
        set(v) = p.edit().putString("level", v.name).apply()

    var target: Int
        get() = p.getInt("target", 40)
        set(v) = p.edit().putInt("target", v).apply()
}
