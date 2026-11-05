package com.iraqtranslate

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Lightweight 64-bit spectral landmark fingerprints for playback continuity/seek detection. */
class AudioFingerprintEngine(private val sampleRate: Int = 16_000, private val frameMs: Int = 40) {
    data class Fingerprint(val sampleStart: Long, val hash: Long, val rms: Double)
    data class Observation(val fingerprint: Fingerprint, val repeated: Boolean, val similarity: Double)
    private val frameSize = sampleRate * frameMs / 1000
    private var pending = ShortArray(frameSize)
    private var count = 0
    private var sampleCursor = 0L
    private val history = ArrayDeque<Fingerprint>()

    @Synchronized fun accept(pcm: ShortArray): List<Observation> {
        val out = ArrayList<Observation>()
        for (sample in pcm) {
            pending[count++] = sample
            sampleCursor++
            if (count == frameSize) {
                val fp = fingerprint(pending, sampleCursor - frameSize)
                var best = 0.0
                for (old in history) {
                    if (fp.sampleStart - old.sampleStart < sampleRate * 3L / 2L) continue
                    val distance = java.lang.Long.bitCount(fp.hash xor old.hash)
                    best = maxOf(best, 1.0 - distance / 64.0)
                }
                val repeated = best >= 0.94 && fp.rms > 0.008
                out += Observation(fp, repeated, best)
                history.add(fp)
                while (history.size > 256) history.removeFirst()
                count = 0
            }
        }
        return out
    }

    @Synchronized fun reset() { count = 0; sampleCursor = 0L; history.clear() }

    private fun fingerprint(frame: ShortArray, start: Long): Fingerprint {
        val energies = DoubleArray(64)
        var rms = 0.0
        for (v in frame) { val x = v / 32768.0; rms += x * x }
        rms = sqrt(rms / frame.size)
        // 64 logarithmically spaced Goertzel bins cover speech fundamentals and formants.
        for (b in energies.indices) {
            val frequency = 90.0 * Math.pow(68.0, b / 63.0)
            val omega = 2.0 * PI * frequency / sampleRate
            val coeff = 2.0 * cos(omega)
            var s1 = 0.0
            var s2 = 0.0
            for (i in frame.indices) {
                val window = 0.5 - 0.5 * cos(2.0 * PI * i / (frame.size - 1))
                val s0 = frame[i] / 32768.0 * window + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            energies[b] = (s1 * s1 + s2 * s2 - coeff * s1 * s2).coerceAtLeast(0.0)
        }
        var hash = 0L
        // Adjacent spectral-energy comparisons create 63 robust landmark bits.
        for (i in 0 until 63) if (energies[i] >= energies[i + 1]) hash = hash or (1L shl i)
        return Fingerprint(start, hash, rms)
    }
}
