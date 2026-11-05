package com.iraqtranslate

import android.content.Context

/** A verified user choice or an explicitly selected EPG programme; never silently substitutes a random IMDb hit. */
data class MediaSelection(
    val title: String,
    val imdbId: String = "",
    val year: Int = 0,
    val season: Int = -1,
    val episode: Int = -1,
    val source: String = "manual",
    val expiresAtMs: Long = 0L
) {
    val identity: String get() = if (imdbId.matches(Regex("tt\\d+")))
        "$imdbId:$season:$episode" else "title:${title.trim().lowercase()}:$year:$season:$episode"
    fun isUsable(nowMs: Long = System.currentTimeMillis()): Boolean =
        title.isNotBlank() && (expiresAtMs == 0L || nowMs < expiresAtMs)
}

/** Private app preferences; no license keys or server secrets stored here. */
object MediaSelectionStore {
    private const val PREFS = "iraq_media_selection_v2"
    fun save(context: Context, selection: MediaSelection) {
        require(selection.title.isNotBlank())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", selection.title.take(180))
            .putString("imdb", selection.imdbId.take(32))
            .putInt("year", selection.year)
            .putInt("season", selection.season)
            .putInt("episode", selection.episode)
            .putString("source", selection.source.take(40))
            .putLong("expires", selection.expiresAtMs)
            .apply()
    }
    fun get(context: Context): MediaSelection? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString("title", "").orEmpty()
        if (title.isBlank()) return null
        return MediaSelection(
            title, prefs.getString("imdb", "").orEmpty(), prefs.getInt("year", 0),
            prefs.getInt("season", -1), prefs.getInt("episode", -1),
            prefs.getString("source", "manual") ?: "manual", prefs.getLong("expires", 0L)
        ).takeIf { it.isUsable() }
    }
    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}
