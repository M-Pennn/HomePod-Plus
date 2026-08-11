package com.airbridge.app.airplay

import com.airbridge.app.airplay.AirplayCrypto.chacha20Poly1305Decrypt
import com.airbridge.app.airplay.AirplayCrypto.chacha20Poly1305Encrypt
import com.airbridge.app.airplay.AirplayCrypto.counterNonce8
import com.airbridge.app.airplay.AirplayCrypto.hkdfSha512
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * AirPlay 2 realtime audio sender state machine (HomePod / Apple TV 4K / macOS).
 * Protocol details ported from the clean-room Apache-2.0 reference.
 */
class RaopSender(
    private val device: AirplayDevice,
    private val listener: Listener
) {
    interface Listener {
        fun onState(state: State)
        fun onLog(message: String)
        fun onStarted()
        fun onStopped(reason: String?)
    }

    enum class State { IDLE, CONNECTING, PAIRING, HANDSHAKE, STREAMING, STOPPING }

    companion object {
        private const val FRAMES_PER_PACKET = 352
        private const val RATE = 44100
        private const val CHANNELS = 2
        private const val BACKLOG_SIZE = 1024
    }

    /** Receiver buffering policy: lower latency = less jitter tolerance. */
    enum class LatencyMode(val frames: Int, val min: Int, val max: Int) {
        STABLE(66150, 11025, 88200),
        LOW(11025, 4410, 44100),
        ULTRA(4410, 2205, 22050)
    }

    private val ring = PcmRingBuffer(RATE * 3)
    private val writeLock = Any()
    private val backlogLock = Any()
    private val backlog = arrayOfNulls<ByteArray>(BACKLOG_SIZE)
    private val backlogSeq = IntArray(BACKLOG_SIZE) { -1 }

    @Volatile
    private var running = false

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var wasUserStop = false

    @Volatile
    var latencyMode = LatencyMode.STABLE

    @Volatile
    var robustMode = false

    @Volatile
    private var latencyFrames = LatencyMode.STABLE.frames

    private var wallMs0 = 0L
    private var elapsedNanos0 = 0L

    private var sessionThread: Thread? = null
    private var pacerThread: Thread? = null
    private var syncThread: Thread? = null
    private var feedbackThread: Thread? = null
    private var volumeFlushThread: Thread? = null
    private var udpControlThread: Thread? = null
    private var udpTimingThread: Thread? = null
    private var eventThread: Thread? = null

    private var rtsp: Socket? = null
    private var eventSock: Socket? = null
    private var preferredLocalIp: String? = null
    private var audioSock: DatagramSocket? = null
    private var controlSock: DatagramSocket? = null
    private var timingSock: DatagramSocket? = null

    // Session identity
    private var sessionId = 0L
    private var dacpId = ""
    private var activeRemote = 0L
    private var cseq = 0
    private var sessionUuid = ""

    // Crypto state
    private var controlEncrypted = false
    private var ctrlSendCtr = 0L
    private var ctrlRecvCtr = 0L
    private var controlOut = ByteArray(0)
    private var controlIn = ByteArray(0)
    private var eventOut = ByteArray(0)
    private var eventIn = ByteArray(0)
    private var eventSendCtr = 0L
    private var eventRecvCtr = 0L
    private var eventFrameCount = 0
    private var loggedEventFrame = false
    private var audioKey = ByteArray(0)
    private var audioNonce = 0L
    private var encryptAudio = false

    // Stream state
    private var serverPort = 0
    private var controlPort = 0
    private var eventPort = 0
    private var startTs = 0L
    private var framesSent = 0L
    private var seq = 0
    private var firstAudio = true
    private var pendingVolumeDb = -1000.0
    private var lastVolumeSentAt = 0L
    private var volumeDirty = false
    private val volumeLock = Any()
    private var encBytesReceived = 0L
    private var airplay2 = true
    private var rtspSession = ""

    // Plaintext RX accumulator for the (possibly encrypted) control channel
    private val plainRxBuf = ByteArrayOutputStream()

    // Resampler state (capture rate -> 44100)
    private var rsBuf = ShortArray(0)
    private var rsRead = 0
    private var rsPhase = 0.0

    // ------------------------------------------------------------------

    fun start() {
        stop()
        wasUserStop = false
        latencyFrames = latencyMode.frames
        synchronized(volumeLock) {
            pendingVolumeDb = -1000.0
            volumeDirty = false
            lastVolumeSentAt = 0L
        }
        running = true
        setState(State.CONNECTING)
        sessionThread = Thread({ runSession() }, "raop-session").also { it.start() }
    }

    fun stop() {
        val wasRunning = running
        wasUserStop = true
        running = false
        if (wasRunning && state == State.STREAMING) {
            try {
                val extra = if (rtspSession.isNotEmpty()) listOf("Session" to rtspSession) else emptyList()
                val req = buildRtspRequest("TEARDOWN", rtspUri(), null, ByteArray(0), extra = extra)
                writeControl(req)
            } catch (_: Exception) {
            }
        }
        closeAll()
        joinThreads()
        setState(State.IDLE)
    }

    fun setVolume(pct: Double) {
        val p = pct.coerceIn(0.0, 100.0)
        val dbfs = pctToDbfs(p)
        synchronized(volumeLock) {
            pendingVolumeDb = dbfs
            volumeDirty = true
        }
        // Socket I/O stays on the background volume-flush thread.
    }

    /** Feed captured PCM (interleaved stereo 16-bit) into the send ring. */
    @Synchronized
    fun pushPcm(samples: ShortArray, rate: Int) {
        if (!running) return
        if (rate == RATE) {
            ring.write(samples)
            return
        }
        // Linear resample rate -> 44100
        val keep = if (rsRead < rsBuf.size) rsBuf.copyOfRange(rsRead, rsBuf.size) else ShortArray(0)
        rsBuf = keep + samples
        rsRead = 0
        val step = rate.toDouble() / RATE
        val out = ShortArray(4096)
        var produced = 0
        while (rsRead + 4 <= rsBuf.size && produced < out.size) {
            val aL = rsBuf[rsRead].toInt()
            val aR = rsBuf[rsRead + 1].toInt()
            val bL = rsBuf[rsRead + 2].toInt()
            val bR = rsBuf[rsRead + 3].toInt()
            out[produced++] = (aL + ((bL - aL) * rsPhase).toInt()).toShort()
            out[produced++] = (aR + ((bR - aR) * rsPhase).toInt()).toShort()
            rsPhase += step
            while (rsPhase >= 1.0) {
                rsPhase -= 1.0
                rsRead += 2
            }
        }
        if (produced > 0) ring.write(out.copyOf(produced))
        if (rsRead > 0) {
            rsBuf = rsBuf.copyOfRange(rsRead, rsBuf.size)
            rsRead = 0
        }
    }

    // ------------------------------------------------------------------
    // Session

    private fun runSession() {
        var reason: String? = null
        try {
            connectRtsp()
            try {
                runAp2()
            } catch (e: Exception) {
                listener.onLog("AirPlay 2 会话建立失败: ${e.message}")
                listener.onLog("回退到 AirPlay 1 模式...")
                closeAll()
                connectRtsp()
                runAp1()
            }
            // Keep consuming control-channel responses (volume/feedback replies)
            // until the session ends. Ignored, but the socket must be drained.
            while (running) {
                try {
                    readControlResponse()
                } catch (e: SocketTimeoutException) {
                    // normal
                }
            }
        } catch (e: Exception) {
            reason = e.message ?: e.javaClass.simpleName
            listener.onLog("会话失败: $reason")
        } finally {
            running = false
            closeAll()
            setState(State.IDLE)
            listener.onStopped(if (reason != null && !wasUserStop) reason else null)
        }
    }

    private fun runAp2() {
        airplay2 = true
        pairTransient()
        controlEncrypted = true
        setState(State.HANDSHAKE)
        listener.onLog("配对完成，开始 AirPlay 2 会话")
        ap2GetInfo()
        ap2SetupSession()
        openEventChannel()
        ap2Record()
        ap2SetupStream()
        startStreaming()
    }

    private fun runAp1() {
        airplay2 = false
        controlEncrypted = false
        encryptAudio = false
        audioKey = ByteArray(0)
        controlOut = ByteArray(0)
        controlIn = ByteArray(0)
        setState(State.HANDSHAKE)
        listener.onLog("AirPlay 1 模式: OPTIONS/ANNOUNCE/SETUP/RECORD")
        sendOptions()
        sendAnnounce()
        sendSetupAp1()
        sendRecordAp1()
        startStreaming()
    }

    private fun sendOptions() {
        val req = buildRtspRequest("OPTIONS", "*", null, ByteArray(0))
        writeControl(req)
        checkCode(readControlResponse(), "OPTIONS")
        listener.onLog("OPTIONS ok")
    }

    private fun sendAnnounce() {
        val localIp = preferredLocalIp ?: "127.0.0.1"
        val remoteIp = device.host
        val sdp = "v=0\r\n" +
            "o=iTunes $sessionId 0 IN IP4 $localIp\r\n" +
            "s=iTunes\r\n" +
            "c=IN IP4 $remoteIp\r\n" +
            "t=0 0\r\n" +
            "m=audio 0 RTP/AVP 96\r\n" +
            "a=rtpmap:96 L16/44100/2\r\n" +
            "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n"
        val req = buildRtspRequest("ANNOUNCE", rtspUri(), "application/sdp", sdp.toByteArray(Charsets.US_ASCII))
        writeControl(req)
        checkCode(readControlResponse(), "ANNOUNCE")
        listener.onLog("ANNOUNCE ok")
    }

    private fun sendSetupAp1() {
        val portCtl = controlSock!!.localPort
        val portTim = timingSock!!.localPort
        // TuneBlade's captured wire format uses camelCase timingPort; the
        // pyatv/owntone style uses timing_port. Try both URI forms too.
        val variants = listOf(
            Triple(rtspUri(), "control_port=$portCtl;timing_port=$portTim", "rtsp + timing_port"),
            Triple(rtspUri(), "control_port=$portCtl;timingPort=$portTim", "rtsp + timingPort"),
            Triple("/$sessionId", "control_port=$portCtl;timing_port=$portTim", "path + timing_port"),
            Triple("/$sessionId", "control_port=$portCtl;timingPort=$portTim", "path + timingPort")
        )
        var lastError: Exception? = null
        for ((uri, transport, label) in variants) {
            try {
                listener.onLog("AP1 SETUP 尝试[$label]")
                val req = buildRtspRequest(
                    "SETUP", uri, null, ByteArray(0),
                    extra = listOf("Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;$transport")
                )
                writeControl(req)
                val resp = readControlResponse(4000)
                checkCode(resp, "SETUP (AP1) [$label]")
                serverPort = 0
                controlPort = 0
                var timingPortRemote = 0
                for (opt in (resp.headers["transport"] ?: "").split(';')) {
                    val eq = opt.indexOf('=')
                    if (eq <= 0) continue
                    val k = opt.substring(0, eq).trim()
                    val v = opt.substring(eq + 1).trim().toIntOrNull() ?: 0
                    when (k) {
                        "server_port" -> serverPort = v
                        "control_port" -> controlPort = v
                        "timing_port" -> timingPortRemote = v
                    }
                }
                if (serverPort == 0) throw IOException("AP1 SETUP 未返回 server_port")
                if (controlPort == 0) controlPort = serverPort
                rtspSession = resp.headers["session"] ?: "1"
                listener.onLog("AP1 SETUP ok [$label]: server=$serverPort control=$controlPort timing=$timingPortRemote session=$rtspSession")
                return
            } catch (e: SocketTimeoutException) {
                lastError = e
                listener.onLog("AP1 SETUP [$label] 无响应")
            } catch (e: Exception) {
                lastError = e
                listener.onLog("AP1 SETUP [$label] 失败: ${e.message}")
                throw e
            }
        }
        throw IOException("AP1 SETUP 无响应（已尝试 4 种变体）", lastError)
    }

    private fun sendRecordAp1() {
        val rtpInfo = "seq=$seq;rtptime=${rtptime32()}"
        val req = buildRtspRequest(
            "RECORD", rtspUri(), null, ByteArray(0),
            extra = listOf(
                "Range" to "npt=0-",
                "RTP-Info" to rtpInfo,
                "Session" to rtspSession
            )
        )
        writeControl(req)
        checkCode(readControlResponse(), "RECORD (AP1)")
        listener.onLog("RECORD ok")
    }

    private fun connectRtsp() {
        val rnd = SecureRandom()
        sessionId = rnd.nextInt().toLong() and 0xFFFFFFFFL
        dacpId = java.lang.Long.toHexString(rnd.nextLong()).uppercase(Locale.US)
        activeRemote = rnd.nextLong() and 0xFFFFFFFFL
        sessionUuid = UUID.randomUUID().toString().uppercase(Locale.US)
        cseq = 0
        serverPort = 0
        controlPort = 0
        eventPort = 0
        rtspSession = ""
        encryptAudio = false
        audioNonce = 0
        encBytesReceived = 0
        controlEncrypted = false
        ctrlSendCtr = 0
        ctrlRecvCtr = 0
        eventSendCtr = 0
        eventRecvCtr = 0
        framesSent = 0
        firstAudio = true
        seq = rnd.nextInt(65536)
        plainRxBuf.reset()

        listener.onLog("连接 ${device.name} (${device.host}:${device.port}) ...")
        val s = Socket()
        s.connect(InetSocketAddress(device.host, device.port), 8000)
        s.tcpNoDelay = true
        s.soTimeout = 10000
        rtsp = s

        timingSock = DatagramSocket(0)
        controlSock = DatagramSocket(0)
        audioSock = DatagramSocket(0)
        // Larger UDP buffers reduce drops during Wi-Fi hiccups.
        try {
            audioSock!!.sendBufferSize = 512 * 1024
            controlSock!!.receiveBufferSize = 256 * 1024
            timingSock!!.receiveBufferSize = 256 * 1024
        } catch (_: Exception) {
        }
        wallMs0 = System.currentTimeMillis()
        elapsedNanos0 = android.os.SystemClock.elapsedRealtimeNanos()
        // HomePod probes the sender's UDP timing/control ports DURING SETUP;
        // start the responders now so SETUP doesn't wait 30s and fail.
        startUdpReaders()
        listener.onLog("TCP 已连接，开始瞬时配对")
        val sockLocal = s.localAddress.hostAddress ?: "?"
        val picked = pickLocalIp()
        preferredLocalIp = picked ?: sockLocal
        listener.onLog("本机地址: socket=$sockLocal, 选定=$preferredLocalIp")
    }

    private fun pairTransient() {
        setState(State.PAIRING)
        listener.onLog("POST /pair-setup (M1, transient)")
        val m1 = Tlv8.encode(
            listOf(
                Tlv8.METHOD to byteArrayOf(0),
                Tlv8.STATE to byteArrayOf(1),
                Tlv8.FLAGS to byteArrayOf(0x10)
            )
        )
        var resp = httpPost("/pair-setup", "application/octet-stream", m1)
        checkCode(resp, "/pair-setup M1")
        val m2 = Tlv8.decode(resp.body)
        if (m2.containsKey(Tlv8.ERROR)) throw IOException("配对被设备拒绝 (error=${m2[Tlv8.ERROR]?.firstOrNull()})")
        val salt = m2[Tlv8.SALT] ?: throw IOException("配对响应缺少 salt")
        val pubB = m2[Tlv8.PUBLIC_KEY] ?: throw IOException("配对响应缺少 public key")

        val srp = AirplayCrypto.SrpClient()
        srp.start("3939")
        if (!srp.process(salt, pubB)) throw IOException("SRP 参数校验失败")

        val m3 = Tlv8.encode(
            listOf(
                Tlv8.STATE to byteArrayOf(3),
                Tlv8.PUBLIC_KEY to srp.publicA(),
                Tlv8.PROOF to srp.proofM1()
            )
        )
        resp = httpPost("/pair-setup", "application/octet-stream", m3)
        checkCode(resp, "/pair-setup M3")
        val m4 = Tlv8.decode(resp.body)
        if (m4.containsKey(Tlv8.ERROR)) throw IOException("配对 PIN 未被接受 (error=${m4[Tlv8.ERROR]?.firstOrNull()})")
        m4[Tlv8.PROOF]?.let {
            if (!srp.verifyServerProof(it)) listener.onLog("警告: 服务端 SRP 证明校验失败（继续）")
        }

        val k = srp.sessionKey()
        controlOut = hkdfSha512("Control-Salt", "Control-Write-Encryption-Key", k, 32)
        controlIn = hkdfSha512("Control-Salt", "Control-Read-Encryption-Key", k, 32)
        eventIn = hkdfSha512("Events-Salt", "Events-Write-Encryption-Key", k, 32)
        eventOut = hkdfSha512("Events-Salt", "Events-Read-Encryption-Key", k, 32)
        audioKey = if (k.size > 32) k.copyOfRange(0, 32) else k
        audioNonce = 0
        encryptAudio = true
        listener.onLog("瞬时配对成功 (M4)")
    }

    private fun ap2GetInfo() {
        val resp = sendAp2("GET", "/info", null, ByteArray(0))
        checkCode(resp, "GET /info")
        listener.onLog("GET /info ok")
    }

    private fun ap2SetupSession() {
        // Some receivers are picky about the SETUP body fields, URI form,
        // X-Apple-StreamID or X-Apple-HKP; try a matrix of variants.
        data class Variant(val uri: String, val streamId: Boolean, val minimalBody: Boolean, val hkp: Int?, val label: String)
        // Fast fallback: if the canonical variants get no reply, the receiver
        // probably wants legacy AirPlay 1; don't burn time on more variants.
        val variants = listOf(
            Variant(rtspUri(), true, false, null, "完整字段 rtsp://ip/id"),
            Variant(rtspUri(), true, true, null, "最小字段 rtsp://ip/id")
        )
        var lastError: Exception? = null
        for (v in variants) {
            try {
                val body = if (v.minimalBody) sessionSetupBodyMinimal() else sessionSetupBody()
                listener.onLog("SETUP session 尝试[${v.label}] ${v.uri} (${body.size} bytes)")
                val resp = sendAp2(
                    "SETUP", v.uri, "application/x-apple-binary-plist", body,
                    streamId = v.streamId, hkp = v.hkp, timeoutMs = 4000
                )
                checkCode(resp, "SETUP session [${v.label}]")
                val root = Bplist.decode(resp.body) as? PValue.DictV
                eventPort = (root?.pairs?.firstOrNull { it.first == "eventPort" }?.second as? PValue.IntV)?.v?.toInt() ?: 0
                listener.onLog("SETUP session ok [${v.label}], eventPort=$eventPort")
                return
            } catch (e: SocketTimeoutException) {
                lastError = e
                listener.onLog("SETUP [${v.label}] 无响应")
            } catch (e: Exception) {
                lastError = e
                listener.onLog("SETUP [${v.label}] 失败: ${e.message}")
                throw e
            }
        }
        throw IOException("SETUP session 无响应（已尝试 2 种变体）", lastError)
    }

    private fun sessionSetupBody(): ByteArray {
        return Bplist.encode(
            PValue.DictV(
                listOf(
                    "deviceID" to PValue.StrV("AA:BB:CC:DD:EE:FF"),
                    "sessionUUID" to PValue.StrV(sessionUuid),
                    "timingPort" to PValue.IntV(timingSock!!.localPort.toLong()),
                    "timingProtocol" to PValue.StrV("NTP"),
                    "isMultiSelectAirPlay" to PValue.True,
                    "groupContainsGroupLeader" to PValue.False,
                    "macAddress" to PValue.StrV("AA:BB:CC:DD:EE:FF"),
                    "model" to PValue.StrV("iPhone14,3"),
                    "name" to PValue.StrV("AirBridge"),
                    "osBuildVersion" to PValue.StrV("20F66"),
                    "osName" to PValue.StrV("iPhone OS"),
                    "osVersion" to PValue.StrV("16.5"),
                    "senderSupportsRelay" to PValue.False,
                    "sourceVersion" to PValue.StrV("690.7.1"),
                    "statsCollectionEnabled" to PValue.False
                )
            )
        )
    }

    /** owntone-style minimal NTP session setup (works on most AirPlay 2 devices). */
    private fun sessionSetupBodyMinimal(): ByteArray {
        return Bplist.encode(
            PValue.DictV(
                listOf(
                    "deviceID" to PValue.StrV("AA:BB:CC:DD:EE:FF"),
                    "sessionUUID" to PValue.StrV(sessionUuid),
                    "timingPort" to PValue.IntV(timingSock!!.localPort.toLong()),
                    "timingProtocol" to PValue.StrV("NTP")
                )
            )
        )
    }

    private fun openEventChannel() {
        if (eventPort == 0) {
            listener.onLog("警告: 设备未提供 eventPort，继续（部分设备会拒绝）")
            return
        }
        val s = Socket()
        s.connect(InetSocketAddress(device.host, eventPort), 5000)
        s.tcpNoDelay = true
        s.soTimeout = 2000
        eventSock = s
        listener.onLog("事件通道已打开 -> ${device.host}:$eventPort")
        eventThread = Thread({ eventLoop() }, "raop-event").also { it.start() }
    }

    private fun ap2Record() {
        val resp = sendAp2("RECORD", rtspUri(), null, ByteArray(0))
        if (resp.code !in 200..299) listener.onLog("RECORD 返回 ${resp.code}（继续）")
        else listener.onLog("RECORD ok")
    }

    private fun ap2SetupStream() {
        val stream = PValue.DictV(
            listOf(
                "audioFormat" to PValue.IntV(0x40000L),
                "audioMode" to PValue.StrV("default"),
                "controlPort" to PValue.IntV(controlSock!!.localPort.toLong()),
                "ct" to PValue.IntV(2),
                "isMedia" to PValue.True,
                "latencyMax" to PValue.IntV(latencyMode.max.toLong()),
                "latencyMin" to PValue.IntV(latencyMode.min.toLong()),
                "shk" to PValue.DataV(audioKey),
                "spf" to PValue.IntV(FRAMES_PER_PACKET.toLong()),
                "sr" to PValue.IntV(RATE.toLong()),
                "type" to PValue.IntV(0x60),
                "supportsDynamicStreamID" to PValue.False,
                "streamConnectionID" to PValue.IntV(sessionId)
            )
        )
        val body = Bplist.encode(PValue.DictV(listOf("streams" to PValue.ArrV(listOf(stream)))))
        val resp = sendAp2("SETUP", rtspUri(), "application/x-apple-binary-plist", body)
        checkCode(resp, "SETUP stream")
        val root = Bplist.decode(resp.body) as? PValue.DictV
        val streams = root?.pairs?.firstOrNull { it.first == "streams" }?.second as? PValue.ArrV
        val s0 = streams?.items?.firstOrNull() as? PValue.DictV
        serverPort = (s0?.pairs?.firstOrNull { it.first == "dataPort" }?.second as? PValue.IntV)?.v?.toInt() ?: 0
        controlPort = (s0?.pairs?.firstOrNull { it.first == "controlPort" }?.second as? PValue.IntV)?.v?.toInt() ?: serverPort
        if (serverPort == 0) throw IOException("SETUP stream 未返回 dataPort")
        listener.onLog("SETUP stream ok, dataPort=$serverPort controlPort=$controlPort")
    }

    private fun startStreaming() {
        setState(State.STREAMING)
        startTs = ntp2ts(ntpNow(), RATE)
        framesSent = 0
        firstAudio = true
        sendSyncPacket(true)
        synchronized(volumeLock) {
            // Default to 25% on a fresh connection so users are not startled.
            if (pendingVolumeDb <= -999.0) pendingVolumeDb = pctToDbfs(25.0)
            volumeDirty = true
        }
        flushVolume()
        startPacer()
        startSyncTimer()
        startFeedback()
        startVolumeFlush()
        listener.onLog("正在推流: 44.1kHz 16bit 立体声 -> ${device.name}")
        listener.onStarted()
    }

    private fun startVolumeFlush() {
        volumeFlushThread = Thread({
            while (running) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
                flushVolume()
            }
        }, "raop-volume").also { it.start() }
    }

    private fun flushVolume() {
        val db: Double
        synchronized(volumeLock) {
            if (!volumeDirty) return
            val now = System.currentTimeMillis()
            if (now - lastVolumeSentAt < 100) return
            volumeDirty = false
            lastVolumeSentAt = now
            db = pendingVolumeDb
        }
        try {
            sendVolume(db)
        } catch (e: Exception) {
            listener.onLog("音量设置失败: ${e.javaClass.simpleName}: ${e.message}")
            // mark dirty again so the flush thread retries
            synchronized(volumeLock) {
                volumeDirty = true
            }
        }
    }

    private fun pctToDbfs(pct: Double): Double =
        if (pct < 0.01) -144.0 else -30.0 + 0.3 * pct

    // ------------------------------------------------------------------
    // Control channel

    private fun rtspUri(): String = "rtsp://${preferredLocalIp ?: "127.0.0.1"}/$sessionId"

    private fun pickLocalIp(): String? {
        val devPrefix = device.host.substringBeforeLast('.')
        var fallback: String? = null
        try {
            val nets = NetworkInterface.getNetworkInterfaces() ?: return null
            for (n in nets) {
                if (!n.isUp || n.isLoopback) continue
                val addrs = n.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a !is Inet4Address) continue
                    val ip = a.hostAddress ?: continue
                    if (ip.startsWith("$devPrefix.")) return ip
                    if (fallback == null && ip.startsWith("192.168.")) fallback = ip
                }
            }
        } catch (_: Exception) {
        }
        return fallback
    }

    private fun buildRtspRequest(
        method: String,
        uri: String,
        contentType: String?,
        body: ByteArray,
        extra: List<Pair<String, String>> = emptyList(),
        streamId: Boolean = false,
        hkp: Int? = null
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: ${cseq++}\r\n")
        sb.append("User-Agent: AirPlay/550.10\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        sb.append("Client-Instance: $dacpId\r\n")
        sb.append("X-Apple-Client-Name: AirBridge\r\n")
        if (streamId) sb.append("X-Apple-StreamID: 1\r\n")
        if (hkp != null) sb.append("X-Apple-HKP: $hkp\r\n")
        for ((k, v) in extra) sb.append("$k: $v\r\n")
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        if (body.isNotEmpty()) sb.append("Content-Length: ${body.size}\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII) + body
    }

    private fun httpPost(uri: String, contentType: String?, body: ByteArray): ControlResponse {
        val sb = StringBuilder()
        sb.append("POST $uri HTTP/1.1\r\n")
        sb.append("CSeq: ${cseq++}\r\n")
        sb.append("User-Agent: AirPlay/550.10\r\n")
        sb.append("Connection: keep-alive\r\n")
        sb.append("X-Apple-HKP: 4\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        sb.append("Client-Instance: $dacpId\r\n")
        sb.append("X-Apple-Client-Name: AirBridge\r\n")
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n\r\n")
        writeControl(sb.toString().toByteArray(Charsets.US_ASCII) + body)
        return readControlResponse()
    }

    private fun sendAp2(
        method: String,
        uri: String,
        contentType: String?,
        body: ByteArray,
        streamId: Boolean = method == "SETUP",
        hkp: Int? = null,
        timeoutMs: Int = 10000
    ): ControlResponse {
        val req = buildRtspRequest(method, uri, contentType, body, streamId = streamId, hkp = hkp)
        if (method == "SETUP") {
            val hex = req.take(180).joinToString("") { "%02x".format(it) }
            listener.onLog("SETUP 请求前 180 字节: $hex")
        }
        writeControl(req)
        return readControlResponse(timeoutMs)
    }

    private fun sendVolume(dbfs: Double) {
        val body = String.format(Locale.US, "volume: %.6f", dbfs).toByteArray(Charsets.US_ASCII)
        val extra = if (rtspSession.isNotEmpty()) listOf("Session" to rtspSession) else emptyList()
        val req = buildRtspRequest("SET_PARAMETER", rtspUri(), "text/parameters", body, extra = extra)
        writeControl(req)
    }

    private fun sendFeedback() {
        val sb = StringBuilder()
        sb.append("POST /feedback RTSP/1.0\r\n")
        sb.append("CSeq: ${cseq++}\r\n")
        sb.append("User-Agent: AirPlay/550.10\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        sb.append("Client-Instance: $dacpId\r\n")
        sb.append("Content-Length: 0\r\n\r\n")
        writeControl(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun writeControl(data: ByteArray) {
        synchronized(writeLock) {
            val out = rtsp!!.getOutputStream()
            if (controlEncrypted) {
                var off = 0
                while (off < data.size) {
                    val len = minOf(1024, data.size - off)
                    val lp = byteArrayOf((len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte())
                    val nonce = counterNonce8(ctrlSendCtr++)
                    val ct = chacha20Poly1305Encrypt(controlOut, nonce, data.copyOfRange(off, off + len), lp)
                    out.write(lp)
                    out.write(ct)
                    off += len
                }
            } else {
                out.write(data)
            }
            out.flush()
        }
    }

    private class ControlResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        val statusLine: String
    )

    private fun readControlResponse(timeoutMs: Int = 10000): ControlResponse {
        rtsp!!.soTimeout = timeoutMs
        try {
            while (true) {
                parseControlResponse()?.let {
                    debugLog("readControlResponse 返回 code=${it.code} body=${it.body.size}B")
                    return it
                }
                try {
                    fillPlainRx()
                    debugLog("readControlResponse: 缓冲=${plainRxBuf.size()}B")
                } catch (e: SocketTimeoutException) {
                    listener.onLog("等待响应超时，已收到 $encBytesReceived 个加密字节")
                    val cur = plainRxBuf.toByteArray()
                    val headEnd = findHeadEnd(cur)
                    val ascii = cur.take(300).joinToString("") { b ->
                        if (b.toInt() in 32..126) b.toInt().toChar().toString() else "."
                    }
                    listener.onLog("超时诊断: 明文=${cur.size} 字节, headEnd=$headEnd")
                    listener.onLog("超时诊断(可打印前300): $ascii")
                    if (headEnd != null && headEnd > 0) {
                        listener.onLog(
                            "超时诊断(响应头): " +
                                String(cur, 0, minOf(headEnd, 600), Charsets.US_ASCII)
                        )
                    }
                    throw e
                }
            }
        } finally {
            rtsp!!.soTimeout = 10000
        }
    }

    private fun fillPlainRx() {
        if (controlEncrypted) {
            val frame = readEncryptedFrame()
            plainRxBuf.write(frame)
        } else {
            val input = rtsp!!.getInputStream()
            var headEnd = findHeadEnd(plainRxBuf.toByteArray())
            if (headEnd == null) {
                var state = headState(plainRxBuf.toByteArray())
                while (state < 4) {
                    val b = input.read()
                    if (b < 0) throw IOException("控制连接被关闭")
                    plainRxBuf.write(b)
                    state = when {
                        b == '\r'.code && (state == 0 || state == 2) -> state + 1
                        b == '\n'.code && (state == 1 || state == 3) -> state + 1
                        b == '\r'.code -> 1
                        else -> 0
                    }
                    if (plainRxBuf.size() > 1 shl 20) throw IOException("控制响应过大")
                }
                headEnd = findHeadEnd(plainRxBuf.toByteArray()) ?: throw IOException("控制响应头不完整")
            }
            val contentLen = parseContentLength(
                String(plainRxBuf.toByteArray(), 0, headEnd, Charsets.US_ASCII)
            )
            val total = headEnd + 4 + contentLen
            while (plainRxBuf.size() < total) {
                val b = input.read()
                if (b < 0) throw IOException("控制连接被关闭")
                plainRxBuf.write(b)
            }
        }
    }

    private fun readEncryptedFrame(): ByteArray {
        val input = rtsp!!.getInputStream()
        val lenData = readFully(input, 2)
        encBytesReceived += 2
        val len = (lenData[0].toInt() and 0xFF) or ((lenData[1].toInt() and 0xFF) shl 8)
        debugLog("收到加密帧: 明文长度=$len")
        val ct = readFully(input, len + 16)
        encBytesReceived += len + 16
        val nonce = counterNonce8(ctrlRecvCtr++)
        val pt = chacha20Poly1305Decrypt(controlIn, nonce, ct, lenData)
            ?: throw IOException("控制通道解密失败")
        debugLog("解密帧成功: ${pt.size} 字节")
        return pt
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw IOException("连接被关闭")
            off += r
        }
        return out
    }

    private fun parseControlResponse(): ControlResponse? {
        val data = plainRxBuf.toByteArray()
        val headEnd = findHeadEnd(data) ?: return null
        debugLog("parseControlResponse: 找到响应头, 缓冲=${data.size}B")
        val head = String(data, 0, headEnd, Charsets.US_ASCII)
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return null
        val statusLine = lines[0]
        val parts = statusLine.split(" ")
        val code = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                headers[lines[i].substring(0, colon).trim().lowercase(Locale.US)] =
                    lines[i].substring(colon + 1).trim()
            }
        }
        val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
        val total = headEnd + 4 + contentLen
        if (data.size < total) {
            listener.onLog("解析: 响应头完整但 body 不完整 (需要 $total, 已有 ${data.size})")
            return null
        }
        val body = data.copyOfRange(headEnd + 4, total)
        val rem = data.copyOfRange(total, data.size)
        plainRxBuf.reset()
        plainRxBuf.write(rem)
        debugLog("parseControlResponse: 消费响应 code=$code body=${body.size}B 剩余=${rem.size}B")
        return ControlResponse(code, headers, body, statusLine)
    }

    private fun debugLog(msg: String) {
        if (state == State.PAIRING || state == State.HANDSHAKE) {
            listener.onLog(msg)
        }
    }

    private fun findHeadEnd(data: ByteArray): Int? {
        for (i in 0 until data.size - 3) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() && data[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun headState(cur: ByteArray): Int {
        val tail = cur.copyOfRange(maxOf(0, cur.size - 3), cur.size)
        return when {
            tail.size >= 3 && tail[0] == '\r'.code.toByte() && tail[1] == '\n'.code.toByte() && tail[2] == '\r'.code.toByte() -> 3
            tail.size >= 2 && tail[0] == '\r'.code.toByte() && tail[1] == '\n'.code.toByte() -> 2
            tail.size >= 1 && tail[0] == '\r'.code.toByte() -> 1
            else -> 0
        }
    }

    private fun parseContentLength(head: String): Int {
        for (line in head.split("\r\n")) {
            if (line.startsWith("content-length:", ignoreCase = true)) {
                return line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun checkCode(resp: ControlResponse, step: String) {
        if (resp.code !in 200..299) {
            val hint = when (resp.code) {
                470 -> "（设备要求 PIN 配对）"
                403 -> "（被设备的访问控制拒绝）"
                else -> ""
            }
            throw IOException("$step 返回 HTTP ${resp.code} $hint")
        }
    }

    // ------------------------------------------------------------------
    // Event channel keep-alive

    private fun eventLoop() {
        val input = eventSock!!.getInputStream()
        val plainBuf = ByteArrayOutputStream()
        while (running) {
            try {
                val lenData = readFullyTimeout(input, 2) ?: continue
                val len = (lenData[0].toInt() and 0xFF) or ((lenData[1].toInt() and 0xFF) shl 8)
                val ct = readFullyTimeout(input, len + 16) ?: continue
                val nonce = counterNonce8(eventRecvCtr)
                var pt = chacha20Poly1305Decrypt(eventIn, nonce, ct, lenData)
                if (pt == null) {
                    // Diagnostic + fallback: maybe the key direction or the
                    // counter base differs on this receiver.
                    val swapped = chacha20Poly1305Decrypt(eventOut, nonce, ct, lenData)
                    val prevCtr = chacha20Poly1305Decrypt(eventIn, counterNonce8(eventRecvCtr - 1), ct, lenData)
                    val nextCtr = chacha20Poly1305Decrypt(eventIn, counterNonce8(eventRecvCtr + 1), ct, lenData)
                    val ctrlKey = chacha20Poly1305Decrypt(controlIn, nonce, ct, lenData)
                    listener.onLog(
                        "事件帧解密失败 len=$len ctr=$eventRecvCtr " +
                            "read=${swapped != null} ctr-1=${prevCtr != null} ctr+1=${nextCtr != null} controlKey=${ctrlKey != null}"
                    )
                    when {
                        swapped != null -> {
                            listener.onLog("事件密钥方向相反，已交换")
                            val t = eventIn
                            eventIn = eventOut
                            eventOut = t
                            pt = swapped
                        }
                        prevCtr != null -> {
                            listener.onLog("事件计数器实际从 -1 开始")
                            pt = prevCtr
                        }
                        nextCtr != null -> {
                            listener.onLog("事件计数器实际从 +1 开始")
                            eventRecvCtr++
                            pt = nextCtr
                        }
                        else -> {
                            listener.onLog("事件帧无法解密，跳过该帧")
                            continue
                        }
                    }
                }
                val out = pt ?: continue
                eventRecvCtr++
                plainBuf.write(out)
                if (eventFrameCount < 5) {
                    eventFrameCount++
                    listener.onLog("事件帧解密成功 len=$len")
                }
                while (true) {
                    val data = plainBuf.toByteArray()
                    val headEnd = findHeadEnd(data) ?: break
                    val head = String(data, 0, headEnd, Charsets.US_ASCII)
                    val contentLen = parseContentLength(head)
                    val total = headEnd + 4 + contentLen
                    if (data.size < total) break
                    var cseqLine: String? = null
                    for (line in head.split("\r\n")) {
                        if (line.startsWith("cseq:", ignoreCase = true)) {
                            cseqLine = line.substringAfter(':').trim()
                            break
                        }
                    }
                    val rem = data.copyOfRange(total, data.size)
                    plainBuf.reset()
                    plainBuf.write(rem)
                    sendEvent200(cseqLine)
                }
            } catch (e: SocketTimeoutException) {
                // normal
            } catch (e: IOException) {
                if (running) listener.onLog("事件通道断开: ${e.message}")
                break
            }
        }
    }

    private fun readFullyTimeout(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = try {
                input.read(out, off, n - off)
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (r < 0) return null
            off += r
        }
        return out
    }

    private fun sendEvent200(cseqLine: String?) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 200 OK\r\n")
        sb.append("Server: AirTunes/550.10\r\n")
        if (cseqLine != null) sb.append("CSeq: $cseqLine\r\n")
        sb.append("\r\n")
        val out = eventSock!!.getOutputStream()
        synchronized(writeLock) {
            val data = sb.toString().toByteArray(Charsets.US_ASCII)
            val ctr0 = eventSendCtr
            writeEncryptedFrames(out, eventOut, ctr0, data)
            eventSendCtr = ctr0 + (data.size + 1023) / 1024
        }
    }

    private fun writeEncryptedFrames(out: OutputStream, key: ByteArray, startCtr: Long, data: ByteArray) {
        var ctr = startCtr
        var off = 0
        while (off < data.size) {
            val len = minOf(1024, data.size - off)
            val lp = byteArrayOf((len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte())
            val nonce = counterNonce8(ctr++)
            val ct = chacha20Poly1305Encrypt(key, nonce, data.copyOfRange(off, off + len), lp)
            out.write(lp)
            out.write(ct)
            off += len
        }
        out.flush()
    }

    // ------------------------------------------------------------------
    // Streaming

    private fun startPacer() {
        pacerThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val startNanos = System.nanoTime()
            while (running) {
                val elapsed = System.nanoTime() - startNanos
                val target = elapsed * RATE / 1_000_000_000L
                var sent = 0
                while (running && framesSent + FRAMES_PER_PACKET <= target && sent < 8) {
                    try {
                        sendAudioPacket()
                    } catch (e: Exception) {
                        listener.onLog("音频发送失败: ${e.message}")
                        running = false
                        break
                    }
                    sent++
                }
                try {
                    Thread.sleep(8)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "raop-pacer").also { it.start() }
    }

    private fun startSyncTimer() {
        syncThread = Thread({
            while (running) {
                try {
                    Thread.sleep(if (robustMode) 500 else 1000)
                } catch (e: InterruptedException) {
                    break
                }
                if (running) {
                    try {
                        sendSyncPacket(false)
                    } catch (_: Exception) {
                    }
                }
            }
        }, "raop-sync").also { it.start() }
    }

    private fun startFeedback() {
        feedbackThread = Thread({
            while (running) {
                val interval = if (airplay2) 2000L else 25000L
                try {
                    Thread.sleep(interval)
                } catch (e: InterruptedException) {
                    break
                }
                if (running) {
                    try {
                        sendFeedback()
                    } catch (_: Exception) {
                    }
                }
            }
        }, "raop-feedback").also { it.start() }
    }

    private fun startUdpReaders() {
        udpControlThread = Thread({ udpControlLoop() }, "raop-udp-control").also { it.start() }
        udpTimingThread = Thread({ udpTimingLoop() }, "raop-udp-timing").also { it.start() }
    }

    private fun udpControlLoop() {
        val sock = controlSock ?: return
        sock.soTimeout = 500
        val buf = ByteArray(65536)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                handleControlDatagram(p)
            } catch (e: SocketTimeoutException) {
                // normal
            } catch (e: IOException) {
                if (controlSock !== sock) break // socket replaced by a new session
                if (running) {
                    try {
                        Thread.sleep(500)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun handleControlDatagram(dg: DatagramPacket) {
        val data = dg.data
        val size = dg.length
        if (size < 8) return
        val type = data[1].toInt() and 0x7F
        if (type != 0x55) return
        val lostSeq = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val lostCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        for (i in 0 until lostCount) {
            val s = (lostSeq + i) and 0xFFFF
            val slot = s and (BACKLOG_SIZE - 1)
            val pkt: ByteArray?
            synchronized(backlogLock) {
                pkt = if (backlogSeq[slot] == s) backlog[slot] else null
            }
            if (pkt != null) {
                val resp = ByteArray(4 + pkt.size)
                resp[0] = 0x80.toByte()
                resp[1] = 0xD6.toByte()
                resp[2] = ((s shr 8) and 0xFF).toByte()
                resp[3] = (s and 0xFF).toByte()
                System.arraycopy(pkt, 0, resp, 4, pkt.size)
                controlSock!!.send(DatagramPacket(resp, resp.size, dg.address, dg.port))
            }
        }
    }

    private fun udpTimingLoop() {
        val sock = timingSock ?: return
        sock.soTimeout = 500
        val buf = ByteArray(64)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                handleTimingDatagram(p)
            } catch (e: SocketTimeoutException) {
                // normal
            } catch (e: IOException) {
                if (timingSock !== sock) break // socket replaced by a new session
                if (running) {
                    try {
                        Thread.sleep(500)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun handleTimingDatagram(dg: DatagramPacket) {
        val data = dg.data
        if (dg.length < 32) return
        val now = ntpNow()
        val resp = ByteArray(32)
        resp[0] = data[0]
        resp[1] = 0xD3.toByte()
        resp[2] = 0
        resp[3] = 7
        System.arraycopy(data, 24, resp, 8, 8) // reftime = request sendtime
        putU32(resp, 16, (now ushr 32) and 0xFFFFFFFFL)
        putU32(resp, 20, now and 0xFFFFFFFFL)
        putU32(resp, 24, (now ushr 32) and 0xFFFFFFFFL)
        putU32(resp, 28, now and 0xFFFFFFFFL)
        timingSock!!.send(DatagramPacket(resp, resp.size, dg.address, dg.port))
    }

    private fun sendAudioPacket() {
        val frames = ShortArray(FRAMES_PER_PACKET * CHANNELS)
        val got = ring.read(frames, FRAMES_PER_PACKET)
        if (got < FRAMES_PER_PACKET) {
            java.util.Arrays.fill(frames, got * CHANNELS, frames.size, 0.toShort())
        }
        val hdr = ByteArray(12)
        hdr[0] = 0x80.toByte()
        hdr[1] = if (firstAudio) 0xE0.toByte() else 0x60.toByte()
        hdr[2] = ((seq shr 8) and 0xFF).toByte()
        hdr[3] = (seq and 0xFF).toByte()
        val rtptime = rtptime32()
        putU32(hdr, 4, rtptime)
        putU32(hdr, 8, sessionId)

        val payload = if (encryptAudio) encodeAlacFrame(frames) else encodeL16(frames)
        val wire: ByteArray
        if (encryptAudio) {
            val aad = hdr.copyOfRange(4, 12)
            val nonce = counterNonce8(audioNonce++)
            val ct = chacha20Poly1305Encrypt(audioKey, nonce, payload, aad)
            wire = ct + nonce
        } else {
            wire = payload
        }
        val pkt = hdr + wire
        audioSock!!.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(device.host), serverPort))
        if (robustMode) {
            // Proactive redundancy: re-send the same packet so a single UDP
            // loss on weak Wi-Fi is self-healed without waiting for retransmit.
            audioSock!!.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(device.host), serverPort))
        }

        synchronized(backlogLock) {
            backlog[seq and (BACKLOG_SIZE - 1)] = pkt
            backlogSeq[seq and (BACKLOG_SIZE - 1)] = seq
        }
        firstAudio = false
        seq = (seq + 1) and 0xFFFF
        framesSent += FRAMES_PER_PACKET
    }

    private fun encodeAlacFrame(frames: ShortArray): ByteArray {
        val out = ByteArrayOutputStream()
        var cur = 0
        var filled = 0
        fun put(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                cur = (cur shl 1) or ((value shr i) and 1)
                filled++
                if (filled == 8) {
                    out.write(cur)
                    cur = 0
                    filled = 0
                }
            }
        }
        put(1, 3) // CPE (stereo)
        put(0, 4)
        put(0, 12)
        put(0, 1) // hasSize = 0 -> default 352
        put(0, 2) // wastedBytes
        put(1, 1) // isNotCompressed = 1
        for (s in frames) put(s.toInt() and 0xFFFF, 16)
        put(7, 3) // END
        if (filled > 0) out.write(cur shl (8 - filled))
        return out.toByteArray()
    }

    private fun encodeL16(frames: ShortArray): ByteArray {
        val out = ByteArray(frames.size * 2)
        var i = 0
        for (s in frames) {
            out[i++] = ((s.toInt() shr 8) and 0xFF).toByte()
            out[i++] = (s.toInt() and 0xFF).toByte()
        }
        return out
    }

    private fun sendSyncPacket(first: Boolean) {
        if (serverPort == 0 || controlPort == 0) return
        val curNtp = ts2ntp(startTs + framesSent, RATE)
        val now = rtptime32()
        val pkt = ByteArray(20)
        pkt[0] = if (first) 0x90.toByte() else 0x80.toByte()
        pkt[1] = 0xD4.toByte()
        pkt[2] = 0
        pkt[3] = 7
        putU32(pkt, 4, (now - latencyFrames) and 0xFFFFFFFFL)
        putU32(pkt, 8, (curNtp ushr 32) and 0xFFFFFFFFL)
        putU32(pkt, 12, curNtp and 0xFFFFFFFFL)
        putU32(pkt, 16, now)
        controlSock!!.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(device.host), controlPort))
    }

    private fun rtptime32(): Long = (latencyFrames + framesSent) and 0xFFFFFFFFL

    private fun ntpNow(): Long {
        // Monotonic high-resolution clock anchored to wall time at connect:
        // microsecond precision without wall-clock jumps.
        val us = wallMs0 * 1000L + (android.os.SystemClock.elapsedRealtimeNanos() - elapsedNanos0) / 1000L
        val sec = us / 1_000_000L
        val frac = us % 1_000_000L
        return ((sec + 0x83AA7E80L) shl 32) or ((frac shl 32) / 1_000_000L)
    }

    private fun ntp2ts(ntp: Long, rate: Int): Long = ((ntp ushr 16) * rate.toLong()) ushr 16

    private fun ts2ntp(ts: Long, rate: Int): Long {
        val v = java.math.BigInteger.valueOf(ts)
            .shiftLeft(16)
            .divide(java.math.BigInteger.valueOf(rate.toLong()))
            .shiftLeft(16)
        return v.toLong()
    }

    private fun putU32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = ((v shr 24) and 0xFF).toByte()
        dst[off + 1] = ((v shr 16) and 0xFF).toByte()
        dst[off + 2] = ((v shr 8) and 0xFF).toByte()
        dst[off + 3] = (v and 0xFF).toByte()
    }

    // ------------------------------------------------------------------

    private fun setState(s: State) {
        state = s
        listener.onState(s)
    }

    private fun closeAll() {
        try {
            rtsp?.close()
        } catch (_: Exception) {
        }
        try {
            eventSock?.close()
        } catch (_: Exception) {
        }
        try {
            audioSock?.close()
        } catch (_: Exception) {
        }
        try {
            controlSock?.close()
        } catch (_: Exception) {
        }
        try {
            timingSock?.close()
        } catch (_: Exception) {
        }
    }

    private fun joinThreads() {
        for (t in listOf(sessionThread, pacerThread, syncThread, feedbackThread, volumeFlushThread, udpControlThread, udpTimingThread, eventThread)) {
            if (t != null && t.isAlive) {
                try {
                    t.join(500)
                } catch (_: InterruptedException) {
                }
            }
        }
    }
}
