package com.iraqtranslate

import kotlin.math.abs

/** Matches captured spectral landmarks against a supplied reference catalogue. */
class ReferenceAudioFingerprintMatcher(
    private val maxHammingDistance: Int = 8,
    private val minimumHits: Int = 5,
    private val offsetToleranceMs: Long = 1_200L
) {
    data class Reference(val mediaId: String, val mediaTimeMs: Long, val hash: Long)
    data class Match(
        val mediaId: String,
        val offsetMs: Long,
        val hits: Int,
        val confidence: Double,
        val coverage: Double,
        val captureTimeMs: Long
    )

    @Volatile private var catalogue: List<Reference> = emptyList()
    @Volatile private var bandIndexes: Array<Map<Int, List<Reference>>> = Array(4) { emptyMap() }

    fun replaceCatalogue(entries: Collection<Reference>) {
        val clean = entries.filter { it.mediaId.isNotBlank() && it.mediaTimeMs >= 0L }.toList()
        val mutable = Array(4) { HashMap<Int, MutableList<Reference>>() }
        for (ref in clean) for (band in 0 until 4) {
            val key = ((ref.hash ushr (band * 16)) and 0xffffL).toInt()
            mutable[band].getOrPut(key) { ArrayList() }.add(ref)
        }
        catalogue = clean
        bandIndexes = Array(4) { band -> mutable[band].mapValues { it.value.toList() } }
    }

    fun catalogueSize(): Int = catalogue.size

    /** Query sampleStart values are relative sample offsets from the beginning of the captured query. */
    fun match(query: List<AudioFingerprintEngine.Fingerprint>, sampleRate: Int = 16_000): Match? {
        if (catalogue.isEmpty() || query.isEmpty() || sampleRate <= 0) return null
        val indexes = bandIndexes
        data class Vote(
            val mediaId: String,
            val offsetMs: Long,
            val queryMs: Long,
            val mediaMs: Long,
            val similarity: Double
        )
        val votes = ArrayList<Vote>()
        val masks = bandProbeMasks
        for (q in query) {
            val candidates = HashSet<Reference>()
            for (band in 0 until 4) {
                val key = ((q.hash ushr (band * 16)) and 0xffffL).toInt()
                val index = indexes[band]
                for (mask in masks) index[key xor mask]?.let { candidates.addAll(it) }
            }
            if (candidates.isEmpty()) continue
            val queryMs = q.sampleStart * 1000L / sampleRate
            // Keep several near-equal candidates. A 64-bit spectral hash is not unique,
            // so selecting the first HashSet match can randomly lock onto the wrong scene.
            val nearMatches = candidates.mapNotNull { reference ->
                val distance = java.lang.Long.bitCount(q.hash xor reference.hash)
                if (distance <= maxHammingDistance) reference to distance else null
            }.sortedBy { it.second }.take(16)
            for ((selected, distance) in nearMatches) {
                votes += Vote(
                    selected.mediaId, selected.mediaTimeMs - queryMs,
                    queryMs, selected.mediaTimeMs, 1.0 - distance / 64.0
                )
            }
        }
        if (votes.isEmpty()) return null
        val grouped = votes.groupBy { it.mediaId }
        var winner: Match? = null
        for ((mediaId, mediaVotes) in grouped) {
            val sorted = mediaVotes.sortedBy { it.offsetMs }
            for (seed in sorted) {
                val cluster = sorted.filter { abs(it.offsetMs - seed.offsetMs) <= offsetToleranceMs }
                // Collapse hash collisions per query frame, preferring the candidate closest
                // to this offset hypothesis instead of an arbitrary HashSet iteration order.
                val temporal = cluster.groupBy { it.queryMs }.values.map { sameFrame ->
                    sameFrame.minWith(
                        compareBy<Vote> { abs(it.offsetMs - seed.offsetMs) }
                            .thenByDescending { it.similarity }
                    )
                }.sortedBy { it.queryMs }
                if (temporal.size < minimumHits) continue
                // A valid match must form a forward-moving sequence on the reference timeline.
                // Independent spectral lookalikes at unrelated media positions are not enough.
                var coherentTransitions = 0
                var testedTransitions = 0
                for (i in 1 until temporal.size) {
                    val dx = temporal[i].queryMs - temporal[i - 1].queryMs
                    val dy = temporal[i].mediaMs - temporal[i - 1].mediaMs
                    if (dx <= 0L) continue
                    testedTransitions++
                    val rate = dy.toDouble() / dx.toDouble()
                    if (dy > 0L && rate in 0.70..1.30) coherentTransitions++
                }
                val temporalCoherence = if (testedTransitions == 0) 0.0
                    else coherentTransitions.toDouble() / testedTransitions
                if (temporal.size < minimumHits || temporalCoherence < 0.65) continue

                val offset = temporal.map { it.offsetMs }.sorted().let { it[it.size / 2] }
                val meanSimilarity = temporal.map { it.similarity }.average()
                val confidence = (temporal.size.toDouble() / query.size.coerceAtLeast(1) *
                    meanSimilarity * temporalCoherence).coerceIn(0.0, 1.0)
                val coverage = (temporal.size.toDouble() / query.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
                val candidate = Match(mediaId, offset, temporal.size, confidence, coverage,
                    temporal.maxOf { it.queryMs })
                if (winner == null || candidate.hits > winner!!.hits ||
                    (candidate.hits == winner!!.hits && candidate.confidence > winner!!.confidence)) winner = candidate
            }
        }
        return winner
    }

    companion object {
        // Four 16-bit bands: with a 64-bit Hamming threshold of 8, at least one band differs by <=2 bits.
        private val bandProbeMasks: IntArray = buildList {
            add(0)
            for (i in 0 until 16) add(1 shl i)
            for (i in 0 until 16) for (j in i + 1 until 16) add((1 shl i) or (1 shl j))
        }.toIntArray()
    }

}
