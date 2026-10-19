package com.homepodplus.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.homepodplus.app.airplay.AirplayDevice
import com.homepodplus.app.airplay.MdnsDiscoverer
import com.homepodplus.app.airplay.RaopSender
import com.homepodplus.app.airplay.RaopSender.LatencyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class VolumeOffsetMode {
    FIXED, // 固定差值，例如 -10%（副音箱 = 主音箱 + offset）
    RATIO  // 相对比例，例如 10%（副音箱 = 主音箱 * (1 + offset/100)）
}

enum class ChannelMode {
    STEREO, // 完整立体声 (两箱皆立体声)
    SPLIT   // 分离左右声道 (独立物理声场)
}

enum class LeftSpeakerAssignment {
    SPEAKER1_LEFT, // 主音箱为左声道 / 副音箱为右声道
    SPEAKER2_LEFT  // 副音箱为左声道 / 主音箱为右声道
}

data class DualConfig(
    val speaker1Name: String = "主音箱",
    val speaker1Host: String = "",
    val speaker1Port: Int = 7000,
    val speaker2Name: String = "副音箱",
    val speaker2Host: String = "",
    val speaker2Port: Int = 7000,
    val offsetMode: VolumeOffsetMode = VolumeOffsetMode.FIXED,
    val offsetValue: Float = -10f, // 差值，例如 -10 代表小 10%
    val defaultVolume: Float = 50f,
    val channelMode: ChannelMode = ChannelMode.STEREO,
    val leftSpeaker: LeftSpeakerAssignment = LeftSpeakerAssignment.SPEAKER1_LEFT,
    val hardwareVolControl: Boolean = true, // 物理音量按键联动调节开关
    val showPersistentNotification: Boolean = true // 通知栏常驻实时控制开关
) {
    val isConfigured: Boolean
        get() = speaker1Host.isNotBlank() && speaker2Host.isNotBlank()

    fun calculateSlaveVolume(masterVol: Float): Float {
        return when (offsetMode) {
            VolumeOffsetMode.FIXED -> (masterVol + offsetValue).coerceIn(0f, 100f)
            VolumeOffsetMode.RATIO -> (masterVol * (1f + offsetValue / 100f)).coerceIn(0f, 100f)
        }
    }
}

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
        val volume: Float = 50f,
        val dualConfig: DualConfig = DualConfig()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var discoverer: MdnsDiscoverer? = null
    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    var sender1: RaopSender? = null
    var sender2: RaopSender? = null
    var sender: RaopSender?
        get() = sender1 ?: sender2
        set(value) {
            sender1 = value
        }

    private const val PREFS = "homepod_plus_prefs"
    private const val KEY_MANUAL = "manual_devices"
    private const val KEY_LATENCY = "latency_mode"
    private const val KEY_ROBUST = "robust_mode"
    private const val KEY_ALAC = "alac_on"
    private const val KEY_VOLUME = "volume"
    private const val KEY_S1_NAME = "s1_name"
    private const val KEY_S1_HOST = "s1_host"
    private const val KEY_S1_PORT = "s1_port"
    private const val KEY_S2_NAME = "s2_name"
    private const val KEY_S2_HOST = "s2_host"
    private const val KEY_S2_PORT = "s2_port"
    private const val KEY_OFFSET_MODE = "offset_mode"
    private const val KEY_OFFSET_VALUE = "offset_value"
    private const val KEY_DEF_VOLUME = "default_volume"
    private const val KEY_CHANNEL_MODE = "channel_mode"
    private const val KEY_LEFT_SPEAKER = "left_speaker"
    private const val KEY_HARDWARE_VOL = "hardware_vol_control"
    private const val KEY_PERSISTENT_NOTIF = "persistent_notif"

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
        val volume = prefs.getFloat(KEY_VOLUME, 50f).coerceIn(0f, 100f)
        val s1Name = prefs.getString(KEY_S1_NAME, "音箱 1") ?: "音箱 1"
        val s1Host = prefs.getString(KEY_S1_HOST, "") ?: ""
        val s1Port = prefs.getInt(KEY_S1_PORT, 7000)
        val s2Name = prefs.getString(KEY_S2_NAME, "音箱 2") ?: "音箱 2"
        val s2Host = prefs.getString(KEY_S2_HOST, "") ?: ""
        val s2Port = prefs.getInt(KEY_S2_PORT, 7000)
        val offModeName = prefs.getString(KEY_OFFSET_MODE, VolumeOffsetMode.FIXED.name) ?: VolumeOffsetMode.FIXED.name
        val offMode = runCatching { VolumeOffsetMode.valueOf(offModeName) }.getOrDefault(VolumeOffsetMode.FIXED)
        val offValue = prefs.getFloat(KEY_OFFSET_VALUE, -10f)
        val defVol = prefs.getFloat(KEY_DEF_VOLUME, 50f)
        val chModeName = prefs.getString(KEY_CHANNEL_MODE, ChannelMode.STEREO.name) ?: ChannelMode.STEREO.name
        val chMode = runCatching { ChannelMode.valueOf(chModeName) }.getOrDefault(ChannelMode.STEREO)
        val leftSpkName = prefs.getString(KEY_LEFT_SPEAKER, LeftSpeakerAssignment.SPEAKER1_LEFT.name) ?: LeftSpeakerAssignment.SPEAKER1_LEFT.name
        val leftSpk = runCatching { LeftSpeakerAssignment.valueOf(leftSpkName) }.getOrDefault(LeftSpeakerAssignment.SPEAKER1_LEFT)
        val hwVol = prefs.getBoolean(KEY_HARDWARE_VOL, true)
        val showNotif = prefs.getBoolean(KEY_PERSISTENT_NOTIF, true)
        val dualConfig = DualConfig(
            speaker1Name = s1Name,
            speaker1Host = s1Host,
            speaker1Port = s1Port,
            speaker2Name = s2Name,
            speaker2Host = s2Host,
            speaker2Port = s2Port,
            offsetMode = offMode,
            offsetValue = offValue,
            defaultVolume = defVol,
            channelMode = chMode,
            leftSpeaker = leftSpk,
            hardwareVolControl = hwVol,
            showPersistentNotification = showNotif
        )
        _state.update {
            it.copy(
                latencyMode = mode,
                robustMode = robust,
                alacOn = alacOn,
                volume = volume,
                dualConfig = dualConfig
            )
        }
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
        sender1?.latencyMode = mode
        sender2?.latencyMode = mode
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_LATENCY, mode.name)
            ?.apply()
        addLog("延迟模式: ${mode.name}")
    }

    fun setRobustMode(on: Boolean) {
        _state.update { it.copy(robustMode = on) }
        sender1?.robustMode = on
        sender2?.robustMode = on
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_ROBUST, on)
            ?.apply()
        addLog(if (on) "已开启网络稳健模式（冗余发包）" else "已关闭网络稳健模式")
    }

    fun setAlac(on: Boolean) {
        _state.update { it.copy(alacOn = on) }
        sender1?.alacOn = on
        sender2?.alacOn = on
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
                status = "正在连接...",
                volume = _state.value.volume
            )
        }
    }

    fun setStreamingDual(s1: RaopSender?, s2: RaopSender?) {
        sender1 = s1
        sender2 = s2
        _state.update { it.copy(connecting = false, streaming = true, status = "正在投送") }
    }

    fun setStreaming(s: RaopSender) {
        setStreamingDual(s, null)
    }

    fun setTestTone(on: Boolean) {
        _state.update { it.copy(testToneOn = on) }
    }

    fun setStatus(text: String) {
        _state.update { it.copy(status = text) }
    }

    fun resetConnection(reason: String?) {
        sender1 = null
        sender2 = null
        _state.update {
            it.copy(
                connecting = false,
                streaming = false,
                status = reason ?: "未投送",
                logs = it.logs + (reason?.let { r -> "已断开: $r" } ?: "已断开")
            )
        }
    }

    fun addLog(line: String) {
        Log.d("HomePodPlus", line)
        val ctx = contextForPrefs
        if (ctx != null) {
            logExecutor.execute {
                try {
                    val f = java.io.File(ctx.filesDir, "homepodplus.log")
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

    fun updateDualConfig(config: DualConfig) {
        _state.update { it.copy(dualConfig = config) }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_S1_NAME, config.speaker1Name)
            ?.putString(KEY_S1_HOST, config.speaker1Host)
            ?.putInt(KEY_S1_PORT, config.speaker1Port)
            ?.putString(KEY_S2_NAME, config.speaker2Name)
            ?.putString(KEY_S2_HOST, config.speaker2Host)
            ?.putInt(KEY_S2_PORT, config.speaker2Port)
            ?.putString(KEY_OFFSET_MODE, config.offsetMode.name)
            ?.putFloat(KEY_OFFSET_VALUE, config.offsetValue)
            ?.putFloat(KEY_DEF_VOLUME, config.defaultVolume)
            ?.putString(KEY_CHANNEL_MODE, config.channelMode.name)
            ?.putString(KEY_LEFT_SPEAKER, config.leftSpeaker.name)
            ?.putBoolean(KEY_HARDWARE_VOL, config.hardwareVolControl)
            ?.putBoolean(KEY_PERSISTENT_NOTIF, config.showPersistentNotification)
            ?.apply()
        val modeStr = if (config.channelMode == ChannelMode.STEREO) "立体声" else "分离声道(${if (config.leftSpeaker == LeftSpeakerAssignment.SPEAKER1_LEFT) "主左副右" else "副左主右"})"
        val hwStr = if (config.hardwareVolControl) "物理按键联动开启" else "物理按键联动关闭"
        val notifStr = if (config.showPersistentNotification) "常驻通知开启" else "常驻通知关闭"
        addLog("已更新双音箱配置: ${config.speaker1Name}(${config.speaker1Host}) & ${config.speaker2Name}(${config.speaker2Host}) · $modeStr · $hwStr · $notifStr")
    }

    fun setVolume(pct: Float) {
        val v = pct.coerceIn(0f, 100f)
        _state.update { it.copy(volume = v) }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putFloat(KEY_VOLUME, v)
            ?.apply()
        sender1?.setVolume(v.toDouble())
        val slaveV = _state.value.dualConfig.calculateSlaveVolume(v)
        sender2?.setVolume(slaveV.toDouble())
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
