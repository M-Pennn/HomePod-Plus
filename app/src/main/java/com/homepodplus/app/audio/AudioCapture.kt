package com.homepodplus.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.os.SystemClock

/**
 * Captures any app's audio via MediaProjection + AudioPlaybackCapture
 * (Android 10+, no root required).
 */
@SuppressLint("MissingPermission")
class AudioCapture(
    private val projection: MediaProjection,
    private val onPcm: (ShortArray, Int) -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false
    private var sampleRate = 44100

    // 捕获硬件时钟测速：把 AudioRecord 实测速率归一化到 44.1kHz 域
    @Volatile
    private var measuredRateHz = 0.0
    private val clockFrames = LongArray(32)
    private val clockNanos = LongArray(32)
    private var clockHead = 0
    private var clockCount = 0
    private var lastClockFrame = 0L
    private var lastClockNano = 0L

    /** 捕获时钟锁定后回调（44.1kHz 域速率），由捕获线程调用 */
    @Volatile
    var onRate: ((Double) -> Unit)? = null

    /** 实测捕获速率（44.1kHz 域）；0 表示尚未锁定 */
    fun measuredRateHz(): Double = measuredRateHz

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
                onLog("音频捕获已启动: ${rate}Hz, 最小缓冲=${min}B, 实际缓冲=${maxOf(min * 4, 8192)}B")
                measuredRateHz = 0.0
                clockHead = 0
                clockCount = 0
                lastClockFrame = 0L
                lastClockNano = 0L
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
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(1024)
        var readCount = 0L
        var maxReadNanos = 0L
        var gapCount = 0L
        var lastDiagNanos = SystemClock.elapsedRealtimeNanos()
        while (running) {
            val readStart = SystemClock.elapsedRealtimeNanos()
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (e: Exception) {
                break
            }
            val readEnd = SystemClock.elapsedRealtimeNanos()
            if (n > 0) {
                val took = readEnd - readStart
                readCount++
                if (took > maxReadNanos) maxReadNanos = took
                if (took > 50_000_000L) gapCount++
                updateClock(rec)
                onPcm(buf.copyOf(n), sampleRate)
            } else if (n < 0) {
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    break
                }
            }
            if (readEnd - lastDiagNanos > 30_000_000_000L) {
                lastDiagNanos = readEnd
                val avgMs = if (readCount > 0) (30_000L / readCount) else 0L
                val maxMs = maxReadNanos / 1_000_000L
                onLog("捕获节奏: 平均每包=${avgMs}ms 最大单包等待=${maxMs}ms >50ms次数=$gapCount")
                readCount = 0
                maxReadNanos = 0
                gapCount = 0
            }
        }
    }

    /** 每次读到数据后用 AudioRecord 硬件时间戳测速（±2ms 精度时钟）。 */
    private fun updateClock(rec: AudioRecord) {
        val ts = AudioTimestamp()
        try {
            if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != 0) return
            val f = ts.framePosition
            val n = ts.nanoTime
            if (f <= 0 || n <= 0 || f == lastClockFrame) return
            lastClockFrame = f
            lastClockNano = n
            clockFrames[clockHead] = f
            clockNanos[clockHead] = n
            clockHead = (clockHead + 1) % clockFrames.size
            if (clockCount < clockFrames.size) clockCount++
            if (clockCount < 2) return
            val old = (clockHead + clockFrames.size - clockCount) % clockFrames.size
            val df = f - clockFrames[old]
            val dt = n - clockNanos[old]
            if (dt > 200_000_000L && df > 0) {
                val inst = df * 1_000_000_000.0 / dt
                val lo = sampleRate * 0.97
                val hi = sampleRate * 1.03
                if (inst in lo..hi) {
                    val normalized = inst * 44100.0 / sampleRate
                    val cur = measuredRateHz
                    measuredRateHz = if (cur <= 0.0) normalized else cur * 0.8 + normalized * 0.2
                    onRate?.invoke(measuredRateHz)
                }
            }
        } catch (_: Exception) {
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
