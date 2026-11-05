package com.iraqtranslate

import kotlin.math.abs

/** Decoder-fed video presentation clock. PTS is only real when supplied by the player's decoder/render pipeline. */
class VideoPtsClock {
    data class Snapshot(val ptsUs: Long, val observedElapsedMs: Long, val speed: Double, val playing: Boolean, val discontinuity: Boolean) {
        fun positionMs(nowElapsedMs: Long): Double {
            val delta = if (playing) (nowElapsedMs - observedElapsedMs).coerceAtLeast(0L) * speed else 0.0
            return ptsUs / 1000.0 + delta
        }
    }

    @Volatile private var latest: Snapshot? = null

    @Synchronized fun update(ptsUs: Long, elapsedMs: Long, speed: Double, playing: Boolean): Snapshot? {
        if (ptsUs < 0L || elapsedMs < 0L || !speed.isFinite() || speed !in 0.0..4.0) return null
        val previous = latest
        val elapsedDelta = previous?.let { (elapsedMs - it.observedElapsedMs).coerceAtLeast(0L) } ?: 0L
        val expected = previous?.positionMs(elapsedMs)?.times(1000.0)
        val discontinuity = previous != null && (elapsedMs < previous.observedElapsedMs ||
            expected == null || abs(ptsUs - expected) > 2_500_000.0 || (playing && elapsedDelta > 10_000L))
        return Snapshot(ptsUs, elapsedMs, speed, playing, discontinuity).also { latest = it }
    }

    fun positionMs(nowElapsedMs: Long): Double? = latest?.let { it.positionMs(nowElapsedMs) }
    fun latest(): Snapshot? = latest
    @Synchronized fun reset() { latest = null }
}
