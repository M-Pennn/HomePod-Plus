package com.airbridge.app.airplay

/**
 * 压缩 ALAC（Apple Lossless）编码器，用于 AirPlay 2 实时音频流。
 *
 * 位流布局与 ffmpeg libavcodec/alacenc.c（LGPL-2.1+）一致，本文件是 Kotlin 移植：
 * - Levinson-Durbin 自适应 LPC（4~6 阶，精度 9，与 ffmpeg alacenc 默认一致）；
 * - 立体声去相关 4 模式自动选择；
 * - Rice 熵编码 + 零游程；
 * - 压缩帧不划算时自动回退 verbatim（未压缩）帧；
 * - 内置最小解码器做逐包自校验，失败自动回退，保证发出的帧总能被标准 ALAC 解码器解出。
 *
 * Rice 参数与 AirPlay fmtp "352 0 16 40 10 14 ..." 一致（pb=40 mb=10 kb=14）。
 *
 * 许可提示：本文件源自 LGPL-2.1+ 代码的位流规范移植；若项目最终以不兼容 LGPL 的
 * 许可证发布，需将本文件替换为独立实现。
 */
object AlacCodec {

    const val FRAME_SIZE = 352
    const val CHANNELS = 2
    const val SAMPLE_SIZE = 16

    // 标准 AirPlay ALAC 参数
    private const val RICE_HISTORY_MULT = 40
    private const val RICE_INITIAL_HISTORY = 10
    private const val RICE_LIMIT = 14
    private const val RICE_MODIFIER = 4

    // ffmpeg alacenc 的 LPC 分析参数
    private const val MIN_LPC_ORDER = 4
    private const val MAX_LPC_ORDER = 6
    private const val LPC_PRECISION = 9
    private const val LPC_MIN_SHIFT = 0
    private const val LPC_MAX_SHIFT = 9

    // 解码侧 bps = sample_size + channels - 1
    private const val BPS = SAMPLE_SIZE + CHANNELS - 1

    class Result(val data: ByteArray, val verbatim: Boolean)

    /** 编码一帧交错立体声 16bit PCM，并做逐包解码自校验。 */
    fun encode(samples: ShortArray, offset: Int = 0, frames: Int = FRAME_SIZE): Result {
        val compressed = encodeCompressed(samples, offset, frames)
        val verbatim = encodeVerbatim(samples, offset, frames)
        val useVerbatim = compressed.size >= verbatim.size || !verify(samples, offset, frames, compressed)
        return if (useVerbatim) Result(verbatim, true) else Result(compressed, false)
    }

    private fun verify(samples: ShortArray, offset: Int, frames: Int, frame: ByteArray): Boolean {
        return try {
            val dec = decode(frame)
            if (dec.size != frames * 2) return false
            for (i in 0 until frames * 2) {
                if (dec[i] != samples[offset + i].toInt()) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // 编码

    private fun encodeCompressed(samples: ShortArray, offset: Int, frames: Int): ByteArray {
        val left = IntArray(frames)
        val right = IntArray(frames)
        for (i in 0 until frames) {
            left[i] = samples[offset + i * 2].toInt()
            right[i] = samples[offset + i * 2 + 1].toInt()
        }
        val (shift, weight) = decorrelate(left, right, frames)

        val bw = BitWriter()
        writeHeader(bw, verbatim = false, frames)
        bw.put(shift, 8)
        bw.put(weight, 8)
        val lpc = arrayOfNulls<LpcResult>(2)
        for (ch in 0 until 2) {
            lpc[ch] = calcPredictorParams(if (ch == 0) left else right, frames)
            bw.put(0, 4) // prediction_type = 0
            bw.put(lpc[ch]!!.quant, 4)
            bw.put(RICE_MODIFIER, 3)
            bw.put(lpc[ch]!!.order, 5)
            for (j in 0 until lpc[ch]!!.order) bw.putSigned(lpc[ch]!!.coefs[j], 16)
        }
        for (ch in 0 until 2) {
            val p = lpc[ch]!!
            val residual = linearPredictor(if (ch == 0) left else right, frames, p.order, p.quant, p.coefs)
            entropyCoder(bw, residual, frames)
        }
        bw.put(7, 3)
        return bw.toByteArray()
    }

    fun encodeVerbatim(samples: ShortArray, offset: Int = 0, frames: Int = FRAME_SIZE): ByteArray {
        val bw = BitWriter()
        writeHeader(bw, verbatim = true, frames)
        for (i in 0 until frames) {
            bw.putSigned(samples[offset + i * 2].toInt(), SAMPLE_SIZE)
            bw.putSigned(samples[offset + i * 2 + 1].toInt(), SAMPLE_SIZE)
        }
        bw.put(7, 3)
        return bw.toByteArray()
    }

    private fun writeHeader(bw: BitWriter, verbatim: Boolean, frames: Int) {
        bw.put(1, 3) // CPE（立体声元素）
        bw.put(0, 4) // instance
        bw.put(0, 12)
        bw.put(1, 1) // hasSize：帧长写入帧头（352 < 4096）
        bw.put(0, 2) // extraBits >> 3
        bw.put(if (verbatim) 1 else 0, 1)
        bw.put(frames, 32)
    }

    /** 立体声去相关：返回 (interlacing_shift, interlacing_leftweight)。 */
    private fun decorrelate(left: IntArray, right: IntArray, n: Int): Pair<Int, Int> {
        val sum = LongArray(4)
        for (i in 2 until n) {
            val lt = left[i] - 2 * left[i - 1] + left[i - 2]
            val rt = right[i] - 2 * right[i - 1] + right[i - 2]
            sum[2] += Math.abs((lt + rt) shr 1).toLong()
            sum[3] += Math.abs((lt - rt).toLong())
            sum[0] += Math.abs(lt.toLong())
            sum[1] += Math.abs(rt.toLong())
        }
        val score = longArrayOf(sum[0] + sum[1], sum[0] + sum[3], sum[1] + sum[3], sum[2] + sum[3])
        var best = 0
        for (i in 1 until 4) if (score[i] < score[best]) best = i
        return when (best) {
            0 -> 0 to 0
            1 -> {
                for (i in 0 until n) right[i] = left[i] - right[i]
                0 to 1
            }
            2 -> {
                for (i in 0 until n) {
                    val t = right[i]
                    right[i] = left[i] - right[i]
                    left[i] = t + (right[i] shr 31)
                }
                31 to 1
            }
            else -> {
                for (i in 0 until n) {
                    val t = left[i]
                    left[i] = (t + right[i]) shr 1
                    right[i] = t - right[i]
                }
                1 to 1
            }
        }
    }

    private class LpcResult(val order: Int, val quant: Int, val coefs: IntArray)

    /** Levinson-Durbin LPC 分析（Welch 窗 + 自相关 + 反射系数定阶 + 量化），与 ffmpeg alacenc 一致。 */
    private fun calcPredictorParams(samples: IntArray, frames: Int): LpcResult {
        val windowed = DoubleArray(frames)
        lpcApplyWelchWindow(samples, frames, windowed)
        val autoc = DoubleArray(MAX_LPC_ORDER + 1)
        lpcComputeAutocorr(windowed, frames, MAX_LPC_ORDER, autoc)
        val lpc = Array(MAX_LPC_ORDER) { DoubleArray(MAX_LPC_ORDER) }
        computeLpcCoefs(autoc, MAX_LPC_ORDER, lpc)

        // ORDER_METHOD_EST：从高到低找反射系数 > 0.10 的阶
        var optOrder = MIN_LPC_ORDER
        for (i in MAX_LPC_ORDER - 1 downTo MIN_LPC_ORDER - 1) {
            if (Math.abs(lpc[i][i]) > 0.10) {
                optOrder = i + 1
                break
            }
        }

        val coefs = IntArray(optOrder)
        val shift = IntArray(optOrder)
        quantizeLpcCoefs(
            lpc[optOrder - 1], optOrder, LPC_PRECISION, coefs, shift,
            LPC_MIN_SHIFT, LPC_MAX_SHIFT, 1
        )
        // 解码端不接受 lpc_quant == 0；强制至少 1（编码/解码使用同一值，仍然有效）
        var quant = shift[optOrder - 1]
        if (quant < 1) quant = 1
        return LpcResult(optOrder, quant, coefs)
    }

    private fun lpcApplyWelchWindow(data: IntArray, len: Int, wData: DoubleArray) {
        if (len == 1) {
            wData[0] = 0.0
            return
        }
        val n2 = len shr 1
        val c = 2.0 / (len - 1.0)
        if (len and 1 == 1) {
            for (i in 0 until n2) {
                var w = c - i - 1.0
                w = 1.0 - w * w
                wData[i] = data[i] * w
                wData[len - 1 - i] = data[len - 1 - i] * w
            }
            wData[n2] = 0.0
            return
        }
        for (i in 0 until n2) {
            var w = c - n2 + i
            w = 1.0 - w * w
            wData[n2 - 1 - i] = data[n2 - 1 - i] * w
            wData[n2 + i] = data[n2 + i] * w
        }
    }

    private fun lpcComputeAutocorr(data: DoubleArray, len: Int, lag: Int, autoc: DoubleArray) {
        var j = 0
        while (j < lag) {
            var sum0 = 1.0
            var sum1 = 1.0
            for (i in j until len) {
                sum0 += data[i] * data[i - j]
                val prev = i - j - 1
                sum1 += data[i] * (if (prev >= 0) data[prev] else 0.0)
            }
            autoc[j] = sum0
            autoc[j + 1] = sum1
            j += 2
        }
        if (j == lag) {
            var sum = 1.0
            for (i in j - 1 until len) {
                val prev = i - j
                sum += data[i] * (if (prev >= 0) data[prev] else 0.0)
            }
            autoc[j] = sum
        }
    }

    /** Levinson-Durbin 递归：lpc 的第 i 行保存 i+1 阶系数，行末为反射系数。 */
    private fun computeLpcCoefs(autoc: DoubleArray, maxOrder: Int, lpc: Array<DoubleArray>) {
        var err = autoc[0]
        for (i in 0 until maxOrder) {
            var r = -autoc[i]
            for (j in 0 until i) r -= lpc[i - 1][j] * autoc[i - j - 1]
            if (err != 0.0) r /= err
            err *= 1.0 - r * r
            lpc[i][i] = r
            for (j in 0 until (i + 1) / 2) {
                val f = lpc[i - 1][j]
                val b = lpc[i - 1][i - 1 - j]
                lpc[i][j] = f + r * b
                lpc[i][i - 1 - j] = b + r * f
            }
        }
    }

    private fun quantizeLpcCoefs(
        lpcIn: DoubleArray,
        order: Int,
        precision: Int,
        lpcOut: IntArray,
        shiftOut: IntArray,
        minShift: Int,
        maxShift: Int,
        zeroShift: Int
    ) {
        val qmax = (1 shl (precision - 1)) - 1
        var cmax = 0.0
        for (i in 0 until order) cmax = maxOf(cmax, Math.abs(lpcIn[i]))
        if (cmax * (1 shl maxShift) < 1.0) {
            shiftOut[order - 1] = zeroShift
            for (i in 0 until order) lpcOut[i] = 0
            return
        }
        var sh = maxShift
        while (cmax * (1 shl sh) > qmax && sh > minShift) sh--
        if (sh == 0 && cmax > qmax) {
            val scale = qmax.toDouble() / cmax
            for (i in 0 until order) lpcIn[i] *= scale
        }
        var error = 0.0
        for (i in 0 until order) {
            error -= lpcIn[i] * (1 shl sh)
            lpcOut[i] = Math.round(error).toInt().coerceIn(-qmax, qmax)
            error -= lpcOut[i]
        }
        shiftOut[order - 1] = sh
    }

    /** 自适应线性预测，输出残差；coefs 会在本地自适应（与解码端一致）。 */
    private fun linearPredictor(
        samples: IntArray,
        frames: Int,
        order: Int,
        quant: Int,
        coefs: IntArray
    ): IntArray {
        if (order == 0) return samples.clone()
        val residual = IntArray(frames)
        residual[0] = samples[0]
        for (i in 1..order) residual[i] = signExtend(samples[i] - samples[i - 1], BPS)
        var b = 0
        for (i in order + 1 until frames) {
            var sum = 1 shl (quant - 1)
            for (j in 0 until order) {
                sum += (samples[b + order - j] - samples[b]) * coefs[j]
            }
            sum = sum shr quant
            sum += samples[b]
            var res = signExtend(samples[b + order + 1] - sum, BPS)
            residual[i] = res
            if (res != 0) {
                var index = order - 1
                val neg = res < 0
                while (index >= 0 && (if (neg) res < 0 else res > 0)) {
                    val v = samples[b] - samples[b + order - index]
                    var sign = if (v != 0) (if (v > 0) 1 else -1) else 0
                    if (neg) sign = -sign
                    coefs[index] -= sign
                    val vv = v * sign
                    res -= (vv shr quant) * (order - index)
                    index--
                }
            }
            b++
        }
        return residual
    }

    private fun entropyCoder(bw: BitWriter, residual: IntArray, frames: Int) {
        var history = RICE_INITIAL_HISTORY
        var signModifier = 0
        var i = 0
        while (i < frames) {
            var k = floorLog2((history ushr 9) + 3)
            var x = -2 * residual[i] - 1
            x = x xor (x shr 31)
            i++
            encodeScalar(bw, x - signModifier, k, BPS)

            history += x * RICE_HISTORY_MULT - ((history * RICE_HISTORY_MULT) shr 9)
            signModifier = 0
            if (x > 0xFFFF) history = 0xFFFF

            if (history < 128 && i < frames) {
                var blockSize = 0
                k = 7 - floorLog2(history) + ((history + 16) shr 6)
                while (i < frames && residual[i] == 0) {
                    i++
                    blockSize++
                }
                encodeScalar(bw, blockSize, k, 16)
                signModifier = if (blockSize <= 0xFFFF) 1 else 0
                history = 0
            }
        }
    }

    private fun encodeScalar(bw: BitWriter, xIn: Int, kIn: Int, writeSampleSize: Int) {
        val k = minOf(kIn, RICE_LIMIT)
        val divisor = (1 shl k) - 1
        val q = xIn / divisor
        val r = xIn % divisor
        if (q > 8) {
            bw.put(0x1FF, 9)
            bw.put(xIn, writeSampleSize)
        } else {
            if (q > 0) bw.put((1 shl q) - 1, q)
            bw.put(0, 1)
            if (k != 1) {
                if (r > 0) bw.put(r + 1, k) else bw.put(0, k - 1)
            }
        }
    }

    // ------------------------------------------------------------------
    // 解码（自校验用，遵循标准 ALAC 位流）

    fun decode(frame: ByteArray): IntArray {
        val br = BitReader(frame)
        val element = br.bits(3)
        if (element == 7) return IntArray(0)
        br.bits(4) // instance
        br.bits(12)
        val hasSize = br.bit() == 1
        val extraBits = br.bits(2) shl 3
        val compressed = br.bit() == 0
        val frames = if (hasSize) br.bitsLong(32).toInt() else FRAME_SIZE
        val bps = SAMPLE_SIZE - extraBits + CHANNELS - 1
        val out = Array(2) { IntArray(frames) }

        if (!compressed) {
            for (i in 0 until frames) {
                out[0][i] = br.sbits(SAMPLE_SIZE)
                out[1][i] = br.sbits(SAMPLE_SIZE)
            }
        } else {
            val shift = br.bits(8)
            val weight = br.bits(8)
            val coefs = Array(2) { IntArray(32) }
            val orders = IntArray(2)
            val quants = IntArray(2)
            val riceMods = IntArray(2)
            for (ch in 0 until 2) {
                br.bits(4) // prediction_type
                quants[ch] = br.bits(4)
                riceMods[ch] = br.bits(3)
                orders[ch] = br.bits(5)
                for (j in orders[ch] - 1 downTo 0) coefs[ch][j] = br.sbits(16)
            }
            for (ch in 0 until 2) {
                val residual = riceDecode(br, frames, bps, riceMods[ch] * RICE_HISTORY_MULT / 4)
                lpcPrediction(residual, out[ch], frames, bps, coefs[ch], orders[ch], quants[ch])
            }
            if (weight != 0) {
                for (i in 0 until frames) {
                    var a = out[0][i]
                    var b = out[1][i]
                    a -= (b * weight) shr shift
                    b += a
                    out[0][i] = b
                    out[1][i] = a
                }
            }
        }

        val result = IntArray(frames * 2)
        for (i in 0 until frames) {
            result[i * 2] = out[0][i]
            result[i * 2 + 1] = out[1][i]
        }
        return result
    }

    private fun riceDecode(br: BitReader, n: Int, bps: Int, riceHistoryMult: Int): IntArray {
        val out = IntArray(n)
        var history = RICE_INITIAL_HISTORY
        var signModifier = 0
        var i = 0
        while (i < n) {
            var k = minOf(floorLog2((history ushr 9) + 3), RICE_LIMIT)
            var x = decodeScalar(br, k, bps)
            x += signModifier
            signModifier = 0
            out[i] = (x shr 1) xor -(x and 1)

            if (x > 0xFFFF) {
                history = 0xFFFF
            } else {
                history += x * riceHistoryMult - ((history * riceHistoryMult) shr 9)
            }

            if (history < 128 && i + 1 < n) {
                k = minOf(7 - floorLog2(history) + ((history + 16) shr 6), RICE_LIMIT)
                val blockSize = decodeScalar(br, k, 16)
                if (blockSize > 0) {
                    val lim = minOf(blockSize, n - i - 1)
                    for (j in 1..lim) out[i + j] = 0
                    i += lim
                }
                if (blockSize <= 0xFFFF) signModifier = 1
                history = 0
            }
            i++
        }
        return out
    }

    private fun decodeScalar(br: BitReader, k: Int, bps: Int): Int {
        var x = 0
        while (x < 9 && br.bit() == 1) x++
        if (x > 8) {
            x = br.bits(bps)
        } else if (k != 1) {
            val extrabits = br.peekBits(k)
            x = (x shl k) - x
            if (extrabits > 1) {
                x += extrabits - 1
                br.skip(k)
            } else {
                br.skip(k - 1)
            }
        }
        return x
    }

    private fun lpcPrediction(
        error: IntArray,
        out: IntArray,
        n: Int,
        bps: Int,
        coefsIn: IntArray,
        order: Int,
        quant: Int
    ) {
        out[0] = error[0]
        if (n <= 1) return
        if (order == 0) {
            for (i in 1 until n) out[i] = error[i]
            return
        }
        if (order == 31) {
            for (i in 1 until n) out[i] = signExtend(out[i - 1] + error[i], bps)
            return
        }
        for (i in 1..minOf(order, n - 1)) out[i] = signExtend(out[i - 1] + error[i], bps)

        var i = order + 1
        while (i < n) {
            val d = out[i - 1 - order]
            var prediction = 0
            for (j in 0 until order) prediction += (out[i - order + j] - d) * coefsIn[j]
            prediction = (prediction + (1 shl (quant - 1))) shr quant
            var err = error[i]
            out[i] = signExtend(prediction + d + err, bps)

            val errorSign = if (err > 0) 1 else if (err < 0) -1 else 0
            if (errorSign != 0) {
                var j = 0
                while (j < order && err * errorSign > 0) {
                    val v = d - out[i - order + j]
                    var sg = if (v > 0) 1 else if (v < 0) -1 else 0
                    sg *= errorSign
                    coefsIn[j] -= sg
                    val vv = v * sg
                    err -= (vv shr quant) * (j + 1)
                    j++
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------------

    private fun signExtend(v: Int, bits: Int): Int = (v shl (32 - bits)) shr (32 - bits)

    private fun floorLog2(x: Int): Int = 31 - Integer.numberOfLeadingZeros(x)

    /** MSB-first 位写入器。 */
    private class BitWriter {
        private var buf = ByteArray(2048)
        private var bitPos = 0

        fun put(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                ensureCapacity()
                if (((value shr i) and 1) != 0) {
                    val idx = bitPos ushr 3
                    buf[idx] = (buf[idx].toInt() or (1 shl (7 - (bitPos and 7)))).toByte()
                }
                bitPos++
            }
        }

        fun putSigned(value: Int, bits: Int) = put(value, bits)

        fun toByteArray(): ByteArray = buf.copyOf((bitPos + 7) / 8)

        private fun ensureCapacity() {
            if ((bitPos ushr 3) >= buf.size) {
                buf = buf.copyOf(buf.size * 2)
            }
        }
    }

    /** MSB-first 位读取器。 */
    private class BitReader(private val data: ByteArray) {
        private var pos = 0

        fun bit(): Int {
            val b = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return b
        }

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }

        fun bitsLong(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 1) or bit().toLong() }
            return v
        }

        fun sbits(n: Int): Int {
            val v = bits(n)
            return (v shl (32 - n)) shr (32 - n)
        }

        fun peekBits(n: Int): Int {
            val saved = pos
            val v = bits(n)
            pos = saved
            return v
        }

        fun skip(n: Int) {
            pos += n
        }
    }
}
