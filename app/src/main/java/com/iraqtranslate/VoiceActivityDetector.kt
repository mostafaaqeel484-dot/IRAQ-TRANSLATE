package com.iraqtranslate

import kotlin.math.sqrt

/** Lightweight streaming 20 ms energy/ZCR VAD for 16 kHz playback PCM. */
class VoiceActivityDetector(
    private val sampleRate: Int = 16_000,
    private val frameMs: Int = 20,
    private val hangoverMs: Int = 300,
    private val minSpeechMs: Int = 160,
    private val preRollMs: Int = 40
) {
    data class Segment(val startSample: Long, val endSample: Long)
    private val frameSamples = (sampleRate * frameMs / 1000).coerceAtLeast(1)
    private val hangoverFrames = (hangoverMs / frameMs).coerceAtLeast(1)
    private val minSpeechFrames = (minSpeechMs / frameMs).coerceAtLeast(1)
    private val preRollSamples = (sampleRate * preRollMs / 1000).toLong()
    private val pending = ShortArray(frameSamples)
    private var pendingCount = 0
    private var totalSamples = 0L
    private var noiseRms = 0.003
    private var activeStartSample: Long? = null
    private var lastVoiceFrameEnd = 0L
    private var activeFrames = 0
    private var hangover = 0

    @Synchronized fun accept(pcm: ShortArray): List<Segment> {
        val emitted = ArrayList<Segment>()
        for (sample in pcm) {
            pending[pendingCount++] = sample
            totalSamples++
            if (pendingCount == frameSamples) {
                processFrame(emitted)
                pendingCount = 0
            }
        }
        return emitted
    }

    @Synchronized fun flush(): List<Segment> {
        val emitted = ArrayList<Segment>()
        if (pendingCount > 0) {
            while (pendingCount < frameSamples) pending[pendingCount++] = 0
            processFrame(emitted)
            pendingCount = 0
        }
        closeSegment(emitted, totalSamples)
        return emitted
    }

    @Synchronized fun reset() {
        pendingCount = 0
        totalSamples = 0L
        noiseRms = 0.003
        activeStartSample = null
        lastVoiceFrameEnd = 0L
        activeFrames = 0
        hangover = 0
    }

    private fun processFrame(out: MutableList<Segment>) {
        var energy = 0.0
        var crossings = 0
        var previous = pending[0].toInt()
        for (i in 0 until frameSamples) {
            val value = pending[i].toInt()
            val normalized = value / 32768.0
            energy += normalized * normalized
            if ((value >= 0 && previous < 0) || (value < 0 && previous >= 0)) crossings++
            previous = value
        }
        val rms = sqrt(energy / frameSamples)
        val zcr = crossings.toDouble() / frameSamples
        val voice = rms >= maxOf(0.008, noiseRms * 2.7) && zcr in 0.008..0.42
        val frameEnd = totalSamples
        val frameStart = (frameEnd - frameSamples).coerceAtLeast(0L)
        if (voice) {
            if (activeStartSample == null) {
                activeStartSample = (frameStart - preRollSamples).coerceAtLeast(0L)
                activeFrames = 0
            }
            activeFrames++
            hangover = hangoverFrames
            lastVoiceFrameEnd = frameEnd
        } else if (activeStartSample != null) {
            hangover--
            if (hangover <= 0) closeSegment(out, lastVoiceFrameEnd)
        } else {
            noiseRms = (noiseRms * 0.96 + rms * 0.04).coerceIn(0.0005, 0.08)
        }
    }

    private fun closeSegment(out: MutableList<Segment>, endSample: Long) {
        val start = activeStartSample ?: return
        if (activeFrames >= minSpeechFrames && endSample > start) out += Segment(start, endSample)
        activeStartSample = null
        activeFrames = 0
        hangover = 0
    }
}
