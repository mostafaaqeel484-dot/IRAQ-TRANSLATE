package com.iraqtranslate

import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Process
import java.nio.ByteBuffer

/**
 * Launched by app_process through the authenticated ADB shell connection.
 * Audio source 1997 is ECHO_REFERENCE, not MIC. It requires the firmware to
 * authorize CAPTURE_AUDIO_OUTPUT for the shell UID. PCM32 stereo is streamed
 * to stdout with a small protocol header; no file is written.
 */
object ShellAudioHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        var record: AudioRecord? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val sampleRate = 48000
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val pcm32 = 3 // AudioFormat.ENCODING_PCM_32BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, pcm32)
            check(minBuffer > 0) { "ECHO_REFERENCE PCM32 stereo is unsupported: $minBuffer" }
            record = AudioRecord.Builder()
                .setAudioSource(1997) // MediaRecorder.AudioSource.ECHO_REFERENCE
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(pcm32)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(384000, minBuffer))
                .build()
            check(record.state == AudioRecord.STATE_INITIALIZED) { "ECHO_REFERENCE AudioRecord initialization failed" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "ECHO_REFERENCE did not enter recording state"
            }
            val output = System.out
            output.write("IRQAUD01".toByteArray(Charsets.US_ASCII))
            output.flush()
            val buffer = ByteBuffer.allocateDirect(384000)
            val transfer = ByteArray(buffer.capacity())
            while (true) {
                buffer.clear()
                val count = record.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (count < 0) error("ECHO_REFERENCE read failed: $count")
                if (count > 0) {
                    buffer.position(0)
                    buffer.get(transfer, 0, count)
                    output.write(transfer, 0, count)
                    output.flush()
                }
            }
        } catch (t: Throwable) {
            System.err.println("IRAQ_TRANSLATE_ECHO_REFERENCE_ERROR: ${t.message}")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }
}
