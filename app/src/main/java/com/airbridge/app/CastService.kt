package com.airbridge.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.airbridge.app.airplay.AirplayDevice
import com.airbridge.app.airplay.RaopSender
import com.airbridge.app.audio.AudioCapture

class CastService : Service() {

    companion object {
        const val ACTION_START = "com.airbridge.app.action.START"
        const val ACTION_STOP = "com.airbridge.app.action.STOP"
        const val ACTION_TEST_TONE = "com.airbridge.app.action.TEST_TONE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        private const val CHANNEL_ID = "cast"
        private const val NOTIF_ID = 1
    }

    private var projection: MediaProjection? = null
    private var capture: AudioCapture? = null
    private var sender: RaopSender? = null
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var testToneOn = false
    private var testToneThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCasting()
                stopSelf()
            }
            ACTION_TEST_TONE -> toggleTestTone()
            ACTION_START -> startCasting(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCasting(intent: Intent) {
        if (stopping) return
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
        val host = intent.getStringExtra(EXTRA_HOST) ?: run {
            stopSelf()
            return
        }
        val port = intent.getIntExtra(EXTRA_PORT, 7000)
        val name = intent.getStringExtra(EXTRA_NAME) ?: host
        val device = AirplayDevice(name = name, host = host, port = port, type = "_airplay._tcp.local")

        startForegroundCompat()
        CastController.setConnecting(device)

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

        val s = RaopSender(
            device,
            object : RaopSender.Listener {
                override fun onState(state: RaopSender.State) = Unit

                override fun onLog(message: String) = CastController.addLog(message)

                override fun onStarted() {
                    val snd = this@CastService.sender
                    if (snd != null) CastController.setStreaming(snd)
                }

                override fun onStopped(reason: String?) {
                    CastController.resetConnection(reason)
                    cleanup()
                    stopSelf()
                }
            }
        )
        sender = s
        s.latencyMode = CastController.state.value.latencyMode
        s.robustMode = CastController.state.value.robustMode
        CastController.sender = s
        capture = AudioCapture(
            proj,
            { samples, rate -> s.pushPcm(samples, rate) },
            { msg -> CastController.addLog(msg) }
        )
        if (!capture!!.start()) {
            CastController.addLog("无法捕获系统音频（请检查是否允许投射屏幕/音频）")
            cleanup()
            stopSelf()
            return
        }
        acquireNetworkLocks()
        s.start()
    }

    private fun stopCasting() {
        if (stopping) return
        stopping = true
        CastController.addLog("正在停止投射...")
        stopTestTone()
        sender?.stop()
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun cleanup() {
        stopTestTone()
        capture?.stop()
        releaseNetworkLocks()
        capture = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        sender = null
    }

    private fun acquireNetworkLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirBridge:cast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= 29) {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AirBridge:cast")
            } else {
                @Suppress("DEPRECATION")
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirBridge:cast")
            }.apply {
                setReferenceCounted(false)
                acquire()
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

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val stopIntent = Intent(this, CastService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("点击停止可结束投射")
            .setOngoing(true)
            .addAction(0, "停止", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun toggleTestTone() {
        if (sender == null) {
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
            sender?.pushPcm(buf, rate)
            try {
                Thread.sleep(20)
            } catch (e: InterruptedException) {
                break
            }
        }
    }
}
