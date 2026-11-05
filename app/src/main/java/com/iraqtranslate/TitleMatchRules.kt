package com.iraqtranslate

import java.text.Normalizer

/** Conservative EPG -> IMDb resolution: never assume IMDb's first suggestion is correct. */
object TitleMatchRules {
    data class Hit(val id: String, val title: String, val year: Int)
    fun normalized(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKC)
        .replace(Regex("\\s*\\(\\d{4}\\)\\s*"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    fun uniqueExactMatch(epgTitle: String, hits: List<Hit>): Hit? {
        val target = normalized(epgTitle)
        if (target.length < 3) return null
        val exact = hits.filter { normalized(it.title) == target }
        return exact.singleOrNull()
    }
}
