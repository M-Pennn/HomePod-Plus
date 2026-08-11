package com.airbridge.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.airbridge.app.airplay.AirplayDevice
import com.airbridge.app.airplay.MdnsDiscoverer
import com.airbridge.app.airplay.RaopSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** App-wide state shared between the UI and the casting service. */
object CastController {

    data class UiState(
        val discovering: Boolean = false,
        val devices: List<AirplayDevice> = emptyList(),
        val connecting: Boolean = false,
        val streaming: Boolean = false,
        val testToneOn: Boolean = false,
        val status: String = "未连接",
        val logs: List<String> = emptyList(),
        val volume: Float = 80f
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var discoverer: MdnsDiscoverer? = null
    var sender: RaopSender? = null

    private const val PREFS = "airbridge_prefs"
    private const val KEY_MANUAL = "manual_devices"

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

    private var contextForPrefs: Context? = null

    fun loadManual(context: Context) {
        contextForPrefs = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    fun setConnecting(device: AirplayDevice) {
        _state.update { it.copy(connecting = true, streaming = false, status = "正在连接 ${device.name}...") }
    }

    fun setStreaming(s: RaopSender) {
        sender = s
        _state.update { it.copy(connecting = false, streaming = true, status = "正在投射") }
    }

    fun setTestTone(on: Boolean) {
        _state.update { it.copy(testToneOn = on) }
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
        try {
            val ctx = contextForPrefs
            if (ctx != null) {
                val f = java.io.File(ctx.filesDir, "airbridge.log")
                f.appendText("${System.currentTimeMillis()} $line\n")
            }
        } catch (_: Exception) {
        }
        _state.update { it.copy(logs = (it.logs + line).takeLast(300)) }
    }

    fun setVolume(pct: Float) {
        _state.update { it.copy(volume = pct.coerceIn(0f, 100f)) }
        sender?.setVolume(pct.toDouble())
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, CastService::class.java))
    }

    fun toggleTestTone(context: Context) {
        context.startService(Intent(context, CastService::class.java).setAction(CastService.ACTION_TEST_TONE))
    }
}
