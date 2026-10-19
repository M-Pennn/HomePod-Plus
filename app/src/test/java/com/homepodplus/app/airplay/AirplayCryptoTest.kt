package com.homepodplus.app.airplay

import org.junit.Assert.*
import org.junit.Test

class AirplayCryptoTest {
    @Test
    fun chachaRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = AirplayCrypto.counterNonce8(7)
        val pt = "hello airplay".toByteArray(Charsets.US_ASCII)
        val ct = AirplayCrypto.chacha20Poly1305Encrypt(key, nonce, pt)
        val back = AirplayCrypto.chacha20Poly1305Decrypt(key, nonce, ct)
        assertArrayEquals(pt, back!!)
    }

    @Test
    fun hkdfProducesExpectedLength() {
        val ikm = ByteArray(32) { it.toByte() }
        val out = AirplayCrypto.hkdfSha512("Control-Salt", "Control-Write-Encryption-Key", ikm, 32)
        assertEquals(32, out.size)
    }
}