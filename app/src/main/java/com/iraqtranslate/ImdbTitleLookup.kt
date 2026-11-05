package com.iraqtranslate

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public IMDb autocomplete, best-effort only. Selecting an IMDb title never grants subtitle rights. */
object ImdbTitleLookup {
    data class Hit(val id: String, val title: String, val year: Int)
    fun search(raw: String): List<Hit> {
        val query = raw.trim().take(90)
        require(query.length >= 2)
        val first = URLEncoder.encode(query.take(1).lowercase(), "UTF-8")
        val encoded = URLEncoder.encode(query.lowercase(), "UTF-8")
        val conn = URL("https://v3.sg.media-imdb.com/suggestion/$first/$encoded.json").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            val content = conn.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(content).optJSONArray("d") ?: return emptyList()
            return (0 until minOf(items.length(), 12)).mapNotNull { i ->
                val row = items.optJSONObject(i) ?: return@mapNotNull null
                val id = row.optString("id")
                val title = row.optString("l")
                val year = row.optInt("y", 0)
                if (id.matches(Regex("tt\\d+")) && title.isNotBlank()) Hit(id, title, year) else null
            }
        } finally { conn.disconnect() }
    }
}
