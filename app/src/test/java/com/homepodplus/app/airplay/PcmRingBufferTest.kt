package com.homepodplus.app.airplay

import org.junit.Assert.*
import org.junit.Test

class PcmRingBufferTest {
    @Test
    fun readAfterWrite() {
        val buf = PcmRingBuffer(4)
        val input = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        buf.write(input)
        val out = ShortArray(8)
        val n = buf.read(out, 4)
        assertEquals(4, n)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8), out)
        assertEquals(0, buf.read(ShortArray(8), 4))
    }

    @Test
    fun overwritesOldestWhenFull() {
        val buf = PcmRingBuffer(2)
        buf.write(shortArrayOf(1, 2, 3, 4))
        buf.write(shortArrayOf(5, 6, 7, 8))
        val out = ShortArray(4)
        assertEquals(2, buf.read(out, 2))
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), out)
    }

    @Test
    fun keepsNewestWhenInputExceedsCapacity() {
        val buf = PcmRingBuffer(2)
        buf.write(shortArrayOf(1, 2, 3, 4, 5, 6))
        val out = ShortArray(4)
        assertEquals(2, buf.read(out, 2))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), out)
    }
}