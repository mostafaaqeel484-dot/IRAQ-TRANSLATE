package com.iraqtranslate

/**
 * Sherpa-ONNX Whisper language identification.
 * Reflection avoids compile-time builder API mismatches between AAR builds.
 */
class SherpaLanguageIdentifier(
    encoderPath: String,
    decoderPath: String
) : AutoCloseable {
    private val identifier: Any

    init {
        val whisperClass = Class.forName("com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig")
        val whisperBuilder = whisperClass.getMethod("builder").invoke(null)
        whisperBuilder.javaClass.getMethod("setEncoder", String::class.java)
            .invoke(whisperBuilder, encoderPath)
        whisperBuilder.javaClass.getMethod("setDecoder", String::class.java)
            .invoke(whisperBuilder, decoderPath)
        val whisper = whisperBuilder.javaClass.getMethod("build").invoke(whisperBuilder)

        val configClass = Class.forName("com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig")
        val configBuilder = configClass.getMethod("builder").invoke(null)
        configBuilder.javaClass.methods.first {
            it.name == "setWhisper" && it.parameterCount == 1
        }.invoke(configBuilder, whisper)
        configBuilder.javaClass.getMethod(
            "setNumThreads", Int::class.javaPrimitiveType
        ).invoke(configBuilder, 2)
        configBuilder.javaClass.getMethod(
            "setDebug", Boolean::class.javaPrimitiveType
        ).invoke(configBuilder, false)
        val config = configBuilder.javaClass.getMethod("build").invoke(configBuilder)

        val identifierClass = Class.forName(
            "com.k2fsa.sherpa.onnx.SpokenLanguageIdentification"
        )
        identifier = identifierClass.constructors.first {
            it.parameterCount == 1
        }.newInstance(config)
    }

    @Synchronized
    fun detect(samples: FloatArray, sampleRate: Int = 16000): String {
        require(samples.isNotEmpty()) { "No audio samples for language identification" }
        val stream = identifier.javaClass.getMethod("createStream").invoke(identifier)
        return try {
            stream.javaClass.getMethod(
                "acceptWaveform",
                FloatArray::class.java,
                Int::class.javaPrimitiveType
            ).invoke(stream, samples, sampleRate)

            identifier.javaClass.methods.first {
                it.name == "compute" && it.parameterCount == 1
            }.invoke(identifier, stream).toString().trim().lowercase()
        } finally {
            stream.javaClass.getMethod("release").invoke(stream)
        }
    }

    override fun close() {
        identifier.javaClass.getMethod("release").invoke(identifier)
    }
}
