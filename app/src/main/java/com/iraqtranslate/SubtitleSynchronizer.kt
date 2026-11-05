package com.iraqtranslate

import kotlin.math.floor

/**
 * Reproduces the reference's affine time mapping and one-second text buckets.
 * The live playback-capture API does not expose the source media PTS, so this
 * maps against the local monotonic audio-capture clock rather than claiming PTS sync.
 */
class SubtitleSynchronizer(
    private val scale: Double = 1.0,
    private val offsetMs: Double = 0.0
) {
    private val buckets = LinkedHashMap<Long, MutableList<String>>()

    @Synchronized
    fun mapTimestamp(captureElapsedMs: Long): Long =
        (scale * captureElapsedMs + offsetMs).toLong().coerceAtLeast(0L)

    @Synchronized
    fun append(text: String, captureElapsedMs: Long): String {
        val clean = ArabicTextNormalizer.displayText(text)
        if (clean.isBlank()) return ""
        val mapped = mapTimestamp(captureElapsedMs)
        val bucket = floor(mapped / 1000.0).toLong() * 1000L
        val items = buckets.getOrPut(bucket) { mutableListOf() }
        if (items.lastOrNull() != clean) items += clean
        while (buckets.size > 4) buckets.remove(buckets.keys.first())
        return items.joinToString(" ")
    }

    @Synchronized
    fun reset() = buckets.clear()
}
