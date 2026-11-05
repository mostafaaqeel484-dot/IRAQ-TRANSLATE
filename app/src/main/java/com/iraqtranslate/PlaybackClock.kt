package com.iraqtranslate

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.SystemClock

/** Boot-time clock for captured PCM. BOOTTIME aligns AudioTimestamp.nanoTime with SystemClock.elapsedRealtimeNanos; it is not video PTS. */
class PlaybackClock(private val sampleRate: Int) {
    data class Snapshot(val elapsedRealtimeNs: Long, val framePosition: Long, val discontinuity: Boolean)
    private var lastFrame = -1L
    private var lastNs = -1L
    private var originFrame = -1L
    private var originNs = -1L

    @Synchronized fun observe(record: AudioRecord): Snapshot? {
        val timestamp = AudioTimestamp()
        if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) != AudioRecord.SUCCESS) return null
        val discontinuity = lastFrame >= 0 && (timestamp.framePosition < lastFrame || timestamp.nanoTime < lastNs ||
            (timestamp.framePosition - lastFrame) > sampleRate * 3L)
        if (originFrame < 0 || discontinuity) {
            originFrame = timestamp.framePosition
            originNs = timestamp.nanoTime
        }
        lastFrame = timestamp.framePosition
        lastNs = timestamp.nanoTime
        return Snapshot(timestamp.nanoTime, timestamp.framePosition, discontinuity)
    }

    @Synchronized fun elapsedMsForFrame(framePosition: Long): Long {
        if (originFrame < 0 || originNs < 0) return SystemClock.elapsedRealtime()
        return (originNs / 1_000_000L + (framePosition - originFrame) * 1000L / sampleRate)
            .coerceAtLeast(0L)
    }

    @Synchronized fun reset() { lastFrame = -1; lastNs = -1; originFrame = -1; originNs = -1 }
}
