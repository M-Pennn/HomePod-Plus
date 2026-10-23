package com.homepodplus.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.homepodplus.app.airplay.AirplayDevice
import com.homepodplus.app.airplay.RaopSender
import com.homepodplus.app.audio.AudioCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class CastService : Service() {

    companion object {
        const val ACTION_START = "com.homepodplus.app.action.START"
        const val ACTION_STOP = "com.homepodplus.app.action.STOP"
        const val ACTION_TEST_TONE = "com.homepodplus.app.action.TEST_TONE"
        const val ACTION_VOL_UP = "com.homepodplus.app.action.VOL_UP"
        const val ACTION_VOL_DOWN = "com.homepodplus.app.action.VOL_DOWN"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        const val EXTRA_IS_DUAL = "isDual"
        private const val CHANNEL_ID = "cast"
        private const val NOTIF_ID = 1
    }

    private var projection: MediaProjection? = null
    private var capture: AudioCapture? = null
    private val activeSenders = ArrayList<RaopSender>()
    private var startedStates = BooleanArray(0)
    private var savedTargetDevices: List<AirplayDevice> = emptyList()
    @Volatile private var streamingSeconds: Long = 0L
    val sender1: RaopSender? get() = activeSenders.getOrNull(0)
    val sender2: RaopSender? get() = activeSenders.getOrNull(1)
    private var stopping = false
    private var sessionGeneration = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var testToneOn = false
    private var testToneThread: Thread? = null

    // 手机外放自动静音与物理音量键监听
    @Volatile private var savedPhoneVolume = -1
    private var audioMgr: AudioManager? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var volAnchor = 0     // 0=绝对静音，1=按键操作窗口内
    @Volatile private var volLast = 0       // 上次检测到的系统音量级数
    @Volatile private var volActiveAt = 0L  // 最后一次物理按键活动时间戳

    // 左右声道分离缓冲区（复用 ShortArray，消除音频高频推流 GC 开销）
    private var splitLeftBuf = ShortArray(0)
    private var splitRightBuf = ShortArray(0)

    // 连接就绪与首包推流状态（确保 Live Update 仅在推流完全就绪后弹出）
    @Volatile private var hasCapturedPcm = false
    @Volatile private var hasPushedFirstFrame = false

    // 观察 CastController 状态刷新通知
    private var serviceScope: CoroutineScope? = null

    private val volPoller = object : Runnable {
        override fun run() {
            if (!stopping && activeSenders.isNotEmpty()) {
                val am = audioMgr
                if (am != null) {
                    try {
                        val hwEnabled = CastController.state.value.dualConfig.hardwareVolControl
                        val idx = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        if (hwEnabled) {
                            if (idx != volLast) {
                                handleSystemVolume(am, idx)
                            }
                            // 超过 2 秒未按物理音量键，将手机外放拉回绝对静音 (0)
                            if (volAnchor == 1 && SystemClock.uptimeMillis() - volActiveAt > 2000L) {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                                volAnchor = 0
                                volLast = 0
                            }
                        } else {
                            // 关闭物理键联动时：不联动 HomePod 音量，同时若误触侧边键拉回静音 0
                            if (idx != 0) {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                            }
                            volAnchor = 0
                            volLast = 0
                        }
                    } catch (_: Exception) {}
                }
                uiHandler.postDelayed(this, 100)
            }
        }
    }

    private fun handleSystemVolume(am: AudioManager, index: Int) {
        val dir = if (index > volLast) 1 else -1
        volLast = index
        if (dir > 0 && volAnchor == 0) volAnchor = 1
        volActiveAt = SystemClock.uptimeMillis()
        val curVol = CastController.state.value.volume
        val newVol = (curVol + dir * 10f).coerceIn(0f, 100f)
        CastController.setVolume(newVol)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volAnchor, 0)
        } catch (_: Exception) {}
        volLast = volAnchor
    }

    private fun muteSpeaker() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        audioMgr = am
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (savedPhoneVolume < 0) savedPhoneVolume = cur
        if (cur != 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } catch (_: Exception) {}
        }
        volAnchor = 0
        volLast = 0
        uiHandler.removeCallbacks(volPoller)
        uiHandler.postDelayed(volPoller, 100)
    }

    private fun unmuteSpeaker() {
        val saved = savedPhoneVolume
        if (saved < 0) return
        savedPhoneVolume = -1
        uiHandler.removeCallbacks(volPoller)
        val am = audioMgr ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
        try {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur <= 1 && cur != saved) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
            }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCasting()
                stopSelf()
            }
            ACTION_VOL_UP -> {
                val cur = CastController.state.value.volume
                CastController.setVolume((cur + 10f).coerceIn(0f, 100f))
            }
            ACTION_VOL_DOWN -> {
                val cur = CastController.state.value.volume
                CastController.setVolume((cur - 10f).coerceIn(0f, 100f))
            }
            ACTION_TEST_TONE -> toggleTestTone()
            ACTION_START -> startCasting(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun startCasting(intent: Intent) {
        if (stopping) return
        stopping = false
        hasCapturedPcm = false
        hasPushedFirstFrame = false
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        } ?: run {
            stopSelf()
            return
        }

        val isDual = intent.getBooleanExtra(EXTRA_IS_DUAL, true)
        val config = CastController.state.value.dualConfig

        val targetDevices: List<AirplayDevice>

        if (isDual && config.isConfigured) {
            val count = config.deviceCount.coerceIn(1, 4)
            targetDevices = config.speakers.take(count).mapIndexed { idx, spk ->
                AirplayDevice(
                    name = spk.name.ifBlank { "设备 ${idx + 1}" },
                    host = spk.host,
                    port = spk.port,
                    type = "_airplay._tcp.local"
                )
            }
        } else {
            val host = intent.getStringExtra(EXTRA_HOST) ?: run {
                stopSelf()
                return
            }
            val port = intent.getIntExtra(EXTRA_PORT, 7000)
            val name = intent.getStringExtra(EXTRA_NAME) ?: host
            targetDevices = listOf(
                AirplayDevice(name = name, host = host, port = port, type = "_airplay._tcp.local")
            )
        }

        savedTargetDevices = targetDevices
        streamingSeconds = 0L
        CastController.updateCurrentDuration(0L)

        startForegroundCompat()
        CastController.setConnecting(targetDevices.first())

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data) ?: run {
            CastController.addLog("无法创建屏幕捕获会话")
            stopSelf()
            return
        }
        projection = proj
        proj.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    CastController.addLog("屏幕捕获授权被系统撤销")
                    stopCasting()
                    stopSelf()
                }
            },
            Handler(Looper.getMainLooper())
        )

        // 投送启动：自动静音手机外放，开启物理音量键联动
        muteSpeaker()

        // 启动反应式状态监听，当音量或配置变化时即时刷新通知；运行推流时长计数与定时重连
        serviceScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        serviceScope = scope
        scope.launch {
            CastController.state.collect { st ->
                if (st.streaming && !stopping) {
                    updateLiveNotification()
                }
            }
        }
        scope.launch {
            while (!stopping) {
                kotlinx.coroutines.delay(1000L)
                if (!stopping && startedStates.isNotEmpty() && startedStates.all { it }) {
                    streamingSeconds++
                    CastController.updateCurrentDuration(streamingSeconds)
                    val cfg = CastController.state.value.dualConfig
                    if (cfg.autoReconnectEnabled && cfg.autoReconnectMinutes > 0) {
                        val limitSec = cfg.autoReconnectMinutes * 60L
                        if (streamingSeconds >= limitSec) {
                            reconnectSenders("已持续播放 ${cfg.autoReconnectMinutes} 分钟，达到定时重连阈值")
                        }
                    }
                }
            }
        }

        val gen = ++sessionGeneration
        startedStates = BooleanArray(targetDevices.size) { false }
        activeSenders.clear()

        for (i in targetDevices.indices) {
            val snd = createSender(targetDevices[i], i, gen)
            activeSenders.add(snd)
        }
        CastController.setStreamingMulti(activeSenders)

        capture = AudioCapture(
            proj,
            { samples, rate ->
                hasCapturedPcm = true
                val allConnected = startedStates.isNotEmpty() && startedStates.all { it }
                if (allConnected && !hasPushedFirstFrame) {
                    hasPushedFirstFrame = true
                    uiHandler.post { updateLiveNotification() }
                }
                if (!testToneOn) {
                    val count = activeSenders.size
                    if (count == 1) {
                        activeSenders.getOrNull(0)?.pushPcm(samples, rate)
                    } else {
                        val activeSpeakers = config.speakers.take(count)
                        val needsLeft = activeSpeakers.any { it.channel == SpeakerChannel.LEFT }
                        val needsRight = activeSpeakers.any { it.channel == SpeakerChannel.RIGHT }
                        val len = samples.size
                        if ((needsLeft || needsRight) && splitLeftBuf.size != len) {
                            splitLeftBuf = ShortArray(len)
                            splitRightBuf = ShortArray(len)
                        }
                        if (needsLeft || needsRight) {
                            var i = 0
                            while (i < len - 1) {
                                val l = samples[i]
                                val r = samples[i + 1]
                                if (needsLeft) {
                                    splitLeftBuf[i] = l
                                    splitLeftBuf[i + 1] = l
                                }
                                if (needsRight) {
                                    splitRightBuf[i] = r
                                    splitRightBuf[i + 1] = r
                                }
                                i += 2
                            }
                        }
                        for (idx in 0 until minOf(count, activeSenders.size)) {
                            val snd = activeSenders[idx]
                            val ch = activeSpeakers[idx].channel
                            when (ch) {
                                SpeakerChannel.STEREO -> snd.pushPcm(samples, rate)
                                SpeakerChannel.LEFT -> snd.pushPcm(splitLeftBuf, rate)
                                SpeakerChannel.RIGHT -> snd.pushPcm(splitRightBuf, rate)
                            }
                        }
                    }
                }
            },
            { msg -> CastController.addLog(msg) },
            { msg -> CastController.addLog(msg) }
        )
        capture!!.onRate = { rate ->
            for (snd in activeSenders) {
                snd.onCaptureRate(rate)
            }
        }

        if (!capture!!.start()) {
            CastController.addLog("无法捕获系统音频（请检查是否允许投射屏幕/音频）")
            cleanup()
            stopSelf()
            return
        }

        acquireNetworkLocks()
        for (snd in activeSenders) {
            snd.start()
        }
    }

    private fun createSender(device: AirplayDevice, index: Int, gen: Int): RaopSender {
        return RaopSender(
            device,
            object : RaopSender.Listener {
                override fun onState(state: RaopSender.State) {
                    if (gen != sessionGeneration) return
                    when (state) {
                        RaopSender.State.PAIRING -> CastController.setStatus("正在配对 [${device.name}]...")
                        RaopSender.State.HANDSHAKE -> CastController.setStatus("正在建立会话 [${device.name}]...")
                        else -> Unit
                    }
                }

                override fun onLog(message: String) = CastController.addLog("[${device.name}] $message")

                override fun onStarted() {
                    if (gen != sessionGeneration) return
                    if (index < startedStates.size) {
                        startedStates[index] = true
                    }
                    val allConnected = startedStates.isNotEmpty() && startedStates.all { it }
                    if (allConnected) {
                        CastController.setStreamingMulti(activeSenders)
                    }
                    val masterVol = CastController.state.value.volume
                    val config = CastController.state.value.dualConfig
                    val thisVol = config.calculateVolume(index, masterVol)
                    if (index < activeSenders.size) {
                        activeSenders[index].setVolume(thisVol.toDouble())
                    }
                    if (allConnected && hasCapturedPcm && !hasPushedFirstFrame) {
                        hasPushedFirstFrame = true
                        uiHandler.post { updateLiveNotification() }
                    }
                }

                override fun onStopped(reason: String?) {
                    if (gen != sessionGeneration) return
                    CastController.addLog("[${device.name}] 已停止: $reason")
                    if (reason?.contains("被设备的访问控制拒绝") == true) {
                        CastController.triggerAccessDeniedAlert()
                    }
                    if (index < startedStates.size) {
                        startedStates[index] = false
                    }
                    if (index == 0 || startedStates.none { it }) {
                        val dur = streamingSeconds
                        streamingSeconds = 0L
                        CastController.recordSessionFinished(dur)
                        CastController.resetConnection(reason)
                        cleanup()
                        stopSelf()
                    } else {
                        uiHandler.post { updateLiveNotification() }
                    }
                }
            }
        ).apply {
            latencyMode = CastController.state.value.latencyMode
            robustMode = CastController.state.value.robustMode
            alacOn = CastController.state.value.alacOn
        }
    }

    private fun reconnectSenders(reason: String) {
        if (stopping || savedTargetDevices.isEmpty()) return
        CastController.addLog("触发定时自动重连 ($reason)，正在快速重建音频会话...")
        val finishedDuration = streamingSeconds
        streamingSeconds = 0L
        CastController.recordSessionFinished(finishedDuration)

        val gen = ++sessionGeneration
        val oldSenders = ArrayList(activeSenders)
        activeSenders.clear()
        startedStates = BooleanArray(savedTargetDevices.size) { false }

        for (snd in oldSenders) {
            try {
                snd.stop()
            } catch (_: Exception) {}
        }

        for (i in savedTargetDevices.indices) {
            val snd = createSender(savedTargetDevices[i], i, gen)
            activeSenders.add(snd)
        }
        CastController.setStreamingMulti(activeSenders)

        for (snd in activeSenders) {
            snd.start()
        }
    }

    private fun stopCasting() {
        if (stopping) return
        stopping = true
        CastController.addLog("正在停止投射...")
        val dur = streamingSeconds
        streamingSeconds = 0L
        CastController.recordSessionFinished(dur)
        stopTestTone()
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun pauseMediaPlayback() {
        try {
            val am = audioMgr ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
            val now = SystemClock.uptimeMillis()
            val down = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            val up = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)

            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .build()
            am.requestAudioFocus(req)
            uiHandler.postDelayed({
                try {
                    am.abandonAudioFocusRequest(req)
                } catch (_: Exception) {}
            }, 300)
        } catch (_: Exception) {}
    }

    private fun cleanup() {
        pauseMediaPlayback()
        val dur = streamingSeconds
        streamingSeconds = 0L
        if (dur > 0L) {
            CastController.recordSessionFinished(dur)
        }
        startedStates = BooleanArray(0)
        hasCapturedPcm = false
        hasPushedFirstFrame = false
        serviceScope?.cancel()
        serviceScope = null
        unmuteSpeaker()
        stopTestTone()
        for (snd in activeSenders) {
            snd.stop()
        }
        activeSenders.clear()
        CastController.resetConnection(null)
        capture?.stop()
        releaseNetworkLocks()
        capture = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
    }

    private fun acquireNetworkLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomePodPlus:cast").apply {
                setReferenceCounted(false)
                acquire(6L * 60 * 60 * 1000)
            }
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            try {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "HomePodPlus:cast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
                // 部分机型不支持低延迟锁，退回高性能锁
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HomePodPlus:cast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseNetworkLocks() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun buildConnectingNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, CastService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airplay)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在建立连接...")
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "取消", stopPi)
            .build()
    }

    private fun isReadyForLiveNotification(): Boolean {
        if (stopping) return false
        val st = CastController.state.value
        if (!st.streaming) return false
        val connectionsEstablished = startedStates.isNotEmpty() && startedStates.all { it }
        return connectionsEstablished && hasPushedFirstFrame
    }

    private fun buildLiveNotification(volume: Int, config: DualConfig): Notification {
        val pct = volume.coerceIn(0, 100)

        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 若用户在设置中关闭了常驻通知，使用极其精炼的静默后台前台通知（满足系统对投屏服务的要求）
        if (!config.showPersistentNotification) {
            val stopIntent = Intent(this, CastService::class.java).setAction(ACTION_STOP)
            val stopPi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_airplay)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("后台投送运行中")
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, "停止", stopPi)
                .build()
        }

        val stopIntent = Intent(this, CastService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val downIntent = Intent(this, CastService::class.java).setAction(ACTION_VOL_DOWN)
        val downPi = PendingIntent.getService(
            this, 2, downIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val upIntent = Intent(this, CastService::class.java).setAction(ACTION_VOL_UP)
        val upPi = PendingIntent.getService(
            this, 3, upIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 遵循 Android 官方「实时更新通知 (Live Update / Promoted Ongoing)」规范：
        // 1. 不使用任何 RemoteViews 自定义布局，交由系统原生高保真渲染（消除文字发暗、按键重复与排版拥挤问题）
        // 2. 仅保留最核心的「当前音量」与「三个按键」，去除一切冗余说明文字
        // 3. 接入 setShortCriticalText 与 Promoted Ongoing 标志，在顶部状态栏以独立实时动态胶囊标签呈现
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airplay)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("音量 $pct%")
            .setProgress(100, pct, false)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "- 10%", downPi)
            .addAction(0, "+ 10%", upPi)
            .addAction(0, "停止", stopPi)

        // 状态栏条状标签 (Status Chip) 与升级实时动态标记
        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        builder.extras.putCharSequence("android.shortCriticalText", "$pct%")

        val notif = builder.build()
        try {
            notif.flags = notif.flags or Notification.FLAG_PROMOTED_ONGOING
        } catch (_: Exception) {}
        return notif
    }

    private fun updateLiveNotification() {
        if (stopping) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notif = if (isReadyForLiveNotification()) {
                buildLiveNotification(
                    CastController.state.value.volume.roundToInt(),
                    CastController.state.value.dualConfig
                )
            } else {
                buildConnectingNotification()
            }
            nm.notify(NOTIF_ID, notif)
        } catch (_: Exception) {}
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val notif = buildConnectingNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun toggleTestTone() {
        if (activeSenders.isEmpty()) {
            CastController.addLog("当前没有正在投射的会话")
            return
        }
        if (testToneOn) {
            stopTestTone()
            CastController.setTestTone(false)
        } else {
            testToneOn = true
            CastController.setTestTone(true)
            testToneThread = Thread({ testToneLoop() }, "test-tone").also { it.start() }
        }
    }

    private fun stopTestTone() {
        testToneOn = false
        testToneThread?.interrupt()
        testToneThread = null
    }

    private fun testToneLoop() {
        val rate = 44100
        val freq = 440.0
        val buf = ShortArray(2048)
        val sleepMs = (buf.size / 2) * 1000L / rate
        var phase = 0.0
        while (testToneOn) {
            var i = 0
            while (i < buf.size) {
                val v = (Math.sin(phase) * 8000.0).toInt().toShort()
                buf[i] = v
                buf[i + 1] = v
                phase += 2.0 * Math.PI * freq / rate
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                i += 2
            }
            for (snd in activeSenders) {
                snd.pushPcm(buf, rate)
            }
            try {
                Thread.sleep(sleepMs)
            } catch (e: InterruptedException) {
                break
            }
        }
    }
}
