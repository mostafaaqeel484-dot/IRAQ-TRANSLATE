package com.iraqtranslate

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Stateful 63-tap low-pass FIR and 3:1 decimator.
 * Input: interleaved stereo PCM16 at 48 kHz. Output: mono PCM16 at 16 kHz.
 * The reference APK exposes a 63-tap filter, but its exact coefficients were not
 * recoverable from the available DEX evidence; these are designed coefficients.
 */
class Fir63Resampler {
    companion object {
        private const val TAPS = 63
        private const val DECIMATION = 3
        private val coefficients: DoubleArray = designLowPass()

        private fun designLowPass(): DoubleArray {
            val cutoff = 0.145 // cycles/input-sample; approximately 6.96 kHz at 48 kHz
            val h = DoubleArray(TAPS)
            val center = (TAPS - 1) / 2.0
            for (n in 0 until TAPS) {
                val m = n - center
                val ideal = if (m == 0.0) 2.0 * cutoff
                else sin(2.0 * PI * cutoff * m) / (PI * m)
                val window = 0.54 - 0.46 * cos(2.0 * PI * n / (TAPS - 1))
                h[n] = ideal * window
            }
            val sum = h.sum()
            return DoubleArray(TAPS) { h[it] / sum }
        }
    }

    private val delay = DoubleArray(TAPS)
    private var writeIndex = 0
    private var phase = 0

    @Synchronized
    fun processStereoPcm16(input: ByteArray, byteCount: Int): ShortArray {
        val frames = byteCount.coerceAtLeast(0) / 4
        if (frames == 0) return ShortArray(0)
        val output = ShortArray((frames + 2) / DECIMATION)
        var outIndex = 0
        var offset = 0
        repeat(frames) {
            val left = ((input[offset].toInt() and 0xff) or (input[offset + 1].toInt() shl 8)).toShort().toInt()
            val right = ((input[offset + 2].toInt() and 0xff) or (input[offset + 3].toInt() shl 8)).toShort().toInt()
            offset += 4
            delay[writeIndex] = ((left + right) / 2.0) / 32768.0
            writeIndex = (writeIndex + 1) % TAPS
            phase = (phase + 1) % DECIMATION
            if (phase == 0) {
                var value = 0.0
                for (tap in 0 until TAPS) {
                    value += coefficients[tap] * delay[(writeIndex + tap) % TAPS]
                }
                output[outIndex++] = (value.coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()
            }
        }
        return if (outIndex == output.size) output else output.copyOf(outIndex)
    }

    fun reset() {
        delay.fill(0.0)
        writeIndex = 0
        phase = 0
    }
}

internal fun ShortArray.toPcm16Le(): ByteArray {
    val bytes = ByteArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt()
        bytes[i * 2] = (value and 0xff).toByte()
        bytes[i * 2 + 1] = ((value ushr 8) and 0xff).toByte()
    }
    return bytes
}

internal fun ShortArray.toFloatSamples(): FloatArray = FloatArray(size) { this[it] / 32768.0f }

/** Reads interleaved signed PCM32 stereo frames from the ADB Echo Reference helper. */
class Pcm32StereoTo16kReader(
    private val input: java.io.InputStream,
    private val resampler: Fir63Resampler
) {
    private var carry = ByteArray(0)
    private val incoming = ByteArray(384000)

    fun read(): ShortArray? {
        val count = input.read(incoming)
        if (count < 0) return null
        if (count == 0) return ShortArray(0)
        val joined = ByteArray(carry.size + count)
        carry.copyInto(joined)
        incoming.copyInto(joined, carry.size, 0, count)
        val alignedBytes = joined.size - (joined.size % 8) // 2 channels × 4 bytes/sample
        carry = joined.copyOfRange(alignedBytes, joined.size)
        if (alignedBytes == 0) return ShortArray(0)
        val pcm16 = ByteArray(alignedBytes / 2)
        var source = 0
        var target = 0
        while (source < alignedBytes) {
            val sample32 = (joined[source].toInt() and 0xff) or
                ((joined[source + 1].toInt() and 0xff) shl 8) or
                ((joined[source + 2].toInt() and 0xff) shl 16) or
                (joined[source + 3].toInt() shl 24)
            val sample16 = (sample32 shr 16) and 0xffff
            pcm16[target++] = (sample16 and 0xff).toByte()
            pcm16[target++] = ((sample16 ushr 8) and 0xff).toByte()
            source += 4
        }
        return resampler.processStereoPcm16(pcm16, pcm16.size)
    }
}
