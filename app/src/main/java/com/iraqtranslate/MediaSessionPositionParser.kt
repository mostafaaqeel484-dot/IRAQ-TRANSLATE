package com.iraqtranslate

/** Parses Android dumpsys media_session PlaybackState snapshots; position values are milliseconds. */
object MediaSessionPositionParser {
    data class Position(val packageName: String, val state: Int, val positionMs: Long, val speed: Double)

    fun parse(dump: String, nowElapsedMs: Long): Position? {
        val blocks = dump.split(Regex("""(?m)(?=^\s*Session #)"""))
            .filter { it.contains("state=PlaybackState") }.ifEmpty { listOf(dump) }
        val packageRegex = Regex("""(?m)^\s*package=([^\s]+)""")
        val stateRegex = Regex(
            """state=PlaybackState\s*\{\s*state=(\d+),\s*position=(-?\d+),.*?speed=([-+0-9.eE]+)(?:,.*?updated=(\d+))?""",
            RegexOption.DOT_MATCHES_ALL
        )
        val candidates = ArrayList<Position>()
        for (block in blocks) {
            val match = stateRegex.find(block) ?: continue
            val state = match.groupValues[1].toIntOrNull() ?: continue
            val rawPosition = match.groupValues[2].toLongOrNull() ?: continue
            val speed = match.groupValues[3].toDoubleOrNull() ?: continue
            val updatedAt = match.groupValues[4].toLongOrNull() ?: 0L
            if (rawPosition < 0L || !speed.isFinite() || speed !in 0.0..4.0) continue
            if (state !in setOf(2, 3, 6)) continue // paused, playing, buffering
            val effectiveSpeed = if (state == 2 || state == 6) 0.0 else speed
            val position = if (state == 3 && updatedAt > 0L)
                rawPosition + ((nowElapsedMs - updatedAt).coerceAtLeast(0L) * effectiveSpeed).toLong()
            else rawPosition
            val packageName = packageRegex.find(block)?.groupValues?.get(1) ?: "unknown"
            candidates += Position(packageName, state, position, effectiveSpeed)
        }
        return candidates.firstOrNull { it.state == 3 } ?: candidates.firstOrNull()
    }
}
