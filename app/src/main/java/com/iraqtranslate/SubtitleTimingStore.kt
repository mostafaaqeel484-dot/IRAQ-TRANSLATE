package com.iraqtranslate

import android.content.Context

object SubtitleTimingStore {
    private const val PREFS = "iraq_translate_timing"
    private const val KEY_DELAY = "manual_delay_ms"
    fun get(context: Context): Long = SubtitleTimingMath.clamp(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DELAY, 0L)
    )
    fun set(context: Context, delayMs: Long): Long {
        val value = SubtitleTimingMath.clamp(delayMs)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DELAY, value).apply()
        return value
    }
    fun adjust(context: Context, deltaMs: Long): Long = set(context, get(context) + deltaMs)
}
