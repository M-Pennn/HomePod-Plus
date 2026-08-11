package com.airbridge.app.airplay

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * AirPlay 2 / HomeKit crypto primitives.
 * Ported from the clean-room Apache-2.0 reference (airplay2-sender-cpp) and the
 * pyatv / pair_ap wire conventions.
 */
object AirplayCrypto {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // ---- byte helpers ------------------------------------------------------

    fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun fromHex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // ---- hashing / KDF -----------------------------------------------------

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-SHA512 with ASCII salt/info strings (HAP convention). */
    fun hkdfSha512(salt: String, info: String, ikm: ByteArray, length: Int = 32): ByteArray {
        val saltB = salt.toByteArray(Charsets.US_ASCII)
        val infoB = info.toByteArray(Charsets.US_ASCII)
        val prk = hmacSha512(saltB, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var off = 0
        var counter = 1
        while (off < length) {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(prk, "HmacSHA512"))
            mac.update(t)
            mac.update(infoB)
            mac.update(byteArrayOf(counter.toByte()))
            t = mac.doFinal()
            val n = minOf(t.size, length - off)
            System.arraycopy(t, 0, out, off, n)
            off += n
            counter++
        }
        return out
    }

    // ---- ChaCha20-Poly1305 ------------------------------------------------

    /** HAP nonce: 4 zero bytes + 8-byte little-endian counter. */
    fun counterNonce8(counter: Long): ByteArray =
        ByteArray(8) { i -> ((counter shr (8 * i)) and 0xFF).toByte() }

    fun chacha20Poly1305Encrypt(
        key: ByteArray,
        nonce8: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray {
        require(key.size == 32) { "chacha key size" }
        val nonce = ByteArray(12)
        System.arraycopy(nonce8, 0, nonce, 4, minOf(8, nonce8.size))
        return try {
            val c = Cipher.getInstance("ChaCha20-Poly1305")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20-Poly1305"), GCMParameterSpec(128, nonce))
            c.updateAAD(aad)
            c.doFinal(plaintext)
        } catch (e: Exception) {
            val c = Cipher.getInstance("ChaCha20-Poly1305", BouncyCastleProvider.PROVIDER_NAME)
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20-Poly1305"), IvParameterSpec(nonce))
            c.updateAAD(aad)
            c.doFinal(plaintext)
        }
    }

    fun chacha20Poly1305Decrypt(
        key: ByteArray,
        nonce8: ByteArray,
        cipherAndTag: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray? {
        if (key.size != 32 || cipherAndTag.size < 16) return null
        val nonce = ByteArray(12)
        System.arraycopy(nonce8, 0, nonce, 4, minOf(8, nonce8.size))
        return try {
            val c = Cipher.getInstance("ChaCha20-Poly1305")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20-Poly1305"), GCMParameterSpec(128, nonce))
            c.updateAAD(aad)
            c.doFinal(cipherAndTag)
        } catch (e: Exception) {
            try {
                val c = Cipher.getInstance("ChaCha20-Poly1305", BouncyCastleProvider.PROVIDER_NAME)
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20-Poly1305"), IvParameterSpec(nonce))
                c.updateAAD(aad)
                c.doFinal(cipherAndTag)
            } catch (e2: Exception) {
                null
            }
        }
    }

    // ---- X25519 ------------------------------------------------------------

    data class X25519KeyPair(val priv: ByteArray, val pub: ByteArray)

    fun x25519Generate(): X25519KeyPair {
        val priv = ByteArray(32)
        SecureRandom().nextBytes(priv)
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = (priv[31].toInt() and 127).toByte()
        priv[31] = (priv[31].toInt() or 64).toByte()
        val pub = ByteArray(32)
        X25519.scalarMultBase(priv, 0, pub, 0)
        return X25519KeyPair(priv, pub)
    }

    fun x25519SharedSecret(ourPriv: ByteArray, theirPub: ByteArray): ByteArray? {
        if (ourPriv.size != 32 || theirPub.size != 32) return null
        val out = ByteArray(32)
        X25519.scalarMult(ourPriv, 0, theirPub, 0, out, 0)
        var acc = 0
        for (b in out) acc = acc or (b.toInt() and 0xFF)
        if (acc == 0) return null
        return out
    }

    // ---- Ed25519 -----------------------------------------------------------

    fun ed25519PublicFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        val pub = ByteArray(32)
        Ed25519.generatePublicKey(seed, 0, pub, 0)
        return pub
    }

    fun ed25519Sign(seed: ByteArray, msg: ByteArray): ByteArray {
        // BC's rfc8032 API only exposes random private-key generation; a
        // deterministic seed-based path is needed for HAP PIN pairing
        // (Apple TV). HomePod transient pairing does not use Ed25519.
        throw UnsupportedOperationException("Ed25519 sign: PIN 配对路径暂未启用")
    }

    fun ed25519Verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        throw UnsupportedOperationException("Ed25519 verify: PIN 配对路径暂未启用")
    }

    // ---- SRP-6a 3072 / SHA-512 (HomeKit client) ----------------------------

    private val SRP_N = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183" +
            "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A" +
            "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7A" +
            "BF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D" +
            "87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E20" +
            "8E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
        16
    )
    private val SRP_G = BigInteger.valueOf(5)
    private const val SRP_N_BYTES = 384

    private fun nat(x: BigInteger): ByteArray {
        var b = x.toByteArray()
        var i = 0
        while (i < b.size - 1 && b[i] == 0.toByte()) i++
        if (i > 0) b = b.copyOfRange(i, b.size)
        return b
    }

    private fun padTo(x: BigInteger, len: Int): ByteArray {
        val n = nat(x)
        require(n.size <= len)
        val out = ByteArray(len)
        System.arraycopy(n, 0, out, len - n.size, n.size)
        return out
    }

    private fun hashTwoPadded(a: BigInteger, b: BigInteger): ByteArray {
        return sha512(concat(padTo(a, SRP_N_BYTES), padTo(b, SRP_N_BYTES)))
    }

    class SrpClient {
        private val random = SecureRandom()
        private var password = ""
        private var a: BigInteger? = null
        private var A: BigInteger? = null
        private var B: BigInteger? = null
        private var K: ByteArray? = null
        private var M1: ByteArray? = null
        private var processed = false

        fun start(password: String) {
            this.password = password
            val aBytes = ByteArray(32)
            random.nextBytes(aBytes)
            a = BigInteger(1, aBytes)
            A = SRP_G.modPow(a!!, SRP_N)
        }

        fun publicA(): ByteArray = nat(A!!)

        fun process(salt: ByteArray, serverB: ByteArray): Boolean {
            B = BigInteger(1, serverB)
            if (B!!.mod(SRP_N) == BigInteger.ZERO) return false

            val k = BigInteger(1, hashTwoPadded(SRP_N, SRP_G))
            val u = BigInteger(1, hashTwoPadded(A!!, B!!))

            val inner = "Pair-Setup:$password".toByteArray(Charsets.US_ASCII)
            val x = BigInteger(1, sha512(concat(salt, sha512(inner))))

            val gx = SRP_G.modPow(x, SRP_N)
            val base = B!!.subtract(k.multiply(gx)).mod(SRP_N)
            val exp = a!!.add(u.multiply(x))
            val S = base.modPow(exp, SRP_N)
            K = sha512(nat(S))

            val hN = sha512(nat(SRP_N))
            val hg = sha512(nat(SRP_G))
            val hXor = ByteArray(64) { i -> (hN[i].toInt() xor hg[i].toInt()).toByte() }
            val hI = sha512("Pair-Setup".toByteArray(Charsets.US_ASCII))
            M1 = sha512(concat(hXor, hI, salt, nat(A!!), nat(B!!), K!!))

            processed = true
            return true
        }

        fun proofM1(): ByteArray = M1!!
        fun sessionKey(): ByteArray = K!!

        fun verifyServerProof(serverM2: ByteArray): Boolean {
            if (!processed) return false
            val m2 = sha512(concat(nat(A!!), M1!!, K!!))
            if (m2.size != serverM2.size) return false
            var diff = 0
            for (i in m2.indices) diff = diff or (m2[i].toInt() xor serverM2[i].toInt())
            return diff == 0
        }
    }
}

// ---- HomeKit TLV8 ----------------------------------------------------------

object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val PERMISSIONS = 0x0B
    const val NAME = 0x11
    const val FLAGS = 0x13

    fun encode(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((tag, value) in items) {
            var pos = 0
            do {
                val chunk = minOf(255, value.size - pos)
                out.write(tag)
                out.write(chunk)
                out.write(value, pos, chunk)
                pos += chunk
            } while (pos < value.size)
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): LinkedHashMap<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var i = 0
        while (i + 2 <= data.size) {
            val tag = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            if (i + 2 + len > data.size) break
            val chunk = data.copyOfRange(i + 2, i + 2 + len)
            val prev = out[tag]
            if (prev != null) {
                out[tag] = prev + chunk
            } else {
                out[tag] = chunk
            }
            i += 2 + len
        }
        return out
    }
}

// ---- minimal Apple binary plist (bplist00) ---------------------------------

sealed class PValue {
    object True : PValue()
    object False : PValue()
    data class IntV(val v: Long) : PValue()
    data class StrV(val s: String) : PValue()
    data class DataV(val b: ByteArray) : PValue()
    data class ArrV(val items: List<PValue>) : PValue()
    data class DictV(val pairs: List<Pair<String, PValue>>) : PValue()
}

object Bplist {

    private class Builder {
        val bytes = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = bytes.write(v and 0xFF)
        fun u16(v: Int) {
            u8(v shr 8); u8(v)
        }
        fun u32(v: Long) {
            u8((v shr 24).toInt()); u8((v shr 16).toInt()); u8((v shr 8).toInt()); u8(v.toInt())
        }
        fun u64(v: Long) {
            u32(v ushr 32); u32(v)
        }
        fun raw(b: ByteArray) = bytes.write(b)
        fun toByteArray() = bytes.toByteArray()
    }

    fun encode(root: PValue): ByteArray {
        val objects = ArrayList<ByteArray>()

        fun encodeInt(obj: Builder, value: Long) {
            when {
                value in 0..0xFF -> { obj.u8(0x10); obj.u8(value.toInt()) }
                value in 0..0xFFFF -> { obj.u8(0x11); obj.u16(value.toInt()) }
                value in 0..0xFFFFFFFFL -> { obj.u8(0x12); obj.u32(value) }
                else -> { obj.u8(0x13); obj.u64(value) }
            }
        }

        fun encodeMarkerLen(obj: Builder, marker: Int, len: Int) {
            if (len < 15) {
                obj.u8(marker or len)
            } else {
                obj.u8(marker or 0x0F)
                encodeInt(obj, len.toLong())
            }
        }

        fun appendRef(obj: Builder, ref: Int) = obj.u32(ref.toLong())

        fun add(v: PValue): Int {
            val idx = objects.size
            objects.add(ByteArray(0)) // reserve slot
            val obj = Builder()
            when (v) {
                is PValue.True -> obj.u8(0x09)
                is PValue.False -> obj.u8(0x08)
                is PValue.IntV -> encodeInt(obj, v.v)
                is PValue.StrV -> {
                    val b = v.s.toByteArray(Charsets.US_ASCII)
                    encodeMarkerLen(obj, 0x50, b.size)
                    obj.raw(b)
                }
                is PValue.DataV -> {
                    encodeMarkerLen(obj, 0x40, v.b.size)
                    obj.raw(v.b)
                }
                is PValue.ArrV -> {
                    val refs = v.items.map { add(it) }
                    encodeMarkerLen(obj, 0xA0, refs.size)
                    refs.forEach { appendRef(obj, it) }
                }
                is PValue.DictV -> {
                    val krefs = ArrayList<Int>()
                    val vrefs = ArrayList<Int>()
                    for ((k, value) in v.pairs) {
                        krefs.add(add(PValue.StrV(k)))
                        vrefs.add(add(value))
                    }
                    encodeMarkerLen(obj, 0xD0, v.pairs.size)
                    krefs.forEach { appendRef(obj, it) }
                    vrefs.forEach { appendRef(obj, it) }
                }
            }
            objects[idx] = obj.toByteArray()
            return idx
        }

        add(root)

        val out = Builder()
        out.raw("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = ArrayList<Long>()
        for (o in objects) {
            offsets.add(out.bytes.size().toLong())
            out.raw(o)
        }
        val offsetTableStart = out.bytes.size()
        for (off in offsets) out.u32(off)
        val trailer = Builder()
        trailer.u8(0); trailer.u8(0); trailer.u8(0); trailer.u8(0)
        trailer.u8(0); trailer.u8(0); trailer.u8(4); trailer.u8(4)
        trailer.u64(objects.size.toLong())
        trailer.u64(0) // top object
        trailer.u64(offsetTableStart.toLong())
        out.raw(trailer.toByteArray())
        return out.toByteArray()
    }

    fun decode(data: ByteArray): PValue? {
        if (data.size < 8 + 32) return null
        if (!data.copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))) return null
        val tr = data.size - 32
        fun readBE(at: Long, n: Int): Long {
            var v = 0L
            for (i in 0 until n) {
                val p = at + i
                if (p >= data.size) return -1
                v = (v shl 8) or (data[p.toInt()].toLong() and 0xFF)
            }
            return v
        }

        val offsetSize = data[tr + 6].toInt() and 0xFF
        val refSize = data[tr + 7].toInt() and 0xFF
        val numObjects = readBE(tr + 8L, 8)
        val offsetTableStart = readBE(tr + 24L, 8)
        if (offsetSize !in intArrayOf(1, 2, 4, 8) || refSize !in intArrayOf(1, 2, 4, 8)) return null
        if (offsetTableStart < 0 || offsetTableStart >= data.size) return null
        if (numObjects < 0 || numObjects > data.size) return null
        val offsets = ArrayList<Long>(numObjects.toInt())
        for (i in 0 until numObjects) {
            val o = readBE(offsetTableStart + i * offsetSize, offsetSize)
            if (o < 0 || o >= data.size) return null
            offsets.add(o)
        }

        fun objectAt(ref: Long, depth: Int): PValue? {
            if (depth > 32 || ref >= numObjects) return null
            var pos = offsets[ref.toInt()]
            if (pos >= data.size) return null
            val marker = data[pos.toInt()].toInt() and 0xFF
            pos++
            val hi = marker and 0xF0
            val lo = marker and 0x0F

            fun readCount(): Long {
                if (lo != 0x0F) return lo.toLong()
                if (pos >= data.size) return -1
                val im = data[pos.toInt()].toInt() and 0xFF
                pos++
                val n = 1 shl (im and 0x0F)
                if (n !in intArrayOf(1, 2, 4, 8)) return -1
                val c = readBE(pos, n)
                pos += n
                return c
            }

            return when (hi) {
                0x00 -> when (marker) {
                    0x09 -> PValue.True
                    else -> PValue.False
                }
                0x10 -> {
                    val n = 1 shl lo
                    val v = readBE(pos, n)
                    pos += n
                    PValue.IntV(v)
                }
                0x20 -> {
                    val n = 1 shl lo
                    val bits = readBE(pos, n)
                    pos += n
                    if (n == 8) PValue.IntV(bits) else PValue.IntV(bits)
                }
                0x40 -> {
                    val cnt = readCount()
                    if (cnt < 0 || cnt > data.size || pos + cnt > data.size) null
                    else PValue.DataV(data.copyOfRange(pos.toInt(), (pos + cnt).toInt()))
                }
                0x50 -> {
                    val cnt = readCount()
                    if (cnt < 0 || cnt > data.size || pos + cnt > data.size) null
                    else PValue.StrV(String(data, pos.toInt(), cnt.toInt(), Charsets.US_ASCII))
                }
                0x60 -> {
                    val cnt = readCount()
                    if (cnt < 0 || cnt > data.size) null
                    else {
                        val sb = StringBuilder()
                        for (i in 0 until cnt) {
                            val at = pos + i * 2
                            if (at + 1 >= data.size) break
                            sb.append((data[(at + 1).toInt()].toInt() and 0xFF).toChar())
                        }
                        PValue.StrV(sb.toString())
                    }
                }
                0xA0 -> {
                    val cnt = readCount()
                    if (cnt < 0 || cnt > numObjects || pos + cnt * refSize > data.size) null
                    else {
                        val items = ArrayList<PValue>()
                        for (i in 0 until cnt) {
                            val r = readBE(pos + i * refSize, refSize)
                            val child = objectAt(r, depth + 1) ?: return null
                            items.add(child)
                        }
                        PValue.ArrV(items)
                    }
                }
                0xD0 -> {
                    val cnt = readCount()
                    if (cnt < 0 || cnt > numObjects || pos + 2 * cnt * refSize > data.size) null
                    else {
                        val pairs = ArrayList<Pair<String, PValue>>()
                        for (i in 0 until cnt) {
                            val kr = readBE(pos + i * refSize, refSize)
                            val vr = readBE(pos + (cnt + i) * refSize, refSize)
                            val k = objectAt(kr, depth + 1) ?: return null
                            val v = objectAt(vr, depth + 1) ?: return null
                            pairs.add(((k as? PValue.StrV)?.s ?: "") to v)
                        }
                        PValue.DictV(pairs)
                    }
                }
                else -> null
            }
        }

        return objectAt(0, 0)
    }
}
