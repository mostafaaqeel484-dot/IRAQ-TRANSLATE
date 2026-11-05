package com.iraqtranslate

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

data class SpeechModelSpec(
    val id: String,
    val label: String,
    val directory: String,
    val url: String,
    val sourceLanguage: String
)

class SpeechModelInstaller(private val context: Context) {
    companion object {
        val MODELS = listOf(
            model("en", "English (US)", "vosk-model-small-en-us-0.15", "en"),
            model("ar", "Arabic", "vosk-model-ar-mgb2-0.4", "ar"),
            model("tr", "Turkish", "vosk-model-small-tr-0.3", "tr"),
            model("de", "German", "vosk-model-small-de-0.15", "de"),
            model("es", "Spanish", "vosk-model-small-es-0.42", "es"),
            model("fa", "Persian", "vosk-model-small-fa-0.42", "fa"),
            model("fr", "French", "vosk-model-small-fr-0.22", "fr"),
            model("hi", "Hindi", "vosk-model-small-hi-0.22", "hi"),
            model("it", "Italian", "vosk-model-small-it-0.22", "it"),
            model("ja", "Japanese", "vosk-model-small-ja-0.22", "ja"),
            model("ko", "Korean", "vosk-model-small-ko-0.22", "ko"),
            model("nl", "Dutch", "vosk-model-small-nl-0.22", "nl"),
            model("pl", "Polish", "vosk-model-small-pl-0.22", "pl"),
            model("pt", "Portuguese", "vosk-model-small-pt-0.3", "pt"),
            model("ru", "Russian", "vosk-model-small-ru-0.22", "ru"),
            model("uk", "Ukrainian", "vosk-model-small-uk-v3-small", "uk"),
            model("vi", "Vietnamese", "vosk-model-small-vn-0.4", "vi"),
            model("zh", "Chinese", "vosk-model-small-cn-0.22", "zh"),
            model("cs", "Czech", "vosk-model-small-cs-0.4-rhasspy", "cs")
        )

        private fun model(id: String, label: String, directory: String, language: String) =
            SpeechModelSpec(
                id, label, directory,
                "https://alphacephei.com/vosk/models/$directory.zip",
                language
            )
        private const val PREFS = "iraq_translate"
        private const val PREF_SOURCE_MODEL = "source_model"
    }

    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }

    fun selectedModel(): SpeechModelSpec {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_SOURCE_MODEL, "en") ?: "en"
        return MODELS.firstOrNull { it.id == id } ?: MODELS.first()
    }

    fun selectModel(id: String) {
        require(MODELS.any { it.id == id })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_SOURCE_MODEL, id).apply()
    }

    fun modelForLanguage(languageCode: String): SpeechModelSpec? {
        val raw = languageCode.trim().lowercase().substringBefore('-').substringBefore('_')
        val normalized = mapOf(
            "english" to "en", "arabic" to "ar", "turkish" to "tr",
            "german" to "de", "spanish" to "es", "persian" to "fa",
            "farsi" to "fa", "french" to "fr", "hindi" to "hi",
            "italian" to "it", "japanese" to "ja", "korean" to "ko",
            "dutch" to "nl", "polish" to "pl", "portuguese" to "pt",
            "russian" to "ru", "ukrainian" to "uk", "vietnamese" to "vi",
            "chinese" to "zh", "czech" to "cs"
        )[raw] ?: raw
        return MODELS.firstOrNull { it.sourceLanguage == normalized }
    }

    fun installedModels(): List<SpeechModelSpec> = MODELS.filter(::isInstalled)

    fun modelDirectory(spec: SpeechModelSpec = selectedModel()): File =
        File(context.filesDir, spec.directory)

    fun isInstalled(spec: SpeechModelSpec = selectedModel()): Boolean {
        val dir = modelDirectory(spec)
        return File(dir, "conf/model.conf").isFile && File(dir, "am/final.mdl").isFile
    }

    fun install(spec: SpeechModelSpec, onProgress: (Int) -> Unit): File {
        cancelled.set(false)
        val archive = File(context.cacheDir, "${spec.directory}.zip.part")
        val staging = File(context.filesDir, "${spec.directory}.installing")
        archive.parentFile?.mkdirs()
        staging.deleteRecursively()
        try {
            val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IRAQ-TRANSLATE/1.0")
            }
            try {
                val length = connection.contentLengthLong
                if (connection.responseCode !in 200..299) error("Model download HTTP ${connection.responseCode}")
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var lastPercent = -1
                        while (true) {
                            if (cancelled.get()) error("Download cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            total += n
                            if (length > 0) {
                                val percent = ((total * 100) / length).toInt().coerceIn(0, 99)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            staging.mkdirs()
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled.get()) error("Extraction cancelled")
                    val entry = zip.nextEntry ?: break
                    val out = File(staging, entry.name)
                    val root = staging.canonicalFile
                    val canonicalOut = out.canonicalFile
                    require(canonicalOut.path.startsWith(root.path + File.separator) ||
                        canonicalOut == root) { "Unsafe ZIP entry: ${entry.name}" }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { output ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            val nested = File(staging, spec.directory)
            val source = if (File(nested, "conf/model.conf").isFile) nested else staging
            require(File(source, "conf/model.conf").isFile) { "Invalid Vosk model archive" }
            require(File(source, "am/final.mdl").isFile) { "Vosk acoustic model is missing" }

            val target = modelDirectory(spec)
            target.deleteRecursively()
            if (source == staging) {
                staging.renameTo(target) || error("Could not move extracted model")
            } else {
                nested.renameTo(target) || error("Could not move extracted model")
                staging.deleteRecursively()
            }
            onProgress(100)
            return target
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        } finally {
            archive.delete()
        }
    }
}
