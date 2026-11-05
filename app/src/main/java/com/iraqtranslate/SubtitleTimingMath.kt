package com.iraqtranslate

/** Positive delay makes subtitles appear later than the unmodified media timeline. */
object SubtitleTimingMath {
    const val MAX_DELAY_MS = 15_000L
    fun clamp(delayMs: Long): Long = delayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
    fun effectiveMediaTime(mediaMs: Long, delayMs: Long): Long =
        mediaMs - clamp(delayMs) // Negative time intentionally suppresses a cue at t=0 until its delay elapses.
    fun format(delayMs: Long): String = when {
        delayMs > 0L -> "+%.2f ثانية (تأخير)".format(java.util.Locale.US, delayMs / 1000.0)
        delayMs < 0L -> "%.2f ثانية (تقديم)".format(java.util.Locale.US, delayMs / 1000.0)
        else -> "0.00 ثانية (متزامنة)"
    }
}

