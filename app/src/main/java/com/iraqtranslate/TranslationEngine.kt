package com.iraqtranslate

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Network translation adapter.
 * Uses Google's public web translation endpoint (no API key).
 * This endpoint is not a contracted/stable API; production deployments should
 * replace it with a provider configured by the app owner.
 */
class TranslationEngine {
    fun toArabic(source: String, sourceLanguage: String = "auto"): String {
        val text = source.trim()
        if (text.isEmpty()) return ""
        val query = URLEncoder.encode(text, Charsets.UTF_8.name())
        val sl = URLEncoder.encode(sourceLanguage, Charsets.UTF_8.name())
        val endpoint = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=$sl&tl=ar&dt=t&q=$query"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("User-Agent", "Mozilla/5.0 IRAQ-TRANSLATE/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Translation server returned HTTP $code")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONArray(body)
            val segments = root.optJSONArray(0) ?: return ""
            return buildString {
                for (i in 0 until segments.length()) {
                    val segment = segments.optJSONArray(i) ?: continue
                    append(segment.optString(0))
                }
            }.trim()
        } finally {
            connection.disconnect()
        }
    }
}
