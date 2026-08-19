package com.airbridge.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AlacCodecTest {

    private fun assertRoundTrip(samples: ShortArray, offset: Int = 0, frames: Int = AlacCodec.FRAME_SIZE) {
        val result = AlacCodec.encode(samples, offset, frames)
        val decoded = AlacCodec.decode(result.data)
        assertEquals(frames * 2, decoded.size)
        for (i in 0 until frames * 2) {
            assertEquals("sample $i", samples[offset + i].toInt(), decoded[i])
        }
    }

    @Test
    fun sineWaveRoundTripAndCompresses() {
        val frames = AlacCodec.FRAME_SIZE
        val samples = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0) * 12000.0).toInt().toShort()
            val v2 = (Math.sin(2.0 * Math.PI * 220.0 * i / 44100.0) * 9000.0).toInt().toShort()
            samples[i * 2] = v
            samples[i * 2 + 1] = v2
        }
        val result = AlacCodec.encode(samples)
        assertRoundTrip(samples)
        // 纯净正弦应能压缩，且明显小于未压缩帧（1408B 采样 + 头）
        assertFalse("sine should compress", result.verbatim)
        assertTrue("compressed size ${result.data.size} should be < raw", result.data.size < 1408)
    }

    @Test
    fun silenceRoundTripCompressesWell() {
        val samples = ShortArray(AlacCodec.FRAME_SIZE * 2)
        val result = AlacCodec.encode(samples)
        assertRoundTrip(samples)
        assertFalse(result.verbatim)
        assertTrue("silence should compress very small", result.data.size < 64)
    }

    @Test
    fun whiteNoiseRoundTrip() {
        val rnd = Random(42)
        val samples = ShortArray(AlacCodec.FRAME_SIZE * 2) { rnd.nextInt(-32768, 32768).toShort() }
        assertRoundTrip(samples)
        // 白噪声压缩不划算时应回退 verbatim，但往返必须一致
        val result = AlacCodec.encode(samples)
        assertTrue(result.data.size in 1400..1420)
    }

    @Test
    fun offsetEncodingRoundTrips() {
        val rnd = Random(7)
        val samples = ShortArray(1024) { rnd.nextInt(-20000, 20000).toShort() }
        assertRoundTrip(samples, offset = 100, frames = AlacCodec.FRAME_SIZE)
    }

    @Test
    fun musicLikeSignalRoundTrips() {
        val frames = AlacCodec.FRAME_SIZE
        val samples = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / 44100.0
            val l = Math.sin(2.0 * Math.PI * 330.0 * t) * 10000.0 +
                Math.sin(2.0 * Math.PI * 660.0 * t) * 3000.0 +
                Math.sin(2.0 * Math.PI * 990.0 * t) * 1500.0
            val r = Math.sin(2.0 * Math.PI * 220.0 * t) * 11000.0 +
                Math.sin(2.0 * Math.PI * 550.0 * t) * 2500.0
            samples[i * 2] = l.toInt().toShort()
            samples[i * 2 + 1] = r.toInt().toShort()
        }
        val result = AlacCodec.encode(samples)
        assertRoundTrip(samples)
        assertFalse(result.verbatim)
        assertTrue(result.data.size < 1408)
    }

}
