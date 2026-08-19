package com.airbridge.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock

/**
 * 静音 AudioTrack 主时钟（P1）。
 *
 * Android 音频硬件时钟与标称 44.1kHz 之间存在 ppm 级漂移；发送线程若一直按
 * 标称节拍发包，长时间播放会让接收端缓冲缓慢溢出或欠载，表现为爆音/卡顿。
 * 这里用一条只写静音的 AudioTrack 占用播放时钟，周期读取 getTimestamp()，
 * 实测出硬件速率，供发送线程把节拍锁定到真实硬件时钟。
 */
@SuppressLint("MissingPermission")
class AudioMasterClock(
    private val sampleRate: Int = 44100,
    private val onLog: (String) -> Unit = {}
) {

    private var track: AudioTrack? = null
    private var writerThread: Thread? = null
    private var pollerThread: Thread? = null

    @Volatile
    private var running = false

    // 最近一次有效的 (framePosition, nanoTime)
    @Volatile
    private var lastFrame = 0L

    @Volatile
    private var lastNano = 0L

    @Volatile
    private var measuredRate = 0.0

    @Volatile
    private var usable = false

    fun start() {
        if (running) return
        val minBuf = try {
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (t: Throwable) {
            onLog("主时钟不可用: ${t.message}")
            return
        }
        if (minBuf <= 0) {
            onLog("主时钟不可用: getMinBufferSize=$minBuf")
            return
        }
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                .build()
        } catch (t: Throwable) {
            onLog("主时钟创建失败: ${t.message}")
            return
        }
        track = t
        running = true
        writerThread = Thread({ writeLoop(t) }, "audio-clock-write").also { it.start() }
        pollerThread = Thread({ pollLoop(t) }, "audio-clock-poll").also { it.start() }
        try {
            t.play()
        } catch (e: Exception) {
            onLog("主时钟启动失败: ${e.message}")
            stop()
        }
    }

    fun stop() {
        running = false
        try {
            track?.pause()
        } catch (_: Exception) {
        }
        try {
            track?.flush()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        writerThread?.interrupt()
        pollerThread?.interrupt()
        writerThread = null
        pollerThread = null
        usable = false
    }

    val isUsable: Boolean get() = usable

    /** 实测（平滑后）或标称的硬件播放速率。 */
    fun rateHz(): Double {
        val m = measuredRate
        return if (m > 0.0) m else sampleRate.toDouble()
    }

    private fun writeLoop(t: AudioTrack) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val silence = ShortArray(4096)
        while (running) {
            try {
                val written = t.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                if (written <= 0) Thread.sleep(10)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running) onLog("主时钟写入失败: ${e.message}")
                break
            }
        }
    }

    private fun pollLoop(t: AudioTrack) {
        val ts = AudioTimestamp()
        // 用 32 个采样（约 1.6 秒窗口）的斜率测速，再取最近多个窗口的
        // 中位数，抗 AudioTrack 短暂 stall 造成的偏低读数
        val frames = LongArray(32)
        val nanos = LongArray(32)
        val rates = ArrayList<Double>(24)
        var head = 0
        var count = 0
        var stall = 0
        while (running) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                break
            }
            try {
                if (!t.getTimestamp(ts)) {
                    stall++
                    if (stall > 20) usable = false
                    continue
                }
                val f = ts.framePosition
                val n = ts.nanoTime
                if (f <= 0 || n <= 0 || f == lastFrame) {
                    stall++
                    if (stall > 20) usable = false
                    continue
                }
                stall = 0
                lastFrame = f
                lastNano = n
                frames[head] = f
                nanos[head] = n
                head = (head + 1) % frames.size
                if (count < frames.size) count++
                if (count >= 2) {
                    val old = (head + frames.size - count) % frames.size
                    val df = f - frames[old]
                    val dt = n - nanos[old]
                    if (dt > 200_000_000L && df > 0) {
                        val inst = df * 1_000_000_000.0 / dt
                        val lo = sampleRate * 0.985
                        val hi = sampleRate * 1.015
                        if (inst in lo..hi) {
                            rates.add(inst)
                            if (rates.size > 24) rates.removeAt(0)
                            if (rates.size >= 3) {
                                val n = minOf(rates.size, 16)
                                val sorted = rates.takeLast(n).sorted()
                                measuredRate = sorted[sorted.size / 2]
                                usable = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                stall++
                if (stall > 20) usable = false
            }
        }
    }

}
