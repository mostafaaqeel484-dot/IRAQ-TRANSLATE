package com.iraqtranslate

import android.content.Context
import java.io.File

/** Store only the user's XMLTV guide in app-private storage (no access to a system DVB tuner). */
object EpgRepository {
    private const val FILE = "xmltv_guide.xml"
    private const val PREFS = "iraq_xmltv_guide"
    @Volatile private var cache: Pair<Long, XmlTvGuide.Guide>? = null

    fun importGuide(context: Context, bytes: ByteArray): Pair<Int, Int> {
        require(bytes.isNotEmpty() && bytes.size <= 8_000_000) { "دليل البرامج فارغ أو أكبر من 8 ميغابايت" }
        val guide = XmlTvGuide.parse(bytes.inputStream())
        require(guide.channels.isNotEmpty() && guide.programmes.isNotEmpty()) { "ملف XMLTV لا يحتوي على قنوات وبرامج صالحة" }
        File(context.filesDir, FILE).writeBytes(bytes)
        cache = null
        return guide.channels.size to guide.programmes.size
    }
    fun guide(context: Context): XmlTvGuide.Guide? {
        val file = File(context.filesDir, FILE)
        if (!file.isFile || file.length() !in 1L..8_000_000L) return null
        val existing = cache
        if (existing != null && existing.first == file.lastModified()) return existing.second
        return synchronized(this) {
            val current = cache
            if (current != null && current.first == file.lastModified()) current.second
            else runCatching { file.inputStream().use { XmlTvGuide.parse(it) } }.getOrNull()?.also {
                cache = file.lastModified() to it
            }
        }
    }
    fun setChannel(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("channel", id).apply()
    }
    fun clearChannel(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("channel", "").apply()
    }
    fun selectedChannel(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("channel", "").orEmpty()
    fun currentlyPlaying(context: Context, nowMs: Long = System.currentTimeMillis()): XmlTvGuide.Programme? {
        val channel = selectedChannel(context)
        return if (channel.isBlank()) null else guide(context)?.now(channel, nowMs)
    }
}
