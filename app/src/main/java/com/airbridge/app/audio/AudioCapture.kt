package com.airbridge.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection

/**
 * Captures any app's audio via MediaProjection + AudioPlaybackCapture
 * (Android 10+, no root required).
 */
class AudioCapture(
    private val projection: MediaProjection,
    private val onPcm: (ShortArray, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false
    private var sampleRate = 44100

    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        for (rate in intArrayOf(44100, 48000)) {
            try {
                val min = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (min <= 0) continue
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val rec = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(min * 4, 8192))
                    .build()
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    rec.release()
                    continue
                }
                record = rec
                sampleRate = rate
                running = true
                thread = Thread({ loop(rec) }, "audio-capture").also { it.start() }
                return true
            } catch (t: Throwable) {
                onError("音频捕获初始化失败 ($rate Hz): ${t.message}")
            }
        }
        return false
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(2048)
        while (running) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (e: Exception) {
                break
            }
            if (n > 0) {
                onPcm(buf.copyOf(n), sampleRate)
            }
        }
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        try {
            projection.stop()
        } catch (_: Exception) {
        }
    }
}
