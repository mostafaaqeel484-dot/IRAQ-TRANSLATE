package com.iraqtranslate

/** Pure decision logic: never present an unanchored SRT as synchronized playback. */
object SubtitleSyncPolicy {
    enum class Mode { LIVE_TRANSLATION, WAITING_FOR_SPEECH, SPEECH_ANCHORED, PLAYER_CLOCK }
    fun mode(hasSrt: Boolean, speechAnchored: Boolean, trustedClock: Boolean): Mode = when {
        !hasSrt -> Mode.LIVE_TRANSLATION
        speechAnchored -> Mode.SPEECH_ANCHORED
        trustedClock -> Mode.PLAYER_CLOCK
        else -> Mode.WAITING_FOR_SPEECH
    }
    fun shouldRenderSrt(mode: Mode): Boolean = mode == Mode.SPEECH_ANCHORED || mode == Mode.PLAYER_CLOCK
}
