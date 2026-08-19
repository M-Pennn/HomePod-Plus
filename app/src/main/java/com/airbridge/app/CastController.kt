package com.airbridge.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.airbridge.app.airplay.AirplayDevice
import com.airbridge.app.airplay.MdnsDiscoverer
import com.airbridge.app.airplay.RaopSender
import com.airbridge.app.airplay.RaopSender.LatencyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** App-wide state shared between the UI and the casting service. */
@SuppressLint("StaticFieldLeak") // stores only applicationContext
object CastController {

    data class UiState(
        val discovering: Boolean = false,
        val devices: List<AirplayDevice> = emptyList(),
        val connecting: Boolean = false,
        val streaming: Boolean = false,
        val testToneOn: Boolean = false,
        val latencyMode: LatencyMode = LatencyMode.STABLE,
        val robustMode: Boolean = false,
        val alacOn: Boolean = true,
        val status: String = "未连接",
        val logs: List<String> = emptyList(),
        val volume: Float = 25f
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var discoverer: MdnsDiscoverer? = null
    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    var sender: RaopSender? = null

    private const val PREFS = "airbridge_prefs"
    private const val KEY_MANUAL = "manual_devices"
    private const val KEY_LATENCY = "latency_mode"
    private const val KEY_ROBUST = "robust_mode"
    private const val KEY_ALAC = "alac_on"
    private const val KEY_VOLUME = "volume"

    fun startDiscovery(context: Context) {
        if (discoverer != null) return
        _state.update { it.copy(discovering = true, logs = it.logs + "正在扫描局域网 AirPlay 设备...") }
        discoverer = MdnsDiscoverer(
            context = context,
            listener = { devices ->
                val prev = _state.value.devices.map { it.host to it.port }
                _state.update { it.copy(devices = devices.sortedBy { d -> d.name }) }
                val now = _state.value.devices
                for (d in now) {
                    if ((d.host to d.port) !in prev) {
                        addLog("发现设备: ${d.name} (${d.host}:${d.port}, ${d.model ?: "AirPlay"})")
                    }
                }
            },
            log = { addLog(it) }
        ).also { it.start() }
    }

    fun stopDiscovery() {
        discoverer?.stop()
        discoverer = null
        _state.update { it.copy(discovering = false) }
    }

    fun addManual(device: AirplayDevice) {
        _state.update {
            it.copy(
                devices = (it.devices + device).distinctBy { d -> d.host to d.port },
                logs = it.logs + "已手动添加 ${device.name} (${device.host}:${device.port})"
            )
        }
        saveManual(contextForPrefs ?: return)
    }

    fun removeManual(device: AirplayDevice) {
        _state.update {
            it.copy(
                devices = it.devices.filterNot { d -> d.host == device.host && d.port == device.port },
                logs = it.logs + "已删除 ${device.name} (${device.host}:${device.port})"
            )
        }
        saveManual(contextForPrefs ?: return)
    }

    private var contextForPrefs: Context? = null

    fun loadManual(context: Context) {
        contextForPrefs = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modeName = prefs.getString(KEY_LATENCY, LatencyMode.STABLE.name) ?: LatencyMode.STABLE.name
        val mode = runCatching { LatencyMode.valueOf(modeName) }.getOrDefault(LatencyMode.STABLE)
        val robust = prefs.getBoolean(KEY_ROBUST, false)
        val alacOn = prefs.getBoolean(KEY_ALAC, true)
        val volume = prefs.getFloat(KEY_VOLUME, 25f).coerceIn(0f, 100f)
        _state.update { it.copy(latencyMode = mode, robustMode = robust, alacOn = alacOn, volume = volume) }
        val raw = prefs.getString(KEY_MANUAL, "") ?: ""
        for (line in raw.split("\n")) {
            val parts = line.split("|")
            if (parts.size == 3) {
                val name = parts[0]
                val host = parts[1]
                val port = parts[2].toIntOrNull() ?: 7000
                if (host.isNotBlank()) {
                    val dev = AirplayDevice(name = name, host = host, port = port, type = "_airplay._tcp.local")
                    _state.update {
                        it.copy(devices = (it.devices + dev).distinctBy { d -> d.host to d.port })
                    }
                }
            }
        }
    }

    private fun saveManual(context: Context) {
        val lines = _state.value.devices
            .filter { it.type == "_manual" || it.txt.isEmpty() }
            .distinctBy { it.host to it.port }
            .joinToString("\n") { "${it.name}|${it.host}|${it.port}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MANUAL, lines)
            .apply()
    }

    fun setLatencyMode(mode: LatencyMode) {
        _state.update { it.copy(latencyMode = mode) }
        sender?.latencyMode = mode
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_LATENCY, mode.name)
            ?.apply()
        addLog("延迟模式: ${mode.name}")
    }

    fun setRobustMode(on: Boolean) {
        _state.update { it.copy(robustMode = on) }
        sender?.robustMode = on
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_ROBUST, on)
            ?.apply()
        addLog(if (on) "已开启网络稳健模式（冗余发包）" else "已关闭网络稳健模式")
    }

    fun setAlac(on: Boolean) {
        _state.update { it.copy(alacOn = on) }
        sender?.alacOn = on
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_ALAC, on)
            ?.apply()
        addLog(if (on) "已开启压缩 ALAC（减小音频包）" else "已关闭压缩 ALAC（使用未压缩帧）")
    }

    fun setConnecting(device: AirplayDevice) {
        _state.update {
            it.copy(
                connecting = true,
                streaming = false,
                status = "正在连接 ${device.name}...",
                volume = _state.value.volume
            )
        }
    }

    fun setStreaming(s: RaopSender) {
        sender = s
        _state.update { it.copy(connecting = false, streaming = true, status = "正在投射 ${s.deviceName}") }
    }

    fun setTestTone(on: Boolean) {
        _state.update { it.copy(testToneOn = on) }
    }

    fun setStatus(text: String) {
        _state.update { it.copy(status = text) }
    }

    fun resetConnection(reason: String?) {
        sender = null
        _state.update {
            it.copy(
                connecting = false,
                streaming = false,
                status = reason ?: "已断开",
                logs = it.logs + (reason?.let { "已断开: $it" } ?: "已断开")
            )
        }
    }

    fun addLog(line: String) {
        Log.d("AirBridge", line)
        val ctx = contextForPrefs
        if (ctx != null) {
            logExecutor.execute {
                try {
                    val f = java.io.File(ctx.filesDir, "airbridge.log")
                    if (f.length() > 256L * 1024) f.delete()
                    f.appendText("${System.currentTimeMillis()} $line\n")
                } catch (_: Exception) {
                }
            }
        }
        _state.update { it.copy(logs = (it.logs + line).takeLast(300)) }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun setVolume(pct: Float) {
        val v = pct.coerceIn(0f, 100f)
        _state.update { it.copy(volume = v) }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putFloat(KEY_VOLUME, v)
            ?.apply()
        sender?.setVolume(v.toDouble())
    }

    fun stop(context: Context) {
        // Must deliver ACTION_STOP so the service runs its cleanup path.
        context.startService(
            Intent(context, CastService::class.java).setAction(CastService.ACTION_STOP)
        )
    }

    fun toggleTestTone(context: Context) {
        context.startService(Intent(context, CastService::class.java).setAction(CastService.ACTION_TEST_TONE))
    }
}
