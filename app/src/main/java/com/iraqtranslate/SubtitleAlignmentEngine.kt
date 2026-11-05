package com.iraqtranslate

import java.text.Normalizer
import kotlin.math.abs

/** Word-level fuzzy alignment between recognized Arabic dialogue and Arabic subtitle cues. */
class SubtitleAlignmentEngine {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)
    data class Anchor(val captureMs: Double, val subtitleMs: Double, val confidence: Double)
    data class Result(
        val confidence: Double,
        val matchedWords: Int,
        val scale: Double,
        val offsetMs: Double,
        val subtitleAnchorMs: Double
    )

    private val anchors = ArrayDeque<Anchor>()
    private var scale = 1.0
    private var offsetMs = 0.0

    @Synchronized fun reset() { anchors.clear(); scale = 1.0; offsetMs = 0.0 }

    @Synchronized fun align(dialogue: String, captureElapsedMs: Long, sessionStartElapsedMs: Long, cues: List<Cue>): Result? {
        val spoken = tokenize(dialogue)
        if (spoken.size < 3 || cues.isEmpty()) return null
        val projected = (captureElapsedMs - sessionStartElapsedMs).coerceAtLeast(0L) * scale + offsetMs
        val candidates = cues.withIndex().filter { abs(it.value.startMs - projected) <= 180_000L }
            .ifEmpty { cues.withIndex().toList() }
        var bestScore = 0.0
        var bestMatched = 0
        var bestStartCue: Cue? = null
        var bestEndCue: Cue? = null
        for ((idx, _) in candidates) {
            val combined = ArrayList<Pair<String, Cue>>()
            for (j in idx.coerceAtLeast(0)..minOf(cues.lastIndex, idx + 4)) {
                for (token in tokenize(cues[j].text)) combined += token to cues[j]
            }
            if (combined.isEmpty()) continue
            val target = combined.map { it.first }
            val maxLen = minOf(target.size, spoken.size + 3)
            for (start in target.indices) {
                for (len in maxOf(1, spoken.size - 3)..maxLen) {
                    if (start + len > target.size) break
                    val window = target.subList(start, start + len)
                    val distance = levenshtein(spoken, window)
                    val denominator = maxOf(spoken.size, window.size).coerceAtLeast(1)
                    val similarity = 1.0 - distance.toDouble() / denominator
                    val overlap = spoken.toSet().intersect(window.toSet()).size.toDouble() / spoken.toSet().size.coerceAtLeast(1)
                    val score = (similarity * 0.72 + overlap * 0.28).coerceIn(0.0, 1.0)
                    if (score > bestScore) {
                        bestScore = score
                        bestMatched = spoken.toSet().intersect(window.toSet()).size
                        bestStartCue = combined[start].second
                        bestEndCue = combined[start + len - 1].second
                    }
                }
            }
        }
        if (bestStartCue == null) return null
        val lastCue = bestEndCue ?: return null
        if (bestScore < 0.68 || bestMatched < 3) return null
        // Vosk's supplied capture timestamp is the final recognized word's end time;
        // anchor it to the end of the last matched subtitle cue, not the cue midpoint.
        val subtitleAnchor = lastCue.endMs.toDouble()
        val captureAnchor = (captureElapsedMs - sessionStartElapsedMs).coerceAtLeast(0L).toDouble()
        val duplicate = anchors.lastOrNull()?.let {
            abs(it.captureMs - captureAnchor) < 4_000.0 || abs(it.subtitleMs - subtitleAnchor) < 2_000.0
        } == true
        if (!duplicate) anchors.add(Anchor(captureAnchor, subtitleAnchor, bestScore))
        while (anchors.size > 32) anchors.removeFirst()
        fit()
        return Result(bestScore, bestMatched, scale, offsetMs, subtitleAnchor)
    }

    @Synchronized fun addReferenceAnchor(captureRelativeMs: Double, subtitleMs: Double) {
        if (captureRelativeMs < 0.0 || subtitleMs < 0.0) return
        val last = anchors.lastOrNull()
        if (last != null && (abs(last.captureMs - captureRelativeMs) < 3_000.0 ||
                abs(last.subtitleMs - subtitleMs) < 2_000.0)) return
        anchors.add(Anchor(captureRelativeMs, subtitleMs, 0.72))
        while (anchors.size > 32) anchors.removeFirst()
        fit()
    }

    @Synchronized fun currentScale() = scale
    @Synchronized fun currentOffsetMs() = offsetMs
    @Synchronized fun anchorCount() = anchors.size

    private fun fit() {
        if (anchors.isEmpty()) return
        val points = anchors.toList()
        val slopes = ArrayList<Double>()
        for (i in points.indices) for (j in i + 1 until points.size) {
            val dx = points[j].captureMs - points[i].captureMs
            val dy = points[j].subtitleMs - points[i].subtitleMs
            if (dx >= 15_000.0 && dy > 0) {
                val s = dy / dx
                if (s in 0.80..1.20) slopes += s
            }
        }
        if (slopes.isNotEmpty()) scale = median(slopes).coerceIn(0.80, 1.20)
        val offsets = points.map { it.subtitleMs - scale * it.captureMs }
        offsetMs = median(offsets)
    }

    private fun tokenize(value: String): List<String> {
        val normalized = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKC)
            .replace(Regex("[\u064B-\u065F\u0670]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return normalized.split(Regex("\\s+")).filter { it.length > 1 }
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.size]
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 1.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
