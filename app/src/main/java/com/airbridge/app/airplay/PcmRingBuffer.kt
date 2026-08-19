package com.airbridge.app.airplay

/** Simple synchronized short-ring buffer for interleaved stereo PCM. */
class PcmRingBuffer(val capacityFrames: Int) {
    private val buf = ShortArray(capacityFrames * 2)
    private var readPos = 0
    private var writePos = 0
    private var count = 0
    private var overflowFrames = 0L

    @Synchronized
    fun write(samples: ShortArray) {
        var n = samples.size / 2
        if (n <= 0) return
        var off = 0
        if (n > capacityFrames) {
            off = (n - capacityFrames) * 2
            n = capacityFrames
        }
        for (i in 0 until n) {
            buf[writePos] = samples[off + i * 2]
            buf[writePos + 1] = samples[off + i * 2 + 1]
            writePos = (writePos + 2) % buf.size
            if (count < capacityFrames) {
                count++
            } else {
                overflowFrames++
                readPos = (readPos + 2) % buf.size
            }
        }
    }

    @Synchronized
    fun read(dst: ShortArray, frames: Int): Int {
        val n = minOf(frames, count)
        for (i in 0 until n) {
            dst[i * 2] = buf[readPos]
            dst[i * 2 + 1] = buf[readPos + 1]
            readPos = (readPos + 2) % buf.size
        }
        count -= n
        return n
    }

    @Synchronized
    fun availableFrames(): Int = count

    @Synchronized
    fun clear() {
        readPos = 0
        writePos = 0
        count = 0
        overflowFrames = 0
    }

    @Synchronized
    fun drainOverflowFrames(): Long {
        val v = overflowFrames
        overflowFrames = 0
        return v
    }
}
