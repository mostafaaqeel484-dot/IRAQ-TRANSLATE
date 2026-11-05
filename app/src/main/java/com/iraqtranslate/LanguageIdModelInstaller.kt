package com.iraqtranslate

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the multilingual Whisper-tiny encoder/decoder used by Sherpa-ONNX LID. */
class LanguageIdModelInstaller(context: Context) {
    private val root = File(context.filesDir, "sherpa-onnx-whisper-tiny")
    private val encoder = File(root, "tiny-encoder.int8.onnx")
    private val decoder = File(root, "tiny-decoder.int8.onnx")

    fun isInstalled(): Boolean = encoder.isFile && encoder.length() > 1_000_000L &&
        decoder.isFile && decoder.length() > 1_000_000L

    fun encoderPath(): String = encoder.absolutePath
    fun decoderPath(): String = decoder.absolutePath

    fun install(onProgress: (String, Int) -> Unit = { _, _ -> }) {
        root.mkdirs()
        download(
            "tiny-encoder.int8.onnx",
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
            onProgress
        )
        download(
            "tiny-decoder.int8.onnx",
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
            onProgress
        )
        check(isInstalled()) { "Whisper language-identification model files are incomplete" }
    }

    private fun download(name: String, url: String, onProgress: (String, Int) -> Unit) {
        val target = File(root, name)
        if (target.isFile && target.length() > 1_000_000L) {
            onProgress(name, 100)
            return
        }
        val part = File(root, "$name.part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "IRAQ-TRANSLATE/1.0")
        }
        try {
            val status = connection.responseCode
            check(status in 200..299) { "Model download HTTP $status ($name)" }
            val length = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    var last = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        if (length > 0) {
                            val percent = ((total * 100) / length).toInt().coerceIn(0, 100)
                            if (percent != last) {
                                last = percent
                                onProgress(name, percent)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            check(part.length() > 1_000_000L) { "Downloaded model file is unexpectedly small: $name" }
            target.delete()
            check(part.renameTo(target)) { "Could not finalize model file: $name" }
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }
}
