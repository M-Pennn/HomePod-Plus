package com.homepodplus.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.homepodplus.app.airplay.AirplayDevice
import com.homepodplus.app.airplay.RaopSender.LatencyMode
import kotlin.math.roundToInt

// ==========================================
// 现代极简设计主题规范 (Design System Tokens)
// ==========================================
private object BaseTheme {
    val Primary = Color(0xFF0D0D0D)
    val Background = Color(0xFFFAFAFA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSecondary = Color(0xFFF2F2F4)
    val TextPrimary = Color(0xFF0D0D0D)
    val TextSecondary = Color(0xFF6B6B70)
    val TextTertiary = Color(0xFF9E9EA4)
    val Divider = Color(0xFFE5E5EA)
    val Danger = Color(0xFFFF3B30)
    val Success = Color(0xFF34C759)
    val Warning = Color(0xFFFF9500)

    val RadiusSm = RoundedCornerShape(8.dp)
    val RadiusMd = RoundedCornerShape(12.dp)
    val RadiusLg = RoundedCornerShape(16.dp)
    val RadiusPill = RoundedCornerShape(999.dp)

    // 现代极简灰度调色板（彻底消除默认紫蓝色）
    val ColorScheme = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF0D0D0D),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE5E5EA),
        onPrimaryContainer = Color(0xFF0D0D0D),
        secondary = Color(0xFF48484A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF2F2F4),
        onSecondaryContainer = Color(0xFF0D0D0D),
        tertiary = Color(0xFF6B6B70),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF0D0D0D),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0D0D0D),
        surfaceVariant = Color(0xFFF2F2F4),
        onSurfaceVariant = Color(0xFF6B6B70),
        outline = Color(0xFFD1D1D6),
        outlineVariant = Color(0xFFE5E5EA)
    )
}

class MainActivity : ComponentActivity() {

    private companion object {
        const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    private var pendingDevice: AirplayDevice? = null
    private var isPendingDual: Boolean = true

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val device = pendingDevice ?: return@registerForActivityResult
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = Intent(this, CastService::class.java)
                    .setAction(CastService.ACTION_START)
                    .putExtra(CastService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CastService.EXTRA_DATA, result.data)
                    .putExtra(CastService.EXTRA_HOST, device.host)
                    .putExtra(CastService.EXTRA_PORT, device.port)
                    .putExtra(CastService.EXTRA_NAME, device.name)
                    .putExtra(CastService.EXTRA_IS_DUAL, isPendingDual)
                ContextCompat.startForegroundService(this, intent)
            } else {
                CastController.addLog("未获得屏幕/音频捕获授权")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val perms = ArrayList<String>()
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val localNetworkExists = try {
                packageManager.getPermissionInfo(PERM_LOCAL_NETWORK, 0)
                true
            } catch (e: Exception) {
                false
            }
            if (localNetworkExists &&
                checkSelfPermission(PERM_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(PERM_LOCAL_NETWORK)
            }
            if (perms.isNotEmpty()) {
                requestPermissions(perms.toTypedArray(), 100)
            }
        }
        CastController.loadManual(applicationContext)
        CastController.startDiscovery(applicationContext)

        if (intent.getBooleanExtra("auto_start", false)) {
            val config = CastController.state.value.dualConfig
            if (config.isConfigured && !CastController.state.value.streaming) {
                CastController.setVolume(config.defaultVolume)
                connect(
                    AirplayDevice(name = config.speaker1Name, host = config.speaker1Host, port = config.speaker1Port),
                    isDual = true
                )
            }
        }

        setContent {
            MainApp(
                onStartCast = {
                    val config = CastController.state.value.dualConfig
                    if (config.isConfigured) {
                        connect(
                            AirplayDevice(name = config.speaker1Name, host = config.speaker1Host, port = config.speaker1Port),
                            isDual = true
                        )
                    }
                },
                onConnectSingle = { dev -> connect(dev, isDual = false) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("auto_start", false)) {
            val config = CastController.state.value.dualConfig
            if (config.isConfigured && !CastController.state.value.streaming) {
                CastController.setVolume(config.defaultVolume)
                connect(
                    AirplayDevice(name = config.speaker1Name, host = config.speaker1Host, port = config.speaker1Port),
                    isDual = true
                )
            }
        }
    }

    private fun connect(device: AirplayDevice, isDual: Boolean = true) {
        pendingDevice = device
        isPendingDual = isDual
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        if (isFinishing) CastController.stopDiscovery()
        super.onDestroy()
    }
}

@Composable
private fun MainApp(
    onStartCast: () -> Unit,
    onConnectSingle: (AirplayDevice) -> Unit
) {
    val ui by CastController.state.collectAsState()
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) } // 0: 投送 (主页), 1: 配置 (设置)

    AppScaffold(
        ui = ui,
        currentTab = currentTab,
        onTabSelect = { currentTab = it },
        onStartCast = onStartCast,
        onStopCast = { CastController.stop(context) },
        onVolumeChange = { CastController.setVolume(it) },
        onSaveConfig = { CastController.updateDualConfig(it) },
        onConnectSingle = onConnectSingle,
        onRescan = { CastController.startDiscovery(context) },
        onToggleTone = { CastController.toggleTestTone(context) },
        onDeleteManual = { CastController.removeManual(it) },
        onAddManual = { dev -> CastController.addManual(dev) },
        onClearLogs = { CastController.clearLogs() }
    )
}

/**
 * 完整支撑预览与真实运行的根脚手架（全新现代化界面）
 */
@Composable
private fun AppScaffold(
    ui: CastController.UiState,
    currentTab: Int,
    onTabSelect: (Int) -> Unit = {},
    onStartCast: () -> Unit = {},
    onStopCast: () -> Unit = {},
    onVolumeChange: (Float) -> Unit = {},
    onSaveConfig: (DualConfig) -> Unit = {},
    onConnectSingle: (AirplayDevice) -> Unit = {},
    onRescan: () -> Unit = {},
    onToggleTone: () -> Unit = {},
    onDeleteManual: (AirplayDevice) -> Unit = {},
    onAddManual: (AirplayDevice) -> Unit = {},
    onClearLogs: () -> Unit = {}
) {
    val hazeState = remember { HazeState() }

    MaterialTheme(colorScheme = BaseTheme.ColorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BaseTheme.Background)
        ) {
            // 页面主体内容（适配状态栏，内容铺满并在悬浮底栏下方划过）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .hazeSource(state = hazeState)
            ) {
                if (currentTab == 0) {
                    CastHomeScreen(
                        ui = ui,
                        onStartCast = onStartCast,
                        onStopCast = onStopCast,
                        onVolumeChange = onVolumeChange,
                        onGoToConfig = { onTabSelect(1) }
                    )
                } else {
                    ConfigScreen(
                        ui = ui,
                        onSaveConfig = onSaveConfig,
                        onConnectSingle = onConnectSingle,
                        onRescan = onRescan,
                        onToggleTone = onToggleTone,
                        onDeleteManual = onDeleteManual,
                        onAddManual = onAddManual,
                        onClearLogs = onClearLogs
                    )
                }
            }

            // 悬浮圆角磨砂胶囊底栏 (.tabbar-floating)
            FloatingTabBar(
                currentTab = currentTab,
                onTabSelect = onTabSelect,
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // 权限拒绝提示弹窗
            if (ui.accessDeniedAlert) {
                val view = LocalView.current
                AlertDialog(
                    onDismissRequest = {
                        AppHaptics.click(view)
                        CastController.dismissAccessDeniedAlert()
                    },
                    title = {
                        Text(
                            text = "设备访问受限",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BaseTheme.TextPrimary
                        )
                    },
                    text = {
                        Text(
                            text = "设备设置了访问权限 请到「家庭」App-右上角更多-扬声器与电视-配置连接权限并关闭密码",
                            fontSize = 14.sp,
                            color = BaseTheme.TextPrimary,
                            lineHeight = 22.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                AppHaptics.click(view)
                                CastController.dismissAccessDeniedAlert()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BaseTheme.Primary),
                            shape = BaseTheme.RadiusPill
                        ) {
                            Text("我知道了", color = Color.White)
                        }
                    },
                    shape = BaseTheme.RadiusLg,
                    containerColor = BaseTheme.Surface
                )
            }
        }
    }
}

// ==========================================
// 第一屏：极简投送主页 (CastHomeScreen)
// ==========================================
@Composable
private fun CastHomeScreen(
    ui: CastController.UiState,
    onStartCast: () -> Unit,
    onStopCast: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onGoToConfig: () -> Unit
) {
    val view = LocalView.current
    val masterVol = ui.volume
    val isStreaming = ui.streaming
    val isConnecting = ui.connecting
    val isConfigured = ui.dualConfig.isConfigured

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 距离顶部约 30% 留白定位
        Spacer(modifier = Modifier.weight(0.30f))

        // 核心投送波形 SVG 图标 (尺寸 80dp，纯粹现代质感)
        Icon(
            painter = painterResource(R.drawable.ic_cast_tab),
            contentDescription = "投送状态图标",
            tint = if (isStreaming) BaseTheme.Primary else BaseTheme.TextPrimary.copy(alpha = 0.85f),
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(30.dp))

        // 状态提示胶囊
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(BaseTheme.RadiusPill)
                .background(
                    when {
                        isStreaming -> BaseTheme.Success.copy(alpha = 0.12f)
                        isConnecting -> BaseTheme.Warning.copy(alpha = 0.12f)
                        else -> BaseTheme.SurfaceSecondary
                    }
                )
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isStreaming -> BaseTheme.Success
                            isConnecting -> BaseTheme.Warning
                            else -> BaseTheme.TextTertiary
                        }
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isStreaming -> "正在投送"
                    isConnecting -> "正在连接..."
                    else -> "未投送"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    isStreaming -> BaseTheme.Success
                    isConnecting -> BaseTheme.Warning
                    else -> BaseTheme.TextSecondary
                }
            )
        }

        // 图标/状态与下方控制区的过渡空间
        Spacer(modifier = Modifier.weight(0.35f))

        // 音量调节卡片 (仅保留调节条和百分比数字)
        Card(
            shape = BaseTheme.RadiusLg,
            colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "音量",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = BaseTheme.TextPrimary
                    )
                    Text(
                        text = "${masterVol.roundToInt()}%",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = BaseTheme.TextPrimary
                    )
                }

                Slider(
                    value = masterVol,
                    onValueChange = { newVol ->
                        if (newVol.roundToInt() != masterVol.roundToInt()) {
                            AppHaptics.tick(view)
                        }
                        onVolumeChange(newVol)
                    },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = BaseTheme.Primary,
                        activeTrackColor = BaseTheme.Primary,
                        inactiveTrackColor = BaseTheme.Divider
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 最下边：开始 / 停止投送按钮 (大号胶囊主动作按钮)
        Button(
            onClick = {
                AppHaptics.confirm(view)
                if (isStreaming) {
                    onStopCast()
                } else if (isConfigured) {
                    onStartCast()
                } else {
                    onGoToConfig()
                }
            },
            shape = BaseTheme.RadiusPill,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) BaseTheme.Danger else BaseTheme.Primary
            ),
            enabled = !isConnecting,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("连接中...", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text(
                    text = if (isStreaming) "停止投送" else "开始投送",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(84.dp))
    }
}

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return "0 秒"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h} 小时 ${m} 分 ${s} 秒"
        m > 0 -> "${m} 分 ${s} 秒"
        else -> "${s} 秒"
    }
}

// ==========================================
// 第二屏：配置与二级菜单 (ConfigScreen)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerItemCard(
    index: Int,
    isMaster: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    onSelectDevice: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrowRotation"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BaseTheme.RadiusLg)
            .background(BaseTheme.Surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "设备 ${index + 1}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = BaseTheme.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    if (isMaster) {
                        Box(
                            modifier = Modifier
                                .clip(BaseTheme.RadiusPill)
                                .background(BaseTheme.Primary.copy(alpha = 0.08f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("音量基准", fontSize = 10.sp, color = BaseTheme.Primary, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Text("(从属音箱)", fontSize = 11.sp, color = BaseTheme.TextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (host.isNotBlank()) "$name · $host:$port" else "未绑定设备",
                    fontSize = 12.sp,
                    color = if (host.isNotBlank()) BaseTheme.TextSecondary else BaseTheme.TextTertiary,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            val view = LocalView.current
            // 显著的高亮「选择设备」胶囊按键
            Button(
                onClick = {
                    AppHaptics.click(view)
                    onSelectDevice()
                },
                shape = BaseTheme.RadiusPill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BaseTheme.Primary,
                    contentColor = Color.White
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = "选择设备",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 折叠/展开小箭头按键
            IconButton(
                onClick = {
                    AppHaptics.tick(view)
                    expanded = !expanded
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = if (expanded) "收起配置" else "展开详细配置",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(arrowRotation),
                    tint = BaseTheme.TextSecondary
                )
            }
        }

        // 默认折叠隐藏，点击小箭头后才平滑展开详细 IP、端口等配置
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(BaseTheme.Divider)
                )
                BaseTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = "设备名称",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BaseTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = "IP 地址",
                        modifier = Modifier.weight(2f)
                    )
                    BaseTextField(
                        value = port,
                        onValueChange = onPortChange,
                        label = "端口",
                        modifier = Modifier.weight(1f),
                        isNumber = true
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigScreen(
    ui: CastController.UiState,
    onSaveConfig: (DualConfig) -> Unit,
    onConnectSingle: (AirplayDevice) -> Unit,
    onRescan: () -> Unit,
    onToggleTone: () -> Unit,
    onDeleteManual: (AirplayDevice) -> Unit,
    onAddManual: (AirplayDevice) -> Unit,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scrollState = rememberScrollState()

    var deviceCount by remember(ui.dualConfig) { mutableIntStateOf(ui.dualConfig.deviceCount) }

    var spk1Name by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(0) { SpeakerItemConfig("设备 1") }.name) }
    var spk1Host by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(0) { SpeakerItemConfig("设备 1") }.host) }
    var spk1Port by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(0) { SpeakerItemConfig("设备 1") }.port.toString()) }
    var spk1Channel by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(0) { SpeakerItemConfig("设备 1") }.channel) }

    var spk2Name by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(1) { SpeakerItemConfig("设备 2") }.name) }
    var spk2Host by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(1) { SpeakerItemConfig("设备 2") }.host) }
    var spk2Port by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(1) { SpeakerItemConfig("设备 2") }.port.toString()) }
    var spk2Channel by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(1) { SpeakerItemConfig("设备 2") }.channel) }
    var spk2Offset by remember(ui.dualConfig) { mutableFloatStateOf(ui.dualConfig.speakers.getOrElse(1) { SpeakerItemConfig("设备 2") }.volumeOffset.coerceAtMost(0f)) }

    var spk3Name by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(2) { SpeakerItemConfig("设备 3") }.name) }
    var spk3Host by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(2) { SpeakerItemConfig("设备 3") }.host) }
    var spk3Port by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(2) { SpeakerItemConfig("设备 3") }.port.toString()) }
    var spk3Channel by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(2) { SpeakerItemConfig("设备 3") }.channel) }
    var spk3Offset by remember(ui.dualConfig) { mutableFloatStateOf(ui.dualConfig.speakers.getOrElse(2) { SpeakerItemConfig("设备 3") }.volumeOffset.coerceAtMost(0f)) }

    var spk4Name by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(3) { SpeakerItemConfig("设备 4") }.name) }
    var spk4Host by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(3) { SpeakerItemConfig("设备 4") }.host) }
    var spk4Port by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(3) { SpeakerItemConfig("设备 4") }.port.toString()) }
    var spk4Channel by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speakers.getOrElse(3) { SpeakerItemConfig("设备 4") }.channel) }
    var spk4Offset by remember(ui.dualConfig) { mutableFloatStateOf(ui.dualConfig.speakers.getOrElse(3) { SpeakerItemConfig("设备 4") }.volumeOffset.coerceAtMost(0f)) }

    var offsetMode by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.offsetMode) }
    var defVolume by remember(ui.dualConfig) { mutableFloatStateOf(ui.dualConfig.defaultVolume) }
    var hwVolControl by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.hardwareVolControl) }
    var showPersistentNotif by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.showPersistentNotification) }
    var autoReconnectEnabled by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.autoReconnectEnabled) }
    var autoReconnectMinutes by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.autoReconnectMinutes.toString()) }

    var showAddDialog by remember { mutableStateOf(false) }
    var selectingSpeakerIndex by remember { mutableStateOf<Int?>(null) }

    val currentEditedConfig = DualConfig(
        deviceCount = deviceCount,
        speakers = listOf(
            SpeakerItemConfig(spk1Name.trim().ifBlank { "设备 1" }, spk1Host.trim(), spk1Port.toIntOrNull() ?: 7000, if (deviceCount == 1) SpeakerChannel.STEREO else spk1Channel, 0f),
            SpeakerItemConfig(spk2Name.trim().ifBlank { "设备 2" }, spk2Host.trim(), spk2Port.toIntOrNull() ?: 7000, spk2Channel, spk2Offset.coerceAtMost(0f)),
            SpeakerItemConfig(spk3Name.trim().ifBlank { "设备 3" }, spk3Host.trim(), spk3Port.toIntOrNull() ?: 7000, spk3Channel, spk3Offset.coerceAtMost(0f)),
            SpeakerItemConfig(spk4Name.trim().ifBlank { "设备 4" }, spk4Host.trim(), spk4Port.toIntOrNull() ?: 7000, spk4Channel, spk4Offset.coerceAtMost(0f))
        ),
        offsetMode = offsetMode,
        defaultVolume = defVolume,
        hardwareVolControl = hwVolControl,
        showPersistentNotification = showPersistentNotif,
        autoReconnectEnabled = autoReconnectEnabled,
        autoReconnectMinutes = autoReconnectMinutes.toIntOrNull()?.coerceIn(1, 1440) ?: 50
    )

    val isModified = currentEditedConfig != ui.dualConfig

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 模块 0：设备数量选择 (1~4 台)
            SectionHeader("设备数量配置")
            Card(
                shape = BaseTheme.RadiusLg,
                colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("目标投送音箱数量", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${deviceCount} 台", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BaseTheme.Primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..4).forEach { count ->
                            FilterChip(
                                selected = deviceCount == count,
                                onClick = {
                                    AppHaptics.tick(view)
                                    deviceCount = count
                                },
                                label = { Text("${count} 台") },
                                shape = BaseTheme.RadiusPill,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BaseTheme.Primary,
                                    selectedLabelColor = Color.White,
                                    containerColor = BaseTheme.Surface,
                                    labelColor = BaseTheme.TextPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = deviceCount == count,
                                    borderColor = BaseTheme.Divider,
                                    selectedBorderColor = BaseTheme.Primary
                                )
                            )
                        }
                    }
                    Text(
                        text = if (deviceCount == 1) "单设备投送默认输出完整立体声，无需额外配置声道与音量差值。" else "多设备投送支持为每台音箱独立分配输出声道与从属音量。",
                        fontSize = 12.sp,
                        color = BaseTheme.TextSecondary
                    )
                }
            }

            // 模块 1：HomePod 设备网络与名称配置
            SectionHeader("HomePod 设备配置")
            Card(
                shape = BaseTheme.RadiusLg,
                colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SpeakerItemCard(
                        index = 0,
                        isMaster = true,
                        name = spk1Name,
                        onNameChange = { spk1Name = it },
                        host = spk1Host,
                        onHostChange = { spk1Host = it },
                        port = spk1Port,
                        onPortChange = { spk1Port = it },
                        onSelectDevice = { selectingSpeakerIndex = 0 }
                    )

                    if (deviceCount >= 2) {
                        SpeakerItemCard(
                            index = 1,
                            isMaster = false,
                            name = spk2Name,
                            onNameChange = { spk2Name = it },
                            host = spk2Host,
                            onHostChange = { spk2Host = it },
                            port = spk2Port,
                            onPortChange = { spk2Port = it },
                            onSelectDevice = { selectingSpeakerIndex = 1 }
                        )
                    }

                    if (deviceCount >= 3) {
                        SpeakerItemCard(
                            index = 2,
                            isMaster = false,
                            name = spk3Name,
                            onNameChange = { spk3Name = it },
                            host = spk3Host,
                            onHostChange = { spk3Host = it },
                            port = spk3Port,
                            onPortChange = { spk3Port = it },
                            onSelectDevice = { selectingSpeakerIndex = 2 }
                        )
                    }

                    if (deviceCount >= 4) {
                        SpeakerItemCard(
                            index = 3,
                            isMaster = false,
                            name = spk4Name,
                            onNameChange = { spk4Name = it },
                            host = spk4Host,
                            onHostChange = { spk4Host = it },
                            port = spk4Port,
                            onPortChange = { spk4Port = it },
                            onSelectDevice = { selectingSpeakerIndex = 3 }
                        )
                    }
                }
            }

            // 模块 2：声道分配与从属音量配置 (仅在 2 台及以上设备时显示)
            if (deviceCount >= 2) {
                SectionHeader("声道分配与从属音量")
                Card(
                    shape = BaseTheme.RadiusLg,
                    colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("音量联动模式", fontSize = 13.sp, color = BaseTheme.TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = offsetMode == VolumeOffsetMode.FIXED,
                                onClick = {
                                    AppHaptics.tick(view)
                                    offsetMode = VolumeOffsetMode.FIXED
                                },
                                label = { Text("固定差值") },
                                shape = BaseTheme.RadiusPill,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BaseTheme.Primary,
                                    selectedLabelColor = Color.White,
                                    containerColor = BaseTheme.Surface,
                                    labelColor = BaseTheme.TextPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = offsetMode == VolumeOffsetMode.FIXED,
                                    borderColor = BaseTheme.Divider,
                                    selectedBorderColor = BaseTheme.Primary
                                )
                            )
                            FilterChip(
                                selected = offsetMode == VolumeOffsetMode.RATIO,
                                onClick = {
                                    AppHaptics.tick(view)
                                    offsetMode = VolumeOffsetMode.RATIO
                                },
                                label = { Text("相对比例") },
                                shape = BaseTheme.RadiusPill,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BaseTheme.Primary,
                                    selectedLabelColor = Color.White,
                                    containerColor = BaseTheme.Surface,
                                    labelColor = BaseTheme.TextPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = offsetMode == VolumeOffsetMode.RATIO,
                                    borderColor = BaseTheme.Divider,
                                    selectedBorderColor = BaseTheme.Primary
                                )
                            )
                        }

                        Text(
                            text = if (offsetMode == VolumeOffsetMode.FIXED) {
                                "从属音箱音量在主音箱基础上降低固定差值"
                            } else {
                                "从属音箱音量按比例跟随主音箱"
                            },
                            fontSize = 12.sp,
                            color = BaseTheme.TextSecondary
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))

                        for (idx in 0 until deviceCount) {
                            if (idx > 0) {
                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))
                            }
                            val isMaster = (idx == 0)
                            val dName = when (idx) {
                                0 -> spk1Name.ifBlank { "主设备" }
                                1 -> spk2Name.ifBlank { "设备 2" }
                                2 -> spk3Name.ifBlank { "设备 3" }
                                else -> spk4Name.ifBlank { "设备 4" }
                            }
                            val curChannel = when (idx) {
                                0 -> spk1Channel
                                1 -> spk2Channel
                                2 -> spk3Channel
                                else -> spk4Channel
                            }
                            val onChannelChange: (SpeakerChannel) -> Unit = { ch ->
                                when (idx) {
                                    0 -> spk1Channel = ch
                                    1 -> spk2Channel = ch
                                    2 -> spk3Channel = ch
                                    else -> spk4Channel = ch
                                }
                            }
                            val curOffset = when (idx) {
                                0 -> 0f
                                1 -> spk2Offset
                                2 -> spk3Offset
                                else -> spk4Offset
                            }
                            val onOffsetChange: (Float) -> Unit = { off ->
                                val coerced = off.coerceAtMost(0f)
                                when (idx) {
                                    1 -> spk2Offset = coerced
                                    2 -> spk3Offset = coerced
                                    3 -> spk4Offset = coerced
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isMaster) "设备 1: $dName (主)" else "设备 ${idx + 1}: $dName",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BaseTheme.TextPrimary
                                    )
                                    if (isMaster) {
                                        Text(
                                            text = "基准音量 100%",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = BaseTheme.TextSecondary
                                        )
                                    } else {
                                        val displayStr = if (offsetMode == VolumeOffsetMode.FIXED) {
                                            if (curOffset.roundToInt() == 0) "同主音量 (0%)" else "${curOffset.roundToInt()}%"
                                        } else {
                                            val ratio = 1f + curOffset / 100f
                                            if (curOffset.roundToInt() == 0) "同主音量 (1.00x)" else "${"%.2f".format(ratio)}x (${curOffset.roundToInt()}%)"
                                        }
                                        Text(
                                            text = displayStr,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (curOffset == 0f) BaseTheme.TextPrimary else BaseTheme.Primary
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.weight(1.15f)
                                    ) {
                                        listOf(
                                            SpeakerChannel.STEREO to "立体声",
                                            SpeakerChannel.LEFT to "左声道",
                                            SpeakerChannel.RIGHT to "右声道"
                                        ).forEach { (ch, label) ->
                                            val isSelected = curChannel == ch
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(BaseTheme.RadiusPill)
                                                    .background(if (isSelected) BaseTheme.Primary else BaseTheme.Surface)
                                                    .border(1.dp, if (isSelected) BaseTheme.Primary else BaseTheme.Divider, BaseTheme.RadiusPill)
                                                    .clickable {
                                                    AppHaptics.tick(view)
                                                    onChannelChange(ch)
                                                }
                                                    .padding(vertical = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (isSelected) Color.White else BaseTheme.TextPrimary
                                                )
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier.weight(0.85f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isMaster) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(BaseTheme.RadiusPill)
                                                    .background(BaseTheme.Surface)
                                                    .border(1.dp, BaseTheme.Divider, BaseTheme.RadiusPill)
                                                    .padding(vertical = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "主音量",
                                                    fontSize = 11.sp,
                                                    color = BaseTheme.TextSecondary
                                                )
                                            }
                                        } else {
                                            Slider(
                                                value = curOffset,
                                                onValueChange = { newOff ->
                                                    if (newOff.roundToInt() != curOffset.roundToInt()) {
                                                        AppHaptics.tick(view)
                                                    }
                                                    onOffsetChange(newOff)
                                                },
                                                valueRange = -100f..0f,
                                                colors = SliderDefaults.colors(
                                                    thumbColor = BaseTheme.Primary,
                                                    activeTrackColor = BaseTheme.Primary,
                                                    inactiveTrackColor = BaseTheme.Divider
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 模块 2.5：系统控制与联动设置
            SectionHeader("系统控制与联动设置")
            Card(
                shape = BaseTheme.RadiusLg,
                colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("控制中心启动默认音量", fontSize = 14.sp)
                        Text("${defVolume.roundToInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = defVolume,
                        onValueChange = { newVol ->
                            if (newVol.roundToInt() != defVolume.roundToInt()) {
                                AppHaptics.tick(view)
                            }
                            defVolume = newVol
                        },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = BaseTheme.Primary,
                            activeTrackColor = BaseTheme.Primary,
                            inactiveTrackColor = BaseTheme.Divider
                        )
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("物理音量按键联动", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("投送时按手机侧边音量键直接调节 HomePod", fontSize = 12.sp, color = BaseTheme.TextSecondary)
                        }
                        Switch(
                            checked = hwVolControl,
                            onCheckedChange = {
                                AppHaptics.tick(view)
                                hwVolControl = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = BaseTheme.Primary,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = BaseTheme.Divider,
                                uncheckedBorderColor = Color.Transparent
                            )
                        )
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("通知栏常驻控制", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("在手机通知栏与状态栏显示实时音量胶囊与调节按键", fontSize = 12.sp, color = BaseTheme.TextSecondary)
                        }
                        Switch(
                            checked = showPersistentNotif,
                            onCheckedChange = {
                                AppHaptics.tick(view)
                                showPersistentNotif = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = BaseTheme.Primary,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = BaseTheme.Divider,
                                uncheckedBorderColor = Color.Transparent
                            )
                        )
                    }
                }
            }

        // 模块 3：音频与网络（保留的原功能）
        SectionHeader("音频传输与延迟调优")
        Card(
            shape = BaseTheme.RadiusLg,
            colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("延迟模式", fontSize = 13.sp, color = BaseTheme.TextSecondary)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    LatencyMode.entries.forEach { mode ->
                        FilterChip(
                            selected = ui.latencyMode == mode,
                            onClick = {
                                AppHaptics.tick(view)
                                CastController.setLatencyMode(mode)
                            },
                            label = {
                                Text(
                                    when (mode) {
                                        LatencyMode.SAFE -> "网络稳定(2s)"
                                        LatencyMode.STABLE -> "稳定(1.5s)"
                                        LatencyMode.LOW -> "低延迟(0.25s)"
                                        LatencyMode.ULTRA -> "极低(0.1s)"
                                    }
                                )
                            },
                            shape = BaseTheme.RadiusPill,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BaseTheme.Primary,
                                selectedLabelColor = Color.White,
                                containerColor = BaseTheme.Surface,
                                labelColor = BaseTheme.TextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = ui.latencyMode == mode,
                                borderColor = BaseTheme.Divider,
                                selectedBorderColor = BaseTheme.Primary
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("网络稳健模式", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Wi-Fi 冗余发包，离路由器远时防爆音", fontSize = 12.sp, color = BaseTheme.TextSecondary)
                    }
                    Switch(
                        checked = ui.robustMode,
                        onCheckedChange = {
                            AppHaptics.tick(view)
                            CastController.setRobustMode(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BaseTheme.Primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = BaseTheme.Divider,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("压缩 ALAC 编码", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("缩小音频包体积，弱网更稳", fontSize = 12.sp, color = BaseTheme.TextSecondary)
                    }
                    Switch(
                        checked = ui.alacOn,
                        onCheckedChange = {
                            AppHaptics.tick(view)
                            CastController.setAlac(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BaseTheme.Primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = BaseTheme.Divider,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }

                Button(
                    onClick = {
                        AppHaptics.click(view)
                        onToggleTone()
                    },
                    shape = BaseTheme.RadiusPill,
                    colors = ButtonDefaults.buttonColors(containerColor = BaseTheme.Surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BaseTheme.Divider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (ui.testToneOn) "停止测试音" else "播放 440Hz 测试音",
                        color = BaseTheme.TextPrimary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // 模块 3.5：定时自动重连与防破音
        SectionHeader("定时自动重连与防破音")
        Card(
            shape = BaseTheme.RadiusLg,
            colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("定时自动重连", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("达到设定时长后自动断开并快速重连", fontSize = 12.sp, color = BaseTheme.TextSecondary)
                    }
                    Switch(
                        checked = autoReconnectEnabled,
                        onCheckedChange = {
                            AppHaptics.tick(view)
                            autoReconnectEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BaseTheme.Primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = BaseTheme.Divider,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }

                AnimatedVisibility(
                    visible = autoReconnectEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("重连周期（分钟）", fontSize = 13.sp, color = BaseTheme.TextSecondary)
                                val currentMinutesVal = autoReconnectMinutes.toIntOrNull() ?: 50
                                Text("${currentMinutesVal} 分钟", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BaseTheme.Primary)
                            }

                            BaseTextField(
                                value = autoReconnectMinutes,
                                onValueChange = { newVal ->
                                    autoReconnectMinutes = newVal.filter { it.isDigit() }.take(4)
                                },
                                label = "输入重连分钟数 (建议 40~60 分钟)",
                                modifier = Modifier.fillMaxWidth(),
                                isNumber = true
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                listOf(30, 45, 50, 60, 90).forEach { mins ->
                                    val isSel = autoReconnectMinutes == mins.toString()
                                    FilterChip(
                                        selected = isSel,
                                        onClick = {
                                            AppHaptics.tick(view)
                                            autoReconnectMinutes = mins.toString()
                                        },
                                        label = { Text("${mins}分钟", fontSize = 11.sp) },
                                        shape = BaseTheme.RadiusPill,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BaseTheme.Primary,
                                            selectedLabelColor = Color.White,
                                            containerColor = BaseTheme.Surface,
                                            labelColor = BaseTheme.TextPrimary
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isSel,
                                            borderColor = BaseTheme.Divider,
                                            selectedBorderColor = BaseTheme.Primary
                                        )
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))

                        // 持续时长统计展示
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("连接持续时长统计", fontSize = 13.sp, color = BaseTheme.TextSecondary)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 当前连接
                                Card(
                                    shape = BaseTheme.RadiusMd,
                                    colors = CardDefaults.cardColors(containerColor = BaseTheme.Surface),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(BaseTheme.RadiusPill)
                                                    .background(if (ui.streaming) BaseTheme.Success else BaseTheme.TextTertiary)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text("当前连接", fontSize = 11.sp, color = BaseTheme.TextSecondary)
                                        }
                                        Text(
                                            text = if (ui.streaming) formatDuration(ui.currentDurationSeconds) else "未在投送",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (ui.streaming) BaseTheme.Primary else BaseTheme.TextTertiary
                                        )
                                    }
                                }

                                // 上次连接
                                Card(
                                    shape = BaseTheme.RadiusMd,
                                    colors = CardDefaults.cardColors(containerColor = BaseTheme.Surface),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(BaseTheme.RadiusPill)
                                                    .background(BaseTheme.TextTertiary)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text("上次连接", fontSize = 11.sp, color = BaseTheme.TextSecondary)
                                        }
                                        Text(
                                            text = if (ui.lastDurationSeconds > 0L) formatDuration(ui.lastDurationSeconds) else "暂无记录",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BaseTheme.TextPrimary
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))

                        // 说明文字让用户了解这个功能的用意
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BaseTheme.RadiusMd)
                                .background(BaseTheme.Surface)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "💡 功能说明：部分设备在连续投送 1 小时以上时，可能会因局域网微随时钟频偏或物理硬件缓冲区耗尽而引发持续破音。开启此功能后，将在设定的时间自动断开音箱并以毫秒级快速重新建联，无需重新授权屏幕投射。借助这短暂的 1 秒静默空白，彻底重置音箱底层音频缓冲与同步时钟，保障超长连续播放稳定如初。",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = BaseTheme.TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // 模块 4：系统运行日志
        SectionHeader("系统日志")
        Card(
            shape = BaseTheme.RadiusLg,
            colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("实时传输日志", fontSize = 13.sp, color = BaseTheme.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            AppHaptics.click(view)
                            val fullLog = ui.logs.joinToString("\n")
                            if (fullLog.isNotBlank()) {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("HomePodPlus Logs", fullLog)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("复制全部", fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            AppHaptics.click(view)
                            onClearLogs()
                        }) { Text("清空", fontSize = 12.sp) }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(BaseTheme.RadiusSm)
                        .background(BaseTheme.Surface)
                        .padding(8.dp)
                ) {
                    Text(
                        text = ui.logs.takeLast(40).joinToString("\n").ifEmpty { "暂无日志" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = BaseTheme.TextSecondary,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }

        // 移至页面最下方的保存大按钮
        Button(
            onClick = {
                AppHaptics.confirm(view)
                onSaveConfig(currentEditedConfig)
                Toast.makeText(context, "配置已保存并生效", Toast.LENGTH_SHORT).show()
            },
            shape = BaseTheme.RadiusPill,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isModified) BaseTheme.Primary else BaseTheme.Primary.copy(alpha = 1f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = if (isModified) "保存设置（有未保存修改）" else "保存设置",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(100.dp))
    }

    // 悬浮在屏幕顶部的未保存提示胶囊（不挤占页面布局、独立浮动、带阴影）
    AnimatedVisibility(
        visible = isModified,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Card(
            shape = BaseTheme.RadiusPill,
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_dialog_info),
                    contentDescription = "未保存提示",
                    tint = Color(0xFF856404),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "配置已修改，请点击页面底部「保存设置」使其生效",
                    color = Color(0xFF856404),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

    if (selectingSpeakerIndex != null) {
        val targetIdx = selectingSpeakerIndex!!
        DeviceSelectBottomSheet(
            targetSpeakerIndex = targetIdx,
            targetSpeakerName = when (targetIdx) {
                0 -> spk1Name.ifBlank { "设备 1" }
                1 -> spk2Name.ifBlank { "设备 2" }
                2 -> spk3Name.ifBlank { "设备 3" }
                else -> spk4Name.ifBlank { "设备 4" }
            },
            devices = ui.devices,
            isDiscovering = ui.discovering,
            onRescan = onRescan,
            onAddManual = { showAddDialog = true },
            onSelectDevice = { dev ->
                when (targetIdx) {
                    0 -> {
                        spk1Name = dev.name
                        spk1Host = dev.host
                        spk1Port = dev.port.toString()
                    }
                    1 -> {
                        spk2Name = dev.name
                        spk2Host = dev.host
                        spk2Port = dev.port.toString()
                    }
                    2 -> {
                        spk3Name = dev.name
                        spk3Host = dev.host
                        spk3Port = dev.port.toString()
                    }
                    3 -> {
                        spk4Name = dev.name
                        spk4Host = dev.host
                        spk4Port = dev.port.toString()
                    }
                }
                Toast.makeText(context, "已绑定「${dev.name}」到设备 ${targetIdx + 1}", Toast.LENGTH_SHORT).show()
                selectingSpeakerIndex = null
            },
            onDismiss = { selectingSpeakerIndex = null }
        )
    }

    if (showAddDialog) {
        ManualBottomSheet(
            onDismiss = { showAddDialog = false },
            onAdd = { name, ip, port ->
                onAddManual(AirplayDevice(name = name, host = ip, port = port))
                showAddDialog = false
            }
        )
    }
}

// ==========================================
// 悬浮圆角磨砂胶囊底栏 (.tabbar-floating)
// ==========================================
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun FloatingTabBar(
    currentTab: Int,
    onTabSelect: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val tabFraction by animateFloatAsState(
        targetValue = currentTab.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tabSlider"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                elevation = 16.dp,
                shape = BaseTheme.RadiusPill,
                ambientColor = Color(0x30000000),
                spotColor = Color(0x40000000)
            )
            .clip(BaseTheme.RadiusPill)
            .hazeEffect(
                state = hazeState,
                style = HazeMaterials.regular()
            )
            .background(Color.White.copy(alpha = 0.25f))
            .border(1.dp, Color(0x1F000000), BaseTheme.RadiusPill)
            .padding(4.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val tabWidth = maxWidth / 2
            val indicatorOffset = tabWidth * tabFraction

            // 内部物理连续滑动的压暗指示滑块
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(BaseTheme.RadiusPill)
                    .background(Color(0x18000000)) // 压暗背景以区隔激活状态
            )

            // 两个 Tab 选项
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabItem(
                    title = "投送",
                    iconRes = R.drawable.ic_cast_tab,
                    onClick = { onTabSelect(0) },
                    modifier = Modifier.weight(1f)
                )
                TabItem(
                    title = "配置",
                    iconRes = R.drawable.ic_gear_tab,
                    onClick = { onTabSelect(1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    title: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .clip(BaseTheme.RadiusPill)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // 关闭独立按钮水波纹，由连续滑动的压暗滑块表现反馈
                onClick = {
                    AppHaptics.tick(view)
                    onClick()
                }
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = title,
                tint = BaseTheme.TextPrimary, // 未激活与激活状态均呈现纯正黑色
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = BaseTheme.TextPrimary // 未激活与激活状态均呈现纯正黑色
            )
        }
    }
}

// ==========================================
// 辅助小组件
// ==========================================
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = BaseTheme.TextTertiary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun BaseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isNumber: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = if (isNumber) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        shape = BaseTheme.RadiusSm,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BaseTheme.Primary,
            unfocusedBorderColor = BaseTheme.Divider,
            focusedContainerColor = BaseTheme.Surface,
            unfocusedContainerColor = BaseTheme.Surface
        ),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSelectBottomSheet(
    targetSpeakerIndex: Int,
    targetSpeakerName: String,
    devices: List<AirplayDevice>,
    isDiscovering: Boolean,
    onRescan: () -> Unit,
    onAddManual: () -> Unit,
    onSelectDevice: (AirplayDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val view = LocalView.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = BaseTheme.Surface,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(BaseTheme.RadiusPill)
                    .background(BaseTheme.Divider)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("选择设备", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = BaseTheme.TextPrimary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("当前配置槽位: 设备 ${targetSpeakerIndex + 1} ($targetSpeakerName)", fontSize = 12.sp, color = BaseTheme.TextSecondary)
                }
                TextButton(onClick = {
                    AppHaptics.tick(view)
                    onRescan()
                }, shape = BaseTheme.RadiusPill) {
                    if (isDiscovering) {
                        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = BaseTheme.Primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("扫描中", fontSize = 13.sp, color = BaseTheme.Primary)
                    } else {
                        Text("刷新", fontSize = 13.sp, color = BaseTheme.Primary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (devices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (isDiscovering) "正在搜索局域网中的 HomePod..." else "未发现 AirPlay 设备",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = BaseTheme.TextPrimary
                            )
                            Text(
                                text = "请确保手机与 HomePod 连接到相同的 Wi-Fi 网络",
                                fontSize = 12.sp,
                                color = BaseTheme.TextTertiary
                            )
                        }
                    }
                } else {
                    devices.forEach { dev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BaseTheme.RadiusMd)
                                .background(BaseTheme.SurfaceSecondary)
                                .clickable {
                                    AppHaptics.confirm(view)
                                    onSelectDevice(dev)
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BaseTheme.TextPrimary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("${dev.host}:${dev.port} · ${dev.model ?: "AirPlay"}", fontSize = 11.sp, color = BaseTheme.TextSecondary)
                            }
                            Button(
                                onClick = {
                                    AppHaptics.confirm(view)
                                    onSelectDevice(dev)
                                },
                                shape = BaseTheme.RadiusPill,
                                colors = ButtonDefaults.buttonColors(containerColor = BaseTheme.Primary),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("绑定", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedButton(
                    onClick = {
                        AppHaptics.click(view)
                        onDismiss()
                        onAddManual()
                    },
                    shape = BaseTheme.RadiusPill,
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Text("+ 手动输入 IP 添加设备", fontSize = 12.sp, color = BaseTheme.Primary, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualBottomSheet(
    onDismiss: () -> Unit,
    onAdd: (name: String, ip: String, port: Int) -> Unit
) {
    val view = LocalView.current
    var name by remember { mutableStateOf("HomePod") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7000") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = BaseTheme.Surface,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(BaseTheme.RadiusPill)
                    .background(BaseTheme.Divider)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("手动添加设备", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = BaseTheme.TextPrimary)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BaseTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "设备名称",
                    modifier = Modifier.fillMaxWidth()
                )
                BaseTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = "IP 地址",
                    modifier = Modifier.fillMaxWidth()
                )
                BaseTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = "端口",
                    modifier = Modifier.fillMaxWidth(),
                    isNumber = true
                )
            }

            val isValid = ip.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } ?: false)
            Button(
                onClick = {
                    AppHaptics.confirm(view)
                    val p = port.toIntOrNull() ?: 7000
                    if (isValid) onAdd(name.ifBlank { ip }, ip.trim(), p)
                },
                enabled = isValid,
                shape = BaseTheme.RadiusPill,
                colors = ButtonDefaults.buttonColors(containerColor = BaseTheme.Primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text("确认添加", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

// ==========================================
// Android Studio 实时高保真预览
// ==========================================
@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AppPreviewHomeScreen() {
    val mockUi = CastController.UiState(
        discovering = false,
        streaming = true,
        volume = 60f,
        dualConfig = DualConfig(
            deviceCount = 2,
            speakers = listOf(
                SpeakerItemConfig("客厅 HomePod", "192.168.1.101", 7000, SpeakerChannel.STEREO, 0f),
                SpeakerItemConfig("卧室 HomePod mini", "192.168.1.102", 7000, SpeakerChannel.STEREO, -10f),
                SpeakerItemConfig("书房音箱", "", 7000, SpeakerChannel.STEREO, 0f),
                SpeakerItemConfig("阳台音箱", "", 7000, SpeakerChannel.STEREO, 0f)
            )
        )
    )
    AppScaffold(ui = mockUi, currentTab = 0)
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AppPreviewConfigScreen() {
    val mockUi = CastController.UiState(
        discovering = false,
        streaming = false,
        volume = 50f,
        dualConfig = DualConfig(
            deviceCount = 2,
            speakers = listOf(
                SpeakerItemConfig("客厅", "192.168.1.101", 7000, SpeakerChannel.LEFT, 0f),
                SpeakerItemConfig("卧室", "192.168.1.102", 7000, SpeakerChannel.RIGHT, -10f),
                SpeakerItemConfig("设备 3", "", 7000, SpeakerChannel.STEREO, 0f),
                SpeakerItemConfig("设备 4", "", 7000, SpeakerChannel.STEREO, 0f)
            )
        ),
        devices = listOf(
            AirplayDevice("客厅 HomePod", "192.168.1.101", 7000, model = "AudioAccessory6,1"),
            AirplayDevice("卧室 HomePod mini", "192.168.1.102", 7000, model = "AudioAccessory5,1")
        ),
        logs = listOf("系统初始化完成", "已加载设备配置: 客厅 & 卧室")
    )
    AppScaffold(ui = mockUi, currentTab = 1)
}

@Preview(showBackground = true, showSystemUi = true, name = "⚡ 真实可交互模式 (在右侧点手指图标启动)")
@Composable
private fun InteractiveAppPreview() {
    var currentTab by remember { mutableIntStateOf(0) }
    var isStreaming by remember { mutableStateOf(false) }
    var masterVol by remember { androidx.compose.runtime.mutableFloatStateOf(60f) }
    var dualConfig by remember {
        mutableStateOf(
            DualConfig(
                deviceCount = 2,
                speakers = listOf(
                    SpeakerItemConfig("客厅 HomePod", "192.168.1.101", 7000, SpeakerChannel.STEREO, 0f),
                    SpeakerItemConfig("卧室 HomePod mini", "192.168.1.102", 7000, SpeakerChannel.STEREO, -10f),
                    SpeakerItemConfig("设备 3", "", 7000, SpeakerChannel.STEREO, 0f),
                    SpeakerItemConfig("设备 4", "", 7000, SpeakerChannel.STEREO, 0f)
                )
            )
        )
    }

    val mockUi = CastController.UiState(
        discovering = false,
        streaming = isStreaming,
        volume = masterVol,
        dualConfig = dualConfig,
        devices = listOf(
            AirplayDevice("客厅 HomePod", "192.168.1.101", 7000, model = "AudioAccessory6,1"),
            AirplayDevice("卧室 HomePod mini", "192.168.1.102", 7000, model = "AudioAccessory5,1")
        ),
        logs = listOf("HomePod + 已就绪", "发现设备: 客厅 HomePod", "发现设备: 卧室 HomePod mini")
    )

    AppScaffold(
        ui = mockUi,
        currentTab = currentTab,
        onTabSelect = { currentTab = it },
        onStartCast = { isStreaming = true },
        onStopCast = { isStreaming = false },
        onVolumeChange = { masterVol = it },
        onSaveConfig = { dualConfig = it }
    )
}

