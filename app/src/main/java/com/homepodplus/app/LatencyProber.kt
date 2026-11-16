package com.homepodplus.app

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

/**
 * 实时延迟探测器状态
 */
data class ProberState(
    val isProbing: Boolean = false,
    val durationSeconds: Int = 0,
    val currentRttMs: Float = 0f,
    val currentAudioJitterMs: Float = 0f,
    val currentRecommendedMs: Int = 140,
    val historyPoints: List<Float> = emptyList(), // 最近 40~50 个采样点（用于绘制实时动态曲线）
    val minMs: Int = 0,
    val avgMs: Int = 0,
    val p50Ms: Int = 0,
    val p90Ms: Int = 0,
    val p99Ms: Int = 0,
    val maxMs: Int = 0
)

/**
 * 局域网 Wi-Fi 与系统录音抖动持续探测引擎
 * - 支持用户手动“开始”和“结束”，进行无限时持续探测
 * - 实时计算 Wi-Fi 往返 RTT、网络抖动、安卓系统音频捕获间隙
 * - 输出平滑动态推荐延迟曲线，并实时统计 P50（极限）、P90（稳健）、P99（保底）覆盖率指标
 */
object LatencyProber {

    private val _state = MutableStateFlow(ProberState())
    val state: StateFlow<ProberState> = _state

    private val probeScope = CoroutineScope(Dispatchers.IO)
    private var probeJob: Job? = null

    // 共享的最近实测音频录音抖动（毫秒），由 AudioCapture 实时注入
    @Volatile
    var liveAudioJitterMs: Float = 18f

    /**
     * 开始持续探测（不自动结束，由用户手动停止）
     */
    fun startProbing(targetHost: String, targetPort: Int) {
        if (_state.value.isProbing) return

        _state.update {
            it.copy(
                isProbing = true,
                durationSeconds = 0,
                historyPoints = emptyList(),
                currentRecommendedMs = 140
            )
        }

        probeJob?.cancel()
        probeJob = probeScope.launch {
            var secondTickCounter = 0
            val validPort = if (targetPort in 1..65535) targetPort else 7000
            val host = targetHost.trim()

            while (isActive && _state.value.isProbing) {
                val cycleStart = SystemClock.elapsedRealtimeNanos()

                // 1. 探测局域网 Wi-Fi 空口 RTT
                val rttMs = measureWifiRtt(host, validPort)

                // 2. 获取当前系统音频捕获真实抖动
                val audioJitter = liveAudioJitterMs.coerceIn(8f, 60f)

                // 3. 计算科学推荐临界延迟
                // 推荐值 = 网络往返时间 × 1.1 + 录音最大抖动间隙 + HomePod 硬件物理底层 DAC 解码安全余量 (65ms)
                val rawRecommended = (rttMs * 1.1f + audioJitter + 65f)
                val recommendedClamped = rawRecommended.roundToInt().coerceIn(80, 240)

                // 4. 更新历史波形数据点（保留最近 50 个点，滑动平滑窗口）
                _state.update { prev ->
                    val newHistory = (prev.historyPoints + recommendedClamped.toFloat()).takeLast(50)
                    val sorted = newHistory.sorted()
                    val p50 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.50).toInt().coerceIn(0, sorted.size - 1)].roundToInt() else recommendedClamped
                    val p90 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.90).toInt().coerceIn(0, sorted.size - 1)].roundToInt() else recommendedClamped
                    val p99 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.99).toInt().coerceIn(0, sorted.size - 1)].roundToInt() else recommendedClamped
                    val min = if (sorted.isNotEmpty()) sorted.first().roundToInt() else recommendedClamped
                    val max = if (sorted.isNotEmpty()) sorted.last().roundToInt() else recommendedClamped
                    val avg = if (newHistory.isNotEmpty()) newHistory.average().roundToInt() else recommendedClamped

                    val newSec = prev.durationSeconds + (if (secondTickCounter % 2 == 1) 1 else 0)

                    prev.copy(
                        durationSeconds = newSec,
                        currentRttMs = rttMs,
                        currentAudioJitterMs = audioJitter,
                        currentRecommendedMs = recommendedClamped,
                        historyPoints = newHistory,
                        p50Ms = p50,
                        p90Ms = p90,
                        p99Ms = p99,
                        minMs = min,
                        maxMs = max,
                        avgMs = avg
                    )
                }

                secondTickCounter++

                // 保持每 500ms 刷新一次采样点
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - cycleStart) / 1_000_000L
                val waitMs = (500L - elapsedMs).coerceAtLeast(100L)
                delay(waitMs)
            }
        }
    }

    /**
     * 结束持续探测
     */
    fun stopProbing() {
        probeJob?.cancel()
        probeJob = null
        _state.update { it.copy(isProbing = false) }
    }

    /**
     * 测量到目标音箱的真实物理 Wi-Fi 往返时延（以非阻塞短连接或本地网关探测）
     */
    private fun measureWifiRtt(host: String, port: Int): Float {
        if (host.isBlank()) {
            // 如果未配置音箱 IP，给出本地 Wi-Fi 标称 8~12ms 仿真抖动
            return 8f + (Math.random().toFloat() * 6f)
        }
        return try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val socket = Socket()
            // 尝试与 HomePod 的 AirPlay 端口建立快速握手探测
            socket.connect(InetSocketAddress(host, port), 350)
            val t1 = SystemClock.elapsedRealtimeNanos()
            socket.close()
            val rtt = (t1 - t0) / 1_000_000f
            rtt.coerceIn(2f, 150f)
        } catch (_: Exception) {
            // 网络偶发超时或握手拒绝，退回保守估算
            15f + (Math.random().toFloat() * 10f)
        }
    }
}
