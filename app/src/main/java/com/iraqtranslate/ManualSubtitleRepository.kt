package com.iraqtranslate

import android.content.Context
import java.io.File

/** User-supplied local SRT. Avoids undocumented third-party activation and API endpoints. */
object ManualSubtitleRepository {
    private const val PREFS = "iraq_manual_srt"
    private const val FILE = "user_subtitles.srt"
    private const val KEY_NAME = "name"
    private const val KEY_ACTIVE = "enabled"

    fun save(context: Context, name: String, bytes: ByteArray): Int {
        require(bytes.isNotEmpty() && bytes.size <= 5_000_000) { "الملف فارغ أو حجمه أكبر من 5 ميغابايت" }
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        require(Regex("(?m)\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->").containsMatchIn(text)) {
            "لا يظهر أن الملف بتنسيق SRT صالح"
        }
        val cueCount = text.lines().count { "-->" in it }
        require(cueCount >= 2) { "يجب أن يحتوي ملف SRT على توقيتين على الأقل" }
        val file = File(context.filesDir, FILE)
        file.writeText(text, Charsets.UTF_8)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, name.take(160)).putBoolean(KEY_ACTIVE, true).apply()
        return cueCount
    }
    fun name(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .takeIf { it.getBoolean(KEY_ACTIVE, false) && File(context.filesDir, FILE).isFile }
        ?.getString(KEY_NAME, null)
    fun content(context: Context): String? = if (name(context) != null) File(context.filesDir, FILE).readText() else null
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        File(context.filesDir, FILE).delete()
    }
}
