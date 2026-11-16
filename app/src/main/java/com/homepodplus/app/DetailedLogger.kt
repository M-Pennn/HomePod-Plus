package com.homepodplus.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 详细诊断日志信息项
 */
data class LogSessionItem(
    val id: String,
    val file: File,
    val startTimeMs: Long,
    val durationSeconds: Int,
    val sizeBytes: Long
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(startTimeMs))
        }

    val formattedDuration: String
        get() {
            val m = durationSeconds / 60
            val s = durationSeconds % 60
            return if (m > 0) "${m}分${s}秒" else "${s}秒"
        }

    val formattedSize: String
        get() {
            return when {
                sizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
                sizeBytes >= 1024 -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
                else -> "$sizeBytes B"
            }
        }
}

/**
 * 详细诊断日志状态
 */
data class DetailedLogState(
    val isRecording: Boolean = false,
    val remainingSeconds: Int = DetailedLogger.MAX_RECORDING_SECONDS,
    val currentDurationSeconds: Int = 0,
    val currentSessionId: String? = null,
    val savedSessions: List<LogSessionItem> = emptyList()
)

/**
 * 详细诊断日志管理器
 * - 收集所有涉及到的日志（音频传输、RTP时钟、ALAC、握手协商、网络及系统底层诊断）
 * - 支持随时手动结束，最长开启 10 分钟（自动停止并保存）
 * - 严格保留最近 3 次的日志
 * - 支持一键打包导出最近三次的日志为 ZIP 压缩包
 */
@SuppressLint("StaticFieldLeak")
object DetailedLogger {

    const val MAX_RECORDING_SECONDS = 10 * 60 // 最长 10 分钟 (600 秒)
    const val MAX_SAVED_SESSIONS = 3          // 严格保留最近 3 次

    private val _state = MutableStateFlow(DetailedLogState())
    val state: StateFlow<DetailedLogState> = _state

    val isRecording: Boolean
        get() = _state.value.isRecording

    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var timerJob: Job? = null

    private var activeFileWriter: PrintWriter? = null
    private var activeSessionFile: File? = null
    private var activeSessionStartTimeMs: Long = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 初始化管理器，扫描并加载已有的最近历史日志
     */
    fun init(context: Context) {
        fileExecutor.execute {
            cleanupAndRefreshSessions(context.applicationContext)
        }
    }

    /**
     * 开启详细日志记录
     */
    fun startRecording(context: Context) {
        val appContext = context.applicationContext
        if (_state.value.isRecording) return

        val nowMs = System.currentTimeMillis()
        val sessionId = "session_${fileDateFormat.format(Date(nowMs))}"
        val logDir = getLogDir(appContext)
        val sessionFile = File(logDir, "$sessionId.log")

        activeSessionFile = sessionFile
        activeSessionStartTimeMs = nowMs

        fileExecutor.execute {
            try {
                val pw = PrintWriter(FileWriter(sessionFile, true), true)
                activeFileWriter = pw

                // 写入详细环境诊断头
                val header = buildDiagnosticHeader(appContext, nowMs)
                pw.println(header)
                pw.flush()
            } catch (e: Exception) {
                android.util.Log.e("DetailedLogger", "Failed to start log writer", e)
            }
        }

        _state.update {
            it.copy(
                isRecording = true,
                remainingSeconds = MAX_RECORDING_SECONDS,
                currentDurationSeconds = 0,
                currentSessionId = sessionId
            )
        }

        // 启动 10 分钟倒计时调度器
        timerJob?.cancel()
        timerJob = mainScope.launch {
            while (isActive && _state.value.isRecording) {
                delay(1000L)
                val remaining = _state.value.remainingSeconds - 1
                val duration = _state.value.currentDurationSeconds + 1
                if (remaining <= 0) {
                    stopRecording(appContext, "已达到最长记录上限（10分钟），自动结束并保存")
                    break
                } else {
                    _state.update {
                        it.copy(
                            remainingSeconds = remaining,
                            currentDurationSeconds = duration
                        )
                    }
                }
            }
        }

        CastController.addLog("已开启详细日志记录（最长 10 分钟，支持随时手动结束）")
    }

    /**
     * 结束详细日志记录（支持手动结束或超时自动结束）
     */
    fun stopRecording(context: Context, reason: String = "手动结束") {
        val appContext = context.applicationContext
        if (!_state.value.isRecording) return

        timerJob?.cancel()
        timerJob = null

        val duration = _state.value.currentDurationSeconds
        val nowMs = System.currentTimeMillis()

        fileExecutor.execute {
            try {
                val pw = activeFileWriter
                if (pw != null) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    pw.println()
                    pw.println("================================================================================")
                    pw.println("--- 会话记录结束 (SESSION END) ---")
                    pw.println("结束时间 (End Time)     : ${sdf.format(Date(nowMs))}")
                    pw.println("结束原因 (Reason)       : $reason")
                    pw.println("记录时长 (Duration)     : ${duration} 秒")
                    pw.println("================================================================================")
                    pw.println()

                    // 抓取并写入当前进程及系统的最新 Logcat
                    pw.println("================================================================================")
                    pw.println("--- 系统 LOGCAT 诊断日志 (RECENT PROCESS LOGCAT) ---")
                    pw.println("================================================================================")
                    val logcat = captureLogcat()
                    pw.println(logcat)
                    pw.println("================================================================================")
                    pw.println("--- 会话完整写入完毕 (EOF) ---")
                    pw.println("================================================================================")
                    pw.flush()
                    pw.close()
                }
            } catch (e: Exception) {
                android.util.Log.e("DetailedLogger", "Failed to finalize log file", e)
            } finally {
                activeFileWriter = null
                activeSessionFile = null
                cleanupAndRefreshSessions(appContext)
            }
        }

        _state.update {
            it.copy(
                isRecording = false,
                remainingSeconds = MAX_RECORDING_SECONDS,
                currentDurationSeconds = 0,
                currentSessionId = null
            )
        }

        CastController.addLog("详细日志已结束: $reason (已更新最近 3 次存档)")
    }

    /**
     * 记录单条详细事件日志（非记录状态直接返回，零运行时性能损耗）
     */
    fun log(tag: String, message: String) {
        if (!isRecording) return
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val threadName = Thread.currentThread().name
        fileExecutor.execute {
            try {
                activeFileWriter?.println("[$timestamp] [$threadName] [$tag] $message")
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 清理并保留最近 3 次日志，同时刷新状态
     */
    private fun cleanupAndRefreshSessions(context: Context) {
        try {
            val dir = getLogDir(context)
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: emptyArray()

            // 按修改时间降序排序（最新在前）
            val sorted = files.sortedByDescending { it.lastModified() }

            // 严格只保留最近 3 次
            if (sorted.size > MAX_SAVED_SESSIONS) {
                for (i in MAX_SAVED_SESSIONS until sorted.size) {
                    sorted[i].delete()
                }
            }

            val validFiles = sorted.take(MAX_SAVED_SESSIONS)
            val sessionList = validFiles.map { f ->
                // 读取持续时间
                val durationSec = estimateSessionDuration(f)
                LogSessionItem(
                    id = f.nameWithoutExtension,
                    file = f,
                    startTimeMs = f.lastModified(),
                    durationSeconds = durationSec,
                    sizeBytes = f.length()
                )
            }

            _state.update { it.copy(savedSessions = sessionList) }
        } catch (e: Exception) {
            android.util.Log.e("DetailedLogger", "Failed to cleanup/refresh sessions", e)
        }
    }

    /**
     * 从文件内容或元信息中估算记录时长
     */
    private fun estimateSessionDuration(file: File): Int {
        return try {
            val lines = file.readLines()
            for (line in lines.takeLast(30)) {
                if (line.startsWith("记录时长 (Duration)")) {
                    val parts = line.split(":")
                    if (parts.size >= 2) {
                        val num = parts[1].replace("秒", "").trim().toIntOrNull()
                        if (num != null) return num
                    }
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 打包导出最近三次的日志为 ZIP 压缩包
     */
    fun exportZip(context: Context): File? {
        val sessions = _state.value.savedSessions
        if (sessions.isEmpty()) return null

        return try {
            val exportDir = File(context.cacheDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            // 清理旧的导出文件
            exportDir.listFiles()?.forEach { if (it.name.endsWith(".zip")) it.delete() }

            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val zipName = "HomePodPlus_Logs_${sdf.format(Date())}.zip"
            val zipFile = File(exportDir, zipName)

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // 1. 添加汇总说明文件
                val summaryEntry = ZipEntry("EXPORT_SUMMARY.txt")
                zos.putNextEntry(summaryEntry)
                val summaryContent = buildExportSummary(context, sessions)
                zos.write(summaryContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 2. 添加所有保存的会话日志文件（最多 3 个）
                for (session in sessions) {
                    if (session.file.exists() && session.file.length() > 0) {
                        val entry = ZipEntry("sessions/${session.file.name}")
                        zos.putNextEntry(entry)
                        session.file.inputStream().use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
            }
            zipFile
        } catch (e: Exception) {
            android.util.Log.e("DetailedLogger", "Failed to export zip", e)
            null
        }
    }

    /**
     * 调起系统分享面板导出 ZIP 日志包
     */
    fun shareZip(context: Context) {
        val zipFile = exportZip(context)
        if (zipFile == null || !zipFile.exists() || zipFile.length() == 0L) {
            Toast.makeText(context, "暂无保存的详细日志可供导出", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "HomePod+ 详细诊断日志 (${zipFile.name})")
                putExtra(Intent.EXTRA_TEXT, "这是 HomePod+ 应用导出的最近详细诊断日志包，包含系统环境、AirPlay时钟、RTP及底层日志。")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "导出详细诊断日志 (ZIP)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("DetailedLogger", "Failed to share zip", e)
            Toast.makeText(context, "调起导出分享失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 构建详细诊断头部信息
     */
    private fun buildDiagnosticHeader(context: Context, startTimeMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val uiState = CastController.state.value
        val config = uiState.dualConfig

        val sb = StringBuilder()
        sb.appendLine("================================================================================")
        sb.appendLine("HomePod + 详细诊断日志 (Detailed Diagnostic Log Session)")
        sb.appendLine("================================================================================")
        sb.appendLine("应用版本 (App Version)      : v2.2.0 (versionCode 220)")
        sb.appendLine("记录开始时间 (Start Time)   : ${sdf.format(Date(startTimeMs))}")
        sb.appendLine("设备品牌 (Brand/Manuf.)     : ${Build.MANUFACTURER} / ${Build.BRAND}")
        sb.appendLine("设备型号 (Model/Device)     : ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})")
        sb.appendLine("系统版本 (OS Version)       : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("构建版本 (Build Display)    : ${Build.DISPLAY}")
        sb.appendLine()
        sb.appendLine("[当前投送与系统配置]")
        sb.appendLine("投送状态 (Streaming State)  : ${uiState.status} (streaming=${uiState.streaming}, connecting=${uiState.connecting})")
        sb.appendLine("配置设备数 (Device Count)   : ${config.deviceCount} 台")
        sb.appendLine("音量联动模式 (Offset Mode)  : ${config.offsetMode.name}")
        sb.appendLine("当前音量 (Master Volume)    : ${uiState.volume}%")
        sb.appendLine("延迟模式 (Latency Mode)     : ${uiState.latencyMode.name}")
        sb.appendLine("网络稳健模式 (Robust Mode)  : ${uiState.robustMode}")
        sb.appendLine("ALAC 压缩编码 (ALAC Mode)   : ${uiState.alacOn}")
        sb.appendLine("物理按键控制 (Hardware Vol) : ${config.hardwareVolControl}")
        sb.appendLine("常驻控制通知 (Persist Notif): ${config.showPersistentNotification}")
        sb.appendLine("定时自动重连 (Auto Reconnect): ${config.autoReconnectEnabled} (${config.autoReconnectMinutes} 分钟)")
        sb.appendLine()
        sb.appendLine("[配置的音箱列表]")
        for (i in 0 until config.deviceCount.coerceIn(1, 4)) {
            val spk = config.speakers.getOrNull(i)
            if (spk != null) {
                sb.appendLine("  #${i + 1}: ${spk.name} -> ${spk.host}:${spk.port} [声道: ${spk.channel.name}] [偏移: ${spk.volumeOffset}%]")
            }
        }
        sb.appendLine()
        sb.appendLine("[网络接口及局域网状态]")
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                val ips = addrs.joinToString(", ") { it.hostAddress ?: "" }
                sb.appendLine("  接口 ${intf.name} (mtu=${intf.mtu}): $ips")
            }
        } catch (e: Exception) {
            sb.appendLine("  无法读取网络接口: ${e.message}")
        }
        sb.appendLine("================================================================================")
        sb.appendLine("--- 实时日志追踪 (REALTIME EVENT TRACE) ---")
        sb.appendLine("================================================================================")
        return sb.toString()
    }

    /**
     * 构建 ZIP 导出包汇总说明文本
     */
    private fun buildExportSummary(context: Context, sessions: List<LogSessionItem>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("================================================================================")
        sb.appendLine("HomePod + 详细诊断日志汇总包 (Export Summary)")
        sb.appendLine("================================================================================")
        sb.appendLine("导出时间 (Export Time)   : ${sdf.format(Date())}")
        sb.appendLine("应用版本 (App Version)   : v2.2.0 (versionCode 220)")
        sb.appendLine("设备型号 (Device Model)  : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("系统版本 (Android OS)    : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("包含会话数 (Session Qty) : ${sessions.size} / 3")
        sb.appendLine()
        sb.appendLine("会话清单:")
        sessions.forEachIndexed { index, s ->
            sb.appendLine("  [${index + 1}] 文件: sessions/${s.file.name}")
            sb.appendLine("      时间: ${s.formattedTime}")
            sb.appendLine("      时长: ${s.formattedDuration}")
            sb.appendLine("      大小: ${s.formattedSize} (${s.sizeBytes} bytes)")
        }
        sb.appendLine("================================================================================")
        sb.appendLine("说明：该 ZIP 文件包含近 3 次记录的详细音频推流、时钟同源对齐、系统 Logcat 及错误诊断信息，")
        sb.appendLine("可直接发送给开发者或 AI 助手进行精准故障排查与原因分析。")
        sb.appendLine("================================================================================")
        return sb.toString()
    }

    /**
     * 抓取当前进程及相关系统的 Logcat 输出（最多 1000 行）
     */
    private fun captureLogcat(maxLines: Int = 1000): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "*:V"))
            val lines = ArrayList<String>()
            val reader = process.inputStream.bufferedReader()
            reader.forEachLine { line ->
                lines.add(line)
                if (lines.size > maxLines * 2) {
                    lines.subList(0, maxLines).clear()
                }
            }
            process.waitFor(3, TimeUnit.SECONDS)
            if (lines.isEmpty()) {
                "(Logcat 缓冲区为空或无新条目)"
            } else {
                lines.takeLast(maxLines).joinToString("\n")
            }
        } catch (e: Exception) {
            "无法抓取 Logcat: ${e.message}"
        }
    }

    private fun getLogDir(context: Context): File {
        val dir = File(context.filesDir, "detailed_logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
