import com.iraqtranslate.AudioFingerprintEngine
import java.io.RandomAccessFile
import java.io.File

/**
 * Build a reference_fingerprints.csv from a 16 kHz, mono, PCM16 WAV file.
 * Usage: java -jar build-fingerprint-catalogue.jar <mediaId> <input.wav> <output.csv>
 * mediaId must be exactly the app identity, e.g. tt1234567:1:2 or tt1234567:-1:-1.
 */
fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16))

fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: <mediaId> <input.wav> <output.csv>" }
    val mediaId = args[0]
    require(mediaId.isNotBlank() && !mediaId.contains(',')) { "mediaId must be non-empty and contain no comma" }
    val input = File(args[1])
    val output = File(args[2])
    require(input.isFile) { "Input WAV not found: $input" }

    RandomAccessFile(input, "r").use { wav ->
        val header = ByteArray(12)
        wav.readFully(header)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a RIFF/WAVE file" }
        var formatOk = false
        var dataOffset = -1L
        var dataSize = 0L
        while (wav.filePointer + 8 <= wav.length()) {
            val chunkHeader = ByteArray(8)
            wav.readFully(chunkHeader)
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = u32(chunkHeader, 4)
            val chunkStart = wav.filePointer
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV fmt chunk" }
                    val fmt = ByteArray(16)
                    wav.readFully(fmt)
                    val encoding = u16(fmt, 0)
                    val channels = u16(fmt, 2)
                    val rate = u32(fmt, 4)
                    val bits = u16(fmt, 14)
                    require(encoding == 1 && channels == 1 && rate == 16000L && bits == 16) {
                        "WAV must be PCM16, mono, 16000 Hz; got encoding=$encoding channels=$channels rate=$rate bits=$bits"
                    }
                    formatOk = true
                }
                "data" -> { dataOffset = chunkStart; dataSize = size }
            }
            wav.seek(chunkStart + size + (size and 1L))
        }
        require(formatOk && dataOffset >= 0L && dataSize > 0L) { "WAV is missing a supported fmt or data chunk" }

        val engine = AudioFingerprintEngine(16_000, 40)
        output.bufferedWriter().use { writer ->
            writer.appendLine("mediaId,mediaTimeMs,hashHex")
            wav.seek(dataOffset)
            var remaining = dataSize
            val bytes = ByteArray(8192)
            while (remaining >= 2L) {
                val count = wav.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt() and -2)
                if (count <= 0) break
                remaining -= count
                val samples = ShortArray(count / 2) { i ->
                    ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                }
                for (observation in engine.accept(samples)) {
                    val fp = observation.fingerprint
                    val timeMs = fp.sampleStart * 1000L / 16_000L
                    val hash = java.lang.Long.toUnsignedString(fp.hash, 16).padStart(16, '0')
                    writer.append(mediaId).append(',').append(timeMs.toString()).append(',').appendLine(hash)
                }
            }
        }
    }
    println("Wrote reference fingerprint catalogue: ${output.absolutePath}")
}
