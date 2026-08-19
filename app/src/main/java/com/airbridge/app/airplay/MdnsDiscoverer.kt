package com.airbridge.app.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.MulticastSocket
import java.net.NetworkInterface

data class AirplayDevice(
    val name: String,
    val host: String,
    val port: Int,
    val model: String? = null,
    val features: Long? = null,
    val sf: Long? = null,
    val txt: Map<String, String> = emptyMap(),
    val type: String = "_airplay._tcp.local"
) {
    val supportsTransientPairing: Boolean
        get() = sf != null && (sf and 0x4L) != 0L
}

/**
 * Raw mDNS browser for _airplay._tcp and _raop._tcp.
 * Tries the classic multicast socket on 5353 first; if that port is taken it
 * falls back to QU-bit queries so receivers unicast replies to our port.
 */
class MdnsDiscoverer(
    private val context: Context,
    private val listener: (List<AirplayDevice>) -> Unit,
    private val log: (String) -> Unit = {}
) {
    private var thread: Thread? = null
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var unicastFallback = false
    private var nsdManager: NsdManager? = null
    private val nsdListeners = ArrayList<NsdManager.DiscoveryListener>()
    @Volatile private var running = false

    private val pending = LinkedHashMap<String, Pending>()
    private val devices = LinkedHashMap<String, AirplayDevice>()
    private val rawKeys = HashSet<String>()
    private val lastSeen = HashMap<String, Long>()
    private val queryTypes = listOf("_airplay._tcp.local", "_raop._tcp.local")

    private class Pending {
        var name: String? = null
        var host: String? = null
        var ip: String? = null
        var port: Int = 0
        var txt: MutableMap<String, String> = LinkedHashMap()
        var type: String = ""
    }

    fun start() {
        if (running) return
        running = true
        startNsd()
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("airbridge-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread = Thread({ loop() }, "mdns-browser").also { it.start() }
    }

    fun stop() {
        running = false
        stopNsd()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        thread?.interrupt()
        thread = null
    }

    private fun startNsd() {
        try {
            val nm = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = nm
            for (type in listOf("_airplay._tcp.", "_raop._tcp.")) {
                val l = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {
                        log("Nsd 发现已启动: $serviceType")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        handleNsdFound(serviceInfo)
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        val before = devices.size
                        devices.entries.removeAll { e ->
                            e.value.name == serviceInfo.serviceName && e.value.type == serviceInfo.serviceType
                        }
                        if (devices.size != before) {
                            listener(devices.values.toList())
                        }
                    }

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        log("Nsd 发现启动失败: $serviceType (code=$errorCode)")
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }
                nsdListeners.add(l)
                nm.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
            }
        } catch (e: Exception) {
            log("NsdManager 不可用: ${e.message}")
        }
    }

    private fun stopNsd() {
        val nm = nsdManager ?: return
        for (l in nsdListeners) {
            try {
                nm.stopServiceDiscovery(l)
            } catch (_: Exception) {
            }
        }
        nsdListeners.clear()
        nsdManager = null
    }

    private fun handleNsdFound(info: NsdServiceInfo) {
        val nm = nsdManager ?: return
        try {
            nm.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        log("Nsd 解析失败 ${serviceInfo.serviceName}: code=$errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = if (Build.VERSION.SDK_INT >= 34) {
                            serviceInfo.hostAddresses
                                ?.firstOrNull { it is Inet4Address }
                                ?.hostAddress
                                ?: serviceInfo.hostAddresses?.firstOrNull()?.hostAddress
                        } else {
                            @Suppress("DEPRECATION")
                            serviceInfo.host?.hostAddress
                        } ?: return
                        if (host.isBlank()) return
                        val attrs = serviceInfo.attributes
                        val txt = HashMap<String, String>()
                        for ((k, v) in attrs) {
                            txt[k.lowercase()] = String(v, Charsets.UTF_8)
                        }
                        val type = serviceInfo.serviceType ?: "_airplay._tcp."
                        val model = txt["model"] ?: txt["am"]
                        val sf = txt["sf"]?.toLongOrNull(16)
                        val dev = AirplayDevice(
                            name = serviceInfo.serviceName,
                            host = host,
                            port = serviceInfo.port,
                            model = model,
                            sf = sf,
                            txt = txt,
                            type = type
                        )
                        val key = "$type|${dev.name}|${dev.host}:${dev.port}"
                        val prevSize = devices.size
                        devices[key] = dev
                        if (devices.size != prevSize) {
                            listener(devices.values.toList())
                        }
                    }
                }
            )
        } catch (e: Exception) {
            log("Nsd 解析异常: ${e.message}")
        }
    }

    // ---- mDNS wire helpers -------------------------------------------------

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var off = start
        var jumped = false
        var end = start
        var jumps = 0
        while (true) {
            if (off >= data.size || labels.size > 32) break
            val len = data[off].toInt() and 0xFF
            if (len == 0) {
                off++
                if (!jumped) end = off
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (off + 1 >= data.size) break
                jumps++
                if (jumps > 8) break
                val ptr = ((len and 0x3F) shl 8) or (data[off + 1].toInt() and 0xFF)
                if (!jumped) end = off + 2
                jumped = true
                off = ptr
                continue
            }
            if (off + 1 + len > data.size) break
            labels.add(String(data, off + 1, len, Charsets.UTF_8))
            off += 1 + len
        }
        return labels.joinToString(".") to end
    }

    private fun buildQuery(name: String, unicastReply: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0); out.write(0) // transaction id (ignored for mDNS)
        out.write(0); out.write(0) // flags
        out.write(0); out.write(1) // qdcount
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        out.write(encodeName(name))
        out.write(0); out.write(12) // PTR
        val qclass = if (unicastReply) 0x8001 else 0x0001
        out.write((qclass shr 8) and 0xFF); out.write(qclass and 0xFF)
        return out.toByteArray()
    }

    private data class Record(
        val name: String,
        val type: Int,
        val target: String?,
        val port: Int,
        val host: String?,
        val ip: String?,
        val txt: List<String>?
    )

    private fun parsePacket(data: ByteArray): List<Record> {
        if (data.size < 12) return emptyList()
        val an = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val ar = ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
        var off = 12
        val qd = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        for (i in 0 until qd) {
            val r = readName(data, off)
            off = r.second + 4
            if (off > data.size) return emptyList()
        }
        val out = ArrayList<Record>()
        for (section in 0 until 2) {
            val count = if (section == 0) an else ar
            for (i in 0 until count) {
                if (off + 10 > data.size) return out
                val name = readName(data, off)
                off = name.second
                if (off + 10 > data.size) return out
                val rtype = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                val rdlen = ((data[off + 8].toInt() and 0xFF) shl 8) or (data[off + 9].toInt() and 0xFF)
                off += 10
                if (off + rdlen > data.size) return out
                val rdata = data.copyOfRange(off, off + rdlen)
                off += rdlen
                var target: String? = null
                var port = 0
                var host: String? = null
                var ip: String? = null
                var txt: List<String>? = null
                when (rtype) {
                    12 -> {
                        val t = readName(data, off - rdlen)
                        target = t.first
                    }
                    33 -> {
                        if (rdlen >= 7) {
                            port = ((rdata[2].toInt() and 0xFF) shl 8) or (rdata[3].toInt() and 0xFF)
                            val h = readName(data, off - rdlen + 6)
                            host = h.first
                        }
                    }
                    1 -> if (rdlen == 4) {
                        ip = rdata.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    }
                    16 -> {
                        txt = ArrayList()
                        var p = 0
                        while (p < rdlen) {
                            val l = rdata[p].toInt() and 0xFF
                            p++
                            if (p + l <= rdlen) {
                                txt.add(String(rdata, p, l, Charsets.UTF_8))
                                p += l
                            }
                        }
                    }
                }
                out.add(Record(name.first, rtype, target, port, host, ip, txt))
            }
        }
        return out
    }

    // ---- browser loop ------------------------------------------------------

    private fun loop() {
        var socketOk = false
        while (running && !socketOk) {
            socketOk = openSocket()
            if (!socketOk && running) {
                try {
                    Thread.sleep(3000)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
        if (!running) return

        val sock = socket!!
        sock.soTimeout = 1000
        val group = InetAddress.getByName("224.0.0.251")
        val buf = ByteArray(65536)
        var lastQuery = 0L
        var loopCount = 0
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastQuery > 2000) {
                for (q in queryTypes) {
                    try {
                        val qb = buildQuery(q, unicastFallback)
                        val p = DatagramPacket(qb, qb.size, group, 5353)
                        sock.send(p)
                    } catch (e: IOException) {
                        break
                    }
                }
                lastQuery = now
            }
            loopCount++
            if (loopCount % 8 == 0) {
                val stale = rawKeys.filter { (lastSeen[it] ?: 0L) < now - 30_000 }
                for (k in stale) {
                    devices.remove(k)
                    rawKeys.remove(k)
                    lastSeen.remove(k)
                }
                if (stale.isNotEmpty()) {
                    listener(devices.values.toList())
                }
            }
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val records = parsePacket(p.data.copyOfRange(0, p.length))
                for (r in records) {
                    handleRecord(r)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // normal
            } catch (e: IOException) {
                if (running) {
                    // socket died; retry after a pause
                    try {
                        Thread.sleep(1000)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
        }
        try {
            sock.close()
        } catch (_: Exception) {
        }
    }

    private fun openSocket(): Boolean {
        unicastFallback = false
        val group = InetAddress.getByName("224.0.0.251")
        val iface = findWifiInterface()
        val s: MulticastSocket = try {
            MulticastSocket(5353).also {
                it.reuseAddress = true
                it.timeToLive = 2
                if (iface != null) it.networkInterface = iface
                it.joinGroup(group)
            }
        } catch (e1: Exception) {
            unicastFallback = true
            try {
                MulticastSocket(0).also {
                    it.reuseAddress = true
                    it.timeToLive = 2
                    if (iface != null) it.networkInterface = iface
                    it.joinGroup(group)
                }
            } catch (e2: Exception) {
                val msg = "mDNS 套接字创建失败: ${e1.message} / ${e2.message}"
                android.util.Log.e("AirBridge", msg)
                log(msg)
                return false
            }
        }
        socket = s
        val msg = if (unicastFallback) "mDNS: 5353 端口被占用，使用单播回退模式" else "mDNS: 组播模式"
        android.util.Log.d("AirBridge", msg)
        log(msg)
        return true
    }

    private fun findWifiInterface(): NetworkInterface? {
        try {
            val nets = NetworkInterface.getNetworkInterfaces() ?: return null
            val list = nets.toList()
            for (n in list) {
                if (!n.isUp || n.isLoopback) continue
                if (n.name.equals("wlan0", ignoreCase = true)) return n
            }
            for (n in list) {
                if (!n.isUp || n.isLoopback) continue
                val addrs = n.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address) {
                        val ip = a.hostAddress ?: continue
                        if (a.isSiteLocalAddress) return n
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    @Synchronized
    private fun handleRecord(r: Record) {
        when (r.type) {
            12 -> { // PTR: name = service, target = instance
                val svc = r.name
                val instance = r.target ?: return
                val p = pending.getOrPut(instance) { Pending() }
                p.type = svc
                val shortName = instance.substringBefore("._").substringAfterLast('@')
                p.name = shortName
                maybeEmit(instance, p)
            }
            33 -> { // SRV: name = instance
                val p = pending.getOrPut(r.name) {
                    Pending().also {
                        it.name = r.name.substringBefore("._").substringAfterLast('@')
                        it.type = r.name.substringAfter('.')
                    }
                }
                p.port = r.port
                p.host = r.host
                maybeEmit(r.name, p)
            }
            1 -> { // A: name = host fqdn
                val ip = r.ip ?: return
                for ((instance, p) in pending) {
                    if (p.host == r.name) {
                        p.ip = ip
                        if (p.host != null && p.port != 0 && p.name != null) {
                            maybeEmit(instance, p, ip)
                        }
                    }
                }
            }
            16 -> { // TXT: name = instance
                val p = pending.getOrPut(r.name) {
                    Pending().also {
                        it.name = r.name.substringBefore("._").substringAfterLast('@')
                        it.type = r.name.substringAfter('.')
                    }
                }
                for (item in r.txt ?: emptyList()) {
                    val eq = item.indexOf('=')
                    if (eq > 0) {
                        p.txt[item.substring(0, eq).lowercase()] = item.substring(eq + 1)
                    }
                }
                maybeEmit(r.name, p)
            }
        }
    }

    @Synchronized
    private fun maybeEmit(instance: String, p: Pending, ipOverride: String? = null) {
        val name = p.name ?: return
        val host = p.ip ?: ipOverride ?: return
        if (host.isBlank() || p.port == 0) return
        val type = p.type
        val key = "$type|$instance"
        val model = p.txt["model"] ?: p.txt["am"]
        val features = p.txt["features"]?.substringBefore(',')?.toLongOrNull(16)
        val sf = p.txt["sf"]?.toLongOrNull(16)
        val dev = AirplayDevice(
            name = name,
            host = host,
            port = p.port,
            model = model,
            features = features,
            sf = sf,
            txt = p.txt.toMap(),
            type = type
        )
        val changed = devices[key] != dev
        devices[key] = dev
        lastSeen[key] = System.currentTimeMillis()
        rawKeys.add(key)
        if (changed) listener(devices.values.toList())
    }
}
