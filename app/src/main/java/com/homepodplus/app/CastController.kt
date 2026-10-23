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
import kotlin.math.roundToInt

enum class VolumeOffsetMode {
    FIXED, // 固定差值，例如 -10%（副音箱 = 主音箱 + offset）
    RATIO  // 相对比例，例如 -10% 即 90%（副音箱 = 主音箱 * (1 + offset/100)）
}

enum class SpeakerChannel {
    STEREO, // 完整立体声
    LEFT,   // 左声道
    RIGHT   // 右声道
}

@Deprecated("Use SpeakerChannel instead")
enum class ChannelMode {
    STEREO,
    SPLIT
}

@Deprecated("Use SpeakerItemConfig.channel instead")
enum class LeftSpeakerAssignment {
    SPEAKER1_LEFT,
    SPEAKER2_LEFT
}

data class SpeakerItemConfig(
    val name: String = "",
    val host: String = "",
    val port: Int = 7000,
    val channel: SpeakerChannel = SpeakerChannel.STEREO,
    val volumeOffset: Float = 0f // 只能 <= 0f，0f 代表与主箱同音量
)

data class DualConfig(
    val deviceCount: Int = 2, // 1..4
    val speakers: List<SpeakerItemConfig> = listOf(
        SpeakerItemConfig(name = "设备 1", host = "", port = 7000, channel = SpeakerChannel.STEREO, volumeOffset = 0f),
        SpeakerItemConfig(name = "设备 2", host = "", port = 7000, channel = SpeakerChannel.STEREO, volumeOffset = 0f),
        SpeakerItemConfig(name = "设备 3", host = "", port = 7000, channel = SpeakerChannel.STEREO, volumeOffset = 0f),
        SpeakerItemConfig(name = "设备 4", host = "", port = 7000, channel = SpeakerChannel.STEREO, volumeOffset = 0f)
    ),
    val offsetMode: VolumeOffsetMode = VolumeOffsetMode.FIXED,
    val defaultVolume: Float = 50f,
    val hardwareVolControl: Boolean = true, // 物理音量按键联动调节开关
    val showPersistentNotification: Boolean = true, // 通知栏常驻实时控制开关
    val autoReconnectEnabled: Boolean = false, // 定时自动重连开关（预防长时间播放破音）
    val autoReconnectMinutes: Int = 50 // 定时自动重连分钟数（默认 50 分钟）
) {
    // 兼容原有构造函数调用
    constructor(
        speaker1Name: String = "设备 1",
        speaker1Host: String = "",
        speaker1Port: Int = 7000,
        speaker2Name: String = "设备 2",
        speaker2Host: String = "",
        speaker2Port: Int = 7000,
        offsetMode: VolumeOffsetMode = VolumeOffsetMode.FIXED,
        offsetValue: Float = 0f,
        defaultVolume: Float = 50f,
        channelMode: ChannelMode = ChannelMode.STEREO,
        leftSpeaker: LeftSpeakerAssignment = LeftSpeakerAssignment.SPEAKER1_LEFT,
        hardwareVolControl: Boolean = true,
        showPersistentNotification: Boolean = true,
        autoReconnectEnabled: Boolean = false,
        autoReconnectMinutes: Int = 50
    ) : this(
        deviceCount = 2,
        speakers = listOf(
            SpeakerItemConfig(
                name = speaker1Name,
                host = speaker1Host,
                port = speaker1Port,
                channel = if (channelMode == ChannelMode.SPLIT && leftSpeaker == LeftSpeakerAssignment.SPEAKER1_LEFT) SpeakerChannel.LEFT else if (channelMode == ChannelMode.SPLIT) SpeakerChannel.RIGHT else SpeakerChannel.STEREO,
                volumeOffset = 0f
            ),
            SpeakerItemConfig(
                name = speaker2Name,
                host = speaker2Host,
                port = speaker2Port,
                channel = if (channelMode == ChannelMode.SPLIT && leftSpeaker == LeftSpeakerAssignment.SPEAKER2_LEFT) SpeakerChannel.LEFT else if (channelMode == ChannelMode.SPLIT) SpeakerChannel.RIGHT else SpeakerChannel.STEREO,
                volumeOffset = offsetValue.coerceAtMost(0f)
            ),
            SpeakerItemConfig(name = "设备 3", host = "", port = 7000, channel = SpeakerChannel.STEREO, volumeOffset = 0f),
            SpeakerItemConfig(name = "设备 4", host = "", port = 7000, channel = SpeakerChannel.STEREO, volumeOffset = 0f)
        ),
        offsetMode = offsetMode,
        defaultVolume = defaultVolume,
        hardwareVolControl = hardwareVolControl,
        showPersistentNotification = showPersistentNotification,
        autoReconnectEnabled = autoReconnectEnabled,
        autoReconnectMinutes = autoReconnectMinutes
    )

    val speaker1Name: String get() = speakers.getOrNull(0)?.name?.ifBlank { "设备 1" } ?: "设备 1"
    val speaker1Host: String get() = speakers.getOrNull(0)?.host ?: ""
    val speaker1Port: Int get() = speakers.getOrNull(0)?.port ?: 7000
    val speaker2Name: String get() = speakers.getOrNull(1)?.name?.ifBlank { "设备 2" } ?: "设备 2"
    val speaker2Host: String get() = speakers.getOrNull(1)?.host ?: ""
    val speaker2Port: Int get() = speakers.getOrNull(1)?.port ?: 7000
    val offsetValue: Float get() = speakers.getOrNull(1)?.volumeOffset ?: 0f

    val isConfigured: Boolean
        get() {
            val count = deviceCount.coerceIn(1, 4)
            if (speakers.size < count) return false
            for (i in 0 until count) {
                if (speakers[i].host.isBlank()) return false
            }
            return true
        }

    fun calculateSlaveVolume(masterVol: Float, speakerIndex: Int = 1): Float =
        calculateVolume(speakerIndex, masterVol)

    fun calculateVolume(index: Int, masterVol: Float): Float {
        if (index == 0) return masterVol.coerceIn(0f, 100f)
        val spk = speakers.getOrNull(index) ?: return masterVol.coerceIn(0f, 100f)
        val offset = spk.volumeOffset.coerceAtMost(0f)
        return when (offsetMode) {
            VolumeOffsetMode.FIXED -> (masterVol + offset).coerceIn(0f, 100f)
            VolumeOffsetMode.RATIO -> (masterVol * (1f + offset / 100f)).coerceIn(0f, 100f)
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
        val dualConfig: DualConfig = DualConfig(),
        val accessDeniedAlert: Boolean = false,
        val currentDurationSeconds: Long = 0L,
        val lastDurationSeconds: Long = 0L
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var discoverer: MdnsDiscoverer? = null
    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val _senders = arrayOfNulls<RaopSender>(4)
    val senders: List<RaopSender?> get() = _senders.toList()
    var sender1: RaopSender?
        get() = _senders[0]
        set(value) { _senders[0] = value }
    var sender2: RaopSender?
        get() = _senders[1]
        set(value) { _senders[1] = value }
    var sender: RaopSender?
        get() = _senders.firstOrNull { it != null }
        set(value) { _senders[0] = value }

    private const val PREFS = "homepod_plus_prefs"
    private const val KEY_MANUAL = "manual_devices"
    private const val KEY_LATENCY = "latency_mode"
    private const val KEY_ROBUST = "robust_mode"
    private const val KEY_ALAC = "alac_on"
    private const val KEY_VOLUME = "volume"
    private const val KEY_DEVICE_COUNT = "device_count"
    private const val KEY_OFFSET_MODE = "offset_mode"
    private const val KEY_DEF_VOLUME = "default_volume"
    private const val KEY_HARDWARE_VOL = "hardware_vol_control"
    private const val KEY_PERSISTENT_NOTIF = "persistent_notif"
    private const val KEY_AUTO_RECONNECT_ENABLED = "auto_reconnect_enabled"
    private const val KEY_AUTO_RECONNECT_MINUTES = "auto_reconnect_minutes"
    private const val KEY_LAST_DURATION = "last_duration_seconds"

    // 兼容历史版本键名
    private const val KEY_S1_NAME = "s1_name"
    private const val KEY_S1_HOST = "s1_host"
    private const val KEY_S1_PORT = "s1_port"
    private const val KEY_S2_NAME = "s2_name"
    private const val KEY_S2_HOST = "s2_host"
    private const val KEY_S2_PORT = "s2_port"
    private const val KEY_OFFSET_VALUE = "offset_value"
    private const val KEY_CHANNEL_MODE = "channel_mode"
    private const val KEY_LEFT_SPEAKER = "left_speaker"

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
        val deviceCount = prefs.getInt(KEY_DEVICE_COUNT, 2).coerceIn(1, 4)
        val offModeName = prefs.getString(KEY_OFFSET_MODE, VolumeOffsetMode.FIXED.name) ?: VolumeOffsetMode.FIXED.name
        val offMode = runCatching { VolumeOffsetMode.valueOf(offModeName) }.getOrDefault(VolumeOffsetMode.FIXED)
        val defVol = prefs.getFloat(KEY_DEF_VOLUME, 50f)
        val hwVol = prefs.getBoolean(KEY_HARDWARE_VOL, true)
        val showNotif = prefs.getBoolean(KEY_PERSISTENT_NOTIF, true)
        val autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT_ENABLED, false)
        val autoReconnectMin = prefs.getInt(KEY_AUTO_RECONNECT_MINUTES, 50).coerceIn(1, 1440)
        val lastDuration = prefs.getLong(KEY_LAST_DURATION, 0L)

        val speakers = ArrayList<SpeakerItemConfig>()
        val hasNewFormat = prefs.contains("spk_0_host") || prefs.contains(KEY_DEVICE_COUNT)

        for (i in 0 until 4) {
            if (hasNewFormat) {
                val name = prefs.getString("spk_${i}_name", "设备 ${i + 1}") ?: "设备 ${i + 1}"
                val host = prefs.getString("spk_${i}_host", "") ?: ""
                val port = prefs.getInt("spk_${i}_port", 7000)
                val chName = prefs.getString("spk_${i}_channel", SpeakerChannel.STEREO.name) ?: SpeakerChannel.STEREO.name
                val channel = runCatching { SpeakerChannel.valueOf(chName) }.getOrDefault(SpeakerChannel.STEREO)
                val offset = prefs.getFloat("spk_${i}_offset", 0f).coerceAtMost(0f)
                speakers.add(SpeakerItemConfig(name, host, port, channel, offset))
            } else {
                when (i) {
                    0 -> {
                        val s1Name = prefs.getString(KEY_S1_NAME, "设备 1") ?: "设备 1"
                        val s1Host = prefs.getString(KEY_S1_HOST, "") ?: ""
                        val s1Port = prefs.getInt(KEY_S1_PORT, 7000)
                        speakers.add(SpeakerItemConfig(s1Name, s1Host, s1Port, SpeakerChannel.STEREO, 0f))
                    }
                    1 -> {
                        val s2Name = prefs.getString(KEY_S2_NAME, "设备 2") ?: "设备 2"
                        val s2Host = prefs.getString(KEY_S2_HOST, "") ?: ""
                        val s2Port = prefs.getInt(KEY_S2_PORT, 7000)
                        val off = prefs.getFloat(KEY_OFFSET_VALUE, -10f).coerceAtMost(0f)
                        speakers.add(SpeakerItemConfig(s2Name, s2Host, s2Port, SpeakerChannel.STEREO, off))
                    }
                    else -> {
                        speakers.add(SpeakerItemConfig("设备 ${i + 1}", "", 7000, SpeakerChannel.STEREO, 0f))
                    }
                }
            }
        }

        val dualConfig = DualConfig(
            deviceCount = deviceCount,
            speakers = speakers,
            offsetMode = offMode,
            defaultVolume = defVol,
            hardwareVolControl = hwVol,
            showPersistentNotification = showNotif,
            autoReconnectEnabled = autoReconnect,
            autoReconnectMinutes = autoReconnectMin
        )
        _state.update {
            it.copy(
                latencyMode = mode,
                robustMode = robust,
                alacOn = alacOn,
                volume = volume,
                dualConfig = dualConfig,
                lastDurationSeconds = lastDuration
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
        _senders.forEach { it?.latencyMode = mode }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_LATENCY, mode.name)
            ?.apply()
        addLog("延迟模式: ${mode.name}")
    }

    fun setRobustMode(on: Boolean) {
        _state.update { it.copy(robustMode = on) }
        _senders.forEach { it?.robustMode = on }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_ROBUST, on)
            ?.apply()
        addLog(if (on) "已开启网络稳健模式（冗余发包）" else "已关闭网络稳健模式")
    }

    fun setAlac(on: Boolean) {
        _state.update { it.copy(alacOn = on) }
        _senders.forEach { it?.alacOn = on }
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

    fun setStreamingMulti(activeSenders: List<RaopSender>) {
        for (i in 0 until 4) {
            _senders[i] = activeSenders.getOrNull(i)
        }
        _state.update { it.copy(connecting = false, streaming = true, status = "正在投送") }
    }

    fun setStreamingDual(s1: RaopSender?, s2: RaopSender?) {
        _senders[0] = s1
        _senders[1] = s2
        _senders[2] = null
        _senders[3] = null
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
        for (i in 0 until 4) {
            _senders[i] = null
        }
        val curDuration = _state.value.currentDurationSeconds
        val lastDur = if (curDuration > 0L) curDuration else _state.value.lastDurationSeconds
        if (curDuration > 0L) {
            contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putLong(KEY_LAST_DURATION, curDuration)
                ?.apply()
        }
        _state.update {
            it.copy(
                connecting = false,
                streaming = false,
                status = reason ?: "未投送",
                logs = it.logs + (reason?.let { r -> "已断开: $r" } ?: "已断开"),
                currentDurationSeconds = 0L,
                lastDurationSeconds = lastDur
            )
        }
    }

    fun updateCurrentDuration(seconds: Long) {
        _state.update { it.copy(currentDurationSeconds = seconds) }
    }

    fun recordSessionFinished(durationSeconds: Long) {
        val lastSec = if (durationSeconds > 0L) durationSeconds else _state.value.lastDurationSeconds
        _state.update { it.copy(currentDurationSeconds = 0L, lastDurationSeconds = lastSec) }
        if (durationSeconds > 0L) {
            contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putLong(KEY_LAST_DURATION, durationSeconds)
                ?.apply()
        }
    }

    fun triggerAccessDeniedAlert() {
        _state.update { it.copy(accessDeniedAlert = true) }
    }

    fun dismissAccessDeniedAlert() {
        _state.update { it.copy(accessDeniedAlert = false) }
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
        val isAccessDenied = line.contains("被设备的访问控制拒绝")
        _state.update {
            it.copy(
                logs = (it.logs + line).takeLast(300),
                accessDeniedAlert = if (isAccessDenied) true else it.accessDeniedAlert
            )
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun updateDualConfig(config: DualConfig) {
        _state.update { it.copy(dualConfig = config) }
        val editor = contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit() ?: return
        editor.putInt(KEY_DEVICE_COUNT, config.deviceCount)
        editor.putString(KEY_OFFSET_MODE, config.offsetMode.name)
        editor.putFloat(KEY_DEF_VOLUME, config.defaultVolume)
        editor.putBoolean(KEY_HARDWARE_VOL, config.hardwareVolControl)
        editor.putBoolean(KEY_PERSISTENT_NOTIF, config.showPersistentNotification)
        editor.putBoolean(KEY_AUTO_RECONNECT_ENABLED, config.autoReconnectEnabled)
        editor.putInt(KEY_AUTO_RECONNECT_MINUTES, config.autoReconnectMinutes)

        for (i in 0 until 4) {
            val spk = config.speakers.getOrElse(i) { SpeakerItemConfig("设备 ${i + 1}") }
            editor.putString("spk_${i}_name", spk.name)
            editor.putString("spk_${i}_host", spk.host)
            editor.putInt("spk_${i}_port", spk.port)
            editor.putString("spk_${i}_channel", spk.channel.name)
            editor.putFloat("spk_${i}_offset", spk.volumeOffset.coerceAtMost(0f))
        }

        // 兼容旧键名存储
        editor.putString(KEY_S1_NAME, config.speaker1Name)
        editor.putString(KEY_S1_HOST, config.speaker1Host)
        editor.putInt(KEY_S1_PORT, config.speaker1Port)
        editor.putString(KEY_S2_NAME, config.speaker2Name)
        editor.putString(KEY_S2_HOST, config.speaker2Host)
        editor.putInt(KEY_S2_PORT, config.speaker2Port)

        editor.apply()

        val spkSummary = config.speakers.take(config.deviceCount)
            .joinToString(" & ") { "${it.name}(${it.host}, ${it.channel.name}, ${it.volumeOffset.roundToInt()}%)" }
        addLog("已更新配置 (${config.deviceCount}台设备): $spkSummary · 联动模式: ${config.offsetMode.name}")
    }

    fun setVolume(pct: Float) {
        val v = pct.coerceIn(0f, 100f)
        _state.update { it.copy(volume = v) }
        contextForPrefs?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putFloat(KEY_VOLUME, v)
            ?.apply()
        val cfg = _state.value.dualConfig
        val count = cfg.deviceCount.coerceIn(1, 4)
        for (i in 0 until count) {
            val s = _senders[i]
            if (s != null) {
                val devVol = cfg.calculateVolume(i, v)
                s.setVolume(devVol.toDouble())
            }
        }
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
