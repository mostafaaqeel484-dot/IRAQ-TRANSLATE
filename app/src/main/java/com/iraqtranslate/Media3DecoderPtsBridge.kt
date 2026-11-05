package com.iraqtranslate

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import android.media.MediaFormat
import androidx.media3.common.Format

/**
 * Decoder-frame PTS adapter for an IPTV host that owns a Media3 ExoPlayer.
 * Attach this to the actual player before prepare(); do not feed MediaSession
 * position or AudioRecord timestamps into the decoder PTS clock.
 */
@UnstableApi
class Media3DecoderPtsBridge(
    private val player: ExoPlayer,
    private val captureService: CaptureService
) : VideoFrameMetadataListener {
    @Volatile private var attached = false

    fun attach() {
        if (attached) return
        player.addVideoFrameMetadataListener(this)
        attached = true
    }

    fun detach() {
        if (!attached) return
        player.removeVideoFrameMetadataListener(this)
        attached = false
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?
    ) {
        // The callback is emitted by Media3's video renderer for an actual frame.
        captureService.onDecoderVideoFramePts(
            ptsUs = presentationTimeUs,
            playbackSpeed = player.playbackParameters.speed.toDouble(),
            playing = player.isPlaying
        )
    }
}
