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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
                    onValueChange = onVolumeChange,
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

// ==========================================
// 第二屏：配置与二级菜单 (ConfigScreen)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
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
    val scrollState = rememberScrollState()

    var s1Name by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speaker1Name) }
    var s1Host by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speaker1Host) }
    var s1Port by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speaker1Port.toString()) }

    var s2Name by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speaker2Name) }
    var s2Host by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speaker2Host) }
    var s2Port by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.speaker2Port.toString()) }

    var offsetMode by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.offsetMode) }
    var offsetValue by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.offsetValue) }
    var defVolume by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.defaultVolume) }
    var channelMode by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.channelMode) }
    var leftSpeaker by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.leftSpeaker) }
    var hwVolControl by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.hardwareVolControl) }
    var showPersistentNotif by remember(ui.dualConfig) { mutableStateOf(ui.dualConfig.showPersistentNotification) }

    var showAddDialog by remember { mutableStateOf(false) }

    val currentEditedConfig = DualConfig(
        speaker1Name = s1Name.trim().ifBlank { "主音箱" },
        speaker1Host = s1Host.trim(),
        speaker1Port = s1Port.toIntOrNull() ?: 7000,
        speaker2Name = s2Name.trim().ifBlank { "副音箱" },
        speaker2Host = s2Host.trim(),
        speaker2Port = s2Port.toIntOrNull() ?: 7000,
        offsetMode = offsetMode,
        offsetValue = offsetValue,
        defaultVolume = defVolume,
        channelMode = channelMode,
        leftSpeaker = leftSpeaker,
        hardwareVolControl = hwVolControl,
        showPersistentNotification = showPersistentNotif
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
            // 模块 1：双音箱硬件配置 (.list-group)
            SectionHeader("双 HomePod 设备配置")
        Card(
            shape = BaseTheme.RadiusLg,
            colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("主音箱 (音量基准)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BaseTheme.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BaseTextField(value = s1Name, onValueChange = { s1Name = it }, label = "名称", modifier = Modifier.weight(1.2f))
                    BaseTextField(value = s1Host, onValueChange = { s1Host = it }, label = "IP 地址", modifier = Modifier.weight(2f))
                    BaseTextField(value = s1Port, onValueChange = { s1Port = it }, label = "端口", modifier = Modifier.weight(1f), isNumber = true)
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))

                Text("副音箱 (自动跟随)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BaseTheme.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BaseTextField(value = s2Name, onValueChange = { s2Name = it }, label = "名称", modifier = Modifier.weight(1.2f))
                    BaseTextField(value = s2Host, onValueChange = { s2Host = it }, label = "IP 地址", modifier = Modifier.weight(2f))
                    BaseTextField(value = s2Port, onValueChange = { s2Port = it }, label = "端口", modifier = Modifier.weight(1f), isNumber = true)
                }
            }
        }

        // 模块 2：音量差值联动规则
        SectionHeader("音量差值跟随规则")
        Card(
            shape = BaseTheme.RadiusLg,
            colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("联动模式", fontSize = 13.sp, color = BaseTheme.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = offsetMode == VolumeOffsetMode.FIXED,
                        onClick = { offsetMode = VolumeOffsetMode.FIXED },
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
                        onClick = { offsetMode = VolumeOffsetMode.RATIO },
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (offsetMode == VolumeOffsetMode.FIXED) "差值幅度: ${offsetValue.roundToInt()}%" else "相对比例: ${(1f + offsetValue / 100f).let { "%.2fx".format(it) }}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (offsetValue < 0) "副音箱偏小" else if (offsetValue > 0) "副音箱偏大" else "两箱同音量",
                        fontSize = 12.sp,
                        color = BaseTheme.TextSecondary
                    )
                }

                Slider(
                    value = offsetValue,
                    onValueChange = { offsetValue = it },
                    valueRange = -50f..50f,
                    colors = SliderDefaults.colors(
                        thumbColor = BaseTheme.Primary,
                        activeTrackColor = BaseTheme.Primary,
                        inactiveTrackColor = BaseTheme.Divider
                    )
                )

                // 实时算例展示
                val sampleMaster = 50f
                val sampleSlave = when (offsetMode) {
                    VolumeOffsetMode.FIXED -> (sampleMaster + offsetValue).coerceIn(0f, 100f)
                    VolumeOffsetMode.RATIO -> (sampleMaster * (1f + offsetValue / 100f)).coerceIn(0f, 100f)
                }
                Text(
                    text = "示例：当主音箱为 50% 时，副音箱自动设为 ${sampleSlave.roundToInt()}%",
                    fontSize = 12.sp,
                    color = BaseTheme.TextSecondary
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))

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
                    onValueChange = { defVolume = it },
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
                        onCheckedChange = { hwVolControl = it },
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
                        onCheckedChange = { showPersistentNotif = it },
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

        // 模块 2.5：声场与声道分配
        SectionHeader("声场与声道分配")
        Card(
            shape = BaseTheme.RadiusLg,
            colors = CardDefaults.cardColors(containerColor = BaseTheme.SurfaceSecondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("声场模式", fontSize = 13.sp, color = BaseTheme.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = channelMode == ChannelMode.STEREO,
                        onClick = { channelMode = ChannelMode.STEREO },
                        label = { Text("完整立体声投送") },
                        shape = BaseTheme.RadiusPill,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BaseTheme.Primary,
                            selectedLabelColor = Color.White,
                            containerColor = BaseTheme.Surface,
                            labelColor = BaseTheme.TextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = channelMode == ChannelMode.STEREO,
                            borderColor = BaseTheme.Divider,
                            selectedBorderColor = BaseTheme.Primary
                        )
                    )
                    FilterChip(
                        selected = channelMode == ChannelMode.SPLIT,
                        onClick = { channelMode = ChannelMode.SPLIT },
                        label = { Text("分离左右声道投送") },
                        shape = BaseTheme.RadiusPill,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BaseTheme.Primary,
                            selectedLabelColor = Color.White,
                            containerColor = BaseTheme.Surface,
                            labelColor = BaseTheme.TextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = channelMode == ChannelMode.SPLIT,
                            borderColor = BaseTheme.Divider,
                            selectedBorderColor = BaseTheme.Primary
                        )
                    )
                }

                Text(
                    text = if (channelMode == ChannelMode.STEREO) {
                        "两台音箱均输出完整立体声，适合空间不同区域扩音。"
                    } else {
                        "将左右立体声声道物理分离至两台音箱，提供专职左右声道声场。"
                    },
                    fontSize = 12.sp,
                    color = BaseTheme.TextSecondary
                )

                if (channelMode == ChannelMode.SPLIT) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BaseTheme.Divider))

                    Text("左声道音箱指定", fontSize = 13.sp, color = BaseTheme.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = leftSpeaker == LeftSpeakerAssignment.SPEAKER1_LEFT,
                            onClick = { leftSpeaker = LeftSpeakerAssignment.SPEAKER1_LEFT },
                            label = { Text("${s1Name.ifBlank { "主音箱" }} 为左声道") },
                            shape = BaseTheme.RadiusPill,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BaseTheme.Primary,
                                selectedLabelColor = Color.White,
                                containerColor = BaseTheme.Surface,
                                labelColor = BaseTheme.TextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = leftSpeaker == LeftSpeakerAssignment.SPEAKER1_LEFT,
                                borderColor = BaseTheme.Divider,
                                selectedBorderColor = BaseTheme.Primary
                            )
                        )
                        FilterChip(
                            selected = leftSpeaker == LeftSpeakerAssignment.SPEAKER2_LEFT,
                            onClick = { leftSpeaker = LeftSpeakerAssignment.SPEAKER2_LEFT },
                            label = { Text("${s2Name.ifBlank { "副音箱" }} 为左声道") },
                            shape = BaseTheme.RadiusPill,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BaseTheme.Primary,
                                selectedLabelColor = Color.White,
                                containerColor = BaseTheme.Surface,
                                labelColor = BaseTheme.TextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = leftSpeaker == LeftSpeakerAssignment.SPEAKER2_LEFT,
                                borderColor = BaseTheme.Divider,
                                selectedBorderColor = BaseTheme.Primary
                            )
                        )
                    }

                    Text(
                        text = if (leftSpeaker == LeftSpeakerAssignment.SPEAKER1_LEFT) {
                            "● 左声道: ${s1Name.ifBlank { "主音箱" }}   ● 右声道: ${s2Name.ifBlank { "副音箱" }}"
                        } else {
                            "● 左声道: ${s2Name.ifBlank { "副音箱" }}   ● 右声道: ${s1Name.ifBlank { "主音箱" }}"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = BaseTheme.TextPrimary
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
                            onClick = { CastController.setLatencyMode(mode) },
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
                        onCheckedChange = { CastController.setRobustMode(it) },
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
                        onCheckedChange = { CastController.setAlac(it) },
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
                    onClick = onToggleTone,
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

        // 模块 4：局域网发现与手动添加（保留的原功能）
        SectionHeader("局域网设备扫描")
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
                    Text(
                        text = if (ui.discovering) "正在扫描局域网..." else "已发现 ${ui.devices.size} 台设备",
                        fontSize = 13.sp,
                        color = BaseTheme.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onRescan) { Text("刷新扫描", fontSize = 12.sp) }
                        TextButton(onClick = { showAddDialog = true }) { Text("+ 手动添加", fontSize = 12.sp) }
                    }
                }

                if (ui.devices.isEmpty()) {
                    Text("暂未发现设备，确保手机与 HomePod 在同一 Wi-Fi 下", fontSize = 12.sp, color = BaseTheme.TextTertiary)
                } else {
                    ui.devices.forEach { dev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BaseTheme.RadiusSm)
                                .background(BaseTheme.Surface)
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("${dev.host}:${dev.port} · ${dev.model ?: "AirPlay"}", fontSize = 11.sp, color = BaseTheme.TextTertiary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    s1Name = dev.name
                                    s1Host = dev.host
                                    s1Port = dev.port.toString()
                                }) {
                                    Text("设为主箱", fontSize = 11.sp)
                                }
                                TextButton(onClick = {
                                    s2Name = dev.name
                                    s2Host = dev.host
                                    s2Port = dev.port.toString()
                                }) {
                                    Text("设为副箱", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 模块 5：系统运行日志（保留的原功能）
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
                    TextButton(onClick = onClearLogs) { Text("清空", fontSize = 12.sp) }
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
                onSaveConfig(currentEditedConfig)
                Toast.makeText(context, "配置已保存并生效", Toast.LENGTH_SHORT).show()
            },
            shape = BaseTheme.RadiusPill,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isModified) BaseTheme.Primary else BaseTheme.Primary.copy(alpha = 0.85f)
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

    if (showAddDialog) {
        ManualDialog(
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .clip(BaseTheme.RadiusPill)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // 关闭独立按钮水波纹，由连续滑动的压暗滑块表现反馈
                onClick = onClick
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

@Composable
private fun ManualDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, ip: String, port: Int) -> Unit
) {
    var name by remember { mutableStateOf("HomePod") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7000") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加设备") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP 地址") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = port.toIntOrNull() ?: 7000
                    if (ip.isNotBlank()) onAdd(name.ifBlank { ip }, ip.trim(), p)
                },
                enabled = ip.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } ?: false)
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
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
            speaker1Name = "客厅 HomePod",
            speaker1Host = "192.168.1.101",
            speaker2Name = "卧室 HomePod mini",
            speaker2Host = "192.168.1.102",
            offsetValue = -10f
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
            speaker1Name = "客厅",
            speaker1Host = "192.168.1.101",
            speaker2Name = "卧室",
            speaker2Host = "192.168.1.102",
            offsetValue = -10f
        ),
        devices = listOf(
            AirplayDevice("客厅 HomePod", "192.168.1.101", 7000, model = "AudioAccessory6,1"),
            AirplayDevice("卧室 HomePod mini", "192.168.1.102", 7000, model = "AudioAccessory5,1")
        ),
        logs = listOf("系统初始化完成", "已加载双音箱配置: 客厅 & 卧室")
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
                speaker1Name = "客厅 HomePod",
                speaker1Host = "192.168.1.101",
                speaker2Name = "卧室 HomePod mini",
                speaker2Host = "192.168.1.102",
                offsetValue = -10f
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

