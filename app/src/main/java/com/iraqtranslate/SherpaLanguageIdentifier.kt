package com.iraqtranslate

import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig

/** Whisper-based spoken-language identification, matching the Sherpa API used by the reference APK. */
class SherpaLanguageIdentifier(
    encoderPath: String,
    decoderPath: String
) : AutoCloseable {
    private val identifier: SpokenLanguageIdentification

    init {
        val whisper = SpokenLanguageIdentificationWhisperConfig.builder()
            .setEncoder(encoderPath)
            .setDecoder(decoderPath)
            .build()
        val config = SpokenLanguageIdentificationConfig.builder()
            .setWhisper(whisper)
            .setNumThreads(2)
            .setDebug(false)
            .build()
        identifier = SpokenLanguageIdentification(config)
    }

    @Synchronized
    fun detect(samples: FloatArray, sampleRate: Int = 16000): String {
        require(samples.isNotEmpty()) { "No audio samples for language identification" }
        val stream = identifier.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            identifier.compute(stream).trim().lowercase()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        identifier.release()
    }
}
