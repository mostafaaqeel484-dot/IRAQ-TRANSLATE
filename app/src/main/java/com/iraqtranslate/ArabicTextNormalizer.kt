package com.iraqtranslate

import java.text.Normalizer

/** Normalizes Unicode without changing the meaning of Arabic letters. */
object ArabicTextNormalizer {
    fun displayText(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFC)
            .replace('\u0640'.toString(), "") // tatweel
            .replace(Regex("[\\u200B-\\u200F\\uFEFF]"), "")
            .replace(Regex("[\\t\\r\\n ]+"), " ")
            .trim()
        return normalized
    }
}
