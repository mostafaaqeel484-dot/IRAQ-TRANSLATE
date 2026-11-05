import com.iraqtranslate.AudioFingerprintEngine
import com.iraqtranslate.ReferenceAudioFingerprintMatcher
import com.iraqtranslate.VideoPtsClock

fun main() {
    val pts = VideoPtsClock()
    check(pts.update(10_000_000L, 1_000L, 1.0, true) != null)
    check(kotlin.math.abs((pts.positionMs(2_000L) ?: 0.0) - 11_000.0) < 1.0)
    val paused = pts.update(11_000_000L, 2_000L, 1.0, false)!!
    check(paused.positionMs(9_000L) == 11_000.0)
    val seek = pts.update(80_000_000L, 3_000L, 1.0, true)!!
    check(seek.discontinuity)

    val matcher = ReferenceAudioFingerprintMatcher(minimumHits = 5)
    val query = (0 until 10).map { i -> AudioFingerprintEngine.Fingerprint(i * 640L, (i * 7919L).toLong(), 0.1) }
    val references = query.map { q -> ReferenceAudioFingerprintMatcher.Reference("movie-1", q.sampleStart * 1000L / 16000 + 120_000L, q.hash) }
    matcher.replaceCatalogue(references)
    val result = matcher.match(query)
    check(result != null && result.mediaId == "movie-1")
    check(kotlin.math.abs(result!!.offsetMs - 120_000L) <= 1_200L)
    check(result.hits >= 5)

    // Similar fingerprints with inconsistent reference-time ordering must not create a false lock.
    val incoherentRefs = query.mapIndexed { i, q ->
        val wobble = if (i % 2 == 0) 0L else 500L
        ReferenceAudioFingerprintMatcher.Reference(
            "false-scene", 120_000L + q.sampleStart * 1000L / 16000 + wobble, q.hash
        )
    }
    matcher.replaceCatalogue(incoherentRefs)
    check(matcher.match(query) == null) { "temporally incoherent fingerprint sequence was accepted" }

    // The four-band index must still retrieve a reference when up to eight bits differ.
    val noisyQuery = (0 until 12).map { i ->
        AudioFingerprintEngine.Fingerprint(i * 640L, (0x123456789abcdef0L xor (i * 0x101010101010101L)), 0.1)
    }
    val noisyRefs = noisyQuery.map { q ->
        val noise = 0x0001000100010001L // one bit in each 16-bit band: total Hamming distance 4
        ReferenceAudioFingerprintMatcher.Reference("tt9999999:1:1", q.sampleStart * 1000L / 16000 + 240_000L, q.hash xor noise)
    }
    matcher.replaceCatalogue(noisyRefs)
    val noisyMatch = matcher.match(noisyQuery)
    check(noisyMatch != null && noisyMatch.mediaId == "tt9999999:1:1")
    check(kotlin.math.abs(noisyMatch!!.offsetMs - 240_000L) <= 1_200L)
    println("PASS: decoder PTS extrapolation/pause/seek, indexed noisy fingerprint matching, and media-offset consensus")
}
