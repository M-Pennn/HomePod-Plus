package com.airbridge.app.airplay

import org.junit.Assert.*
import org.junit.Test

class Tlv8Test {
    @Test
    fun encodeChunksLongValue() {
        val v = ByteArray(300) { it.toByte() }
        val encoded = Tlv8.encode(listOf(Tlv8.SALT to v))
        val decoded = Tlv8.decode(encoded)
        assertArrayEquals(v, decoded[Tlv8.SALT])
        assertEquals(304, encoded.size)
    }

    @Test
    fun decodeMergesChunks() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        val encoded = ByteArray(2 + a.size + 2 + b.size)
        encoded[0] = Tlv8.PUBLIC_KEY.toByte()
        encoded[1] = a.size.toByte()
        System.arraycopy(a, 0, encoded, 2, a.size)
        encoded[2 + a.size] = Tlv8.PUBLIC_KEY.toByte()
        encoded[3 + a.size] = b.size.toByte()
        System.arraycopy(b, 0, encoded, 4 + a.size, b.size)
        val decoded = Tlv8.decode(encoded)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded[Tlv8.PUBLIC_KEY])
    }
}