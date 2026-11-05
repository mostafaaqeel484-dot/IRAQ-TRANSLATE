package com.iraqtranslate

import java.text.Normalizer

/**
 * Conservative pre-activation check for downloaded Arabic subtitle candidates.
 * It compares recent translated dialogue phrases with short windows of subtitle cues.
 * This is a rejection aid, not a proof of media identity or forced alignment.
 */
class SubtitleCandidateScorer {
    data class Score(val confidence: Double, val matchedWords: Int, val evidenceWords: Int)

    data class Cue(val text: String)

    fun score(dialogueSamples: List<String>, cues: List<Cue>): Score {
        if (dialogueSamples.isEmpty() || cues.isEmpty()) return Score(0.0, 0, 0)
        val phrases = dialogueSamples.map(::tokenize).filter { it.size >= 4 }.takeLast(8)
        if (phrases.isEmpty()) return Score(0.0, 0, 0)

        var best = Score(0.0, 0, 0)
        val tokenizedCues = cues.map { tokenize(it.text) }
        // Compare each phrase and adjacent phrase pair. Never combine dialogue from distant scenes.
        val targets = ArrayList<List<String>>()
        targets += phrases
        for (i in 0 until phrases.lastIndex) {
            targets += (phrases[i] + phrases[i + 1]).distinct().take(32)
        }

        for (targetTokens in targets) {
            val target = targetTokens.distinct().take(32)
            if (target.size < 4) continue
            val targetSet = target.toSet()
            for (i in cues.indices) {
                val windowTokens = ArrayList<String>(48)
                for (j in i..minOf(cues.lastIndex, i + 2)) {
                    windowTokens += tokenizedCues[j]
                    if (windowTokens.size > 70) break
                }
                if (windowTokens.size < 4) continue
                val windowSet = windowTokens.toSet()
                val matched = targetSet.intersect(windowSet).size
                val recall = matched.toDouble() / targetSet.size.coerceAtLeast(1)
                val precision = matched.toDouble() / windowSet.size.coerceAtLeast(1)
                val dice = if (recall + precision == 0.0) 0.0
                    else 2.0 * recall * precision / (recall + precision)
                if (dice > best.confidence) best = Score(dice, matched, targetSet.size)
            }
        }
        return best
    }

    private fun tokenize(value: String): List<String> {
        val normalized = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKC)
            .replace(Regex("[\u064B-\u065F\u0670]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return normalized.split(Regex("\\s+")).filter { it.length > 1 }
    }
}
