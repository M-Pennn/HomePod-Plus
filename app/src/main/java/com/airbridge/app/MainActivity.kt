package com.airbridge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.airbridge.app.airplay.AirplayDevice

class MainActivity : ComponentActivity() {

    private companion object {
        const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    private var pendingDevice: AirplayDevice? = null

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
                ContextCompat.startForegroundService(this, intent)
            } else {
                CastController.addLog("未获得屏幕/音频捕获授权")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContent {
            App(onConnect = { device -> connect(device) })
        }
    }

    private fun connect(device: AirplayDevice) {
        pendingDevice = device
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        if (isFinishing) CastController.stopDiscovery()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(onConnect: (AirplayDevice) -> Unit) {
    val ui by CastController.state.collectAsState()
    val context = LocalContext.current
    var showManual by remember { mutableStateOf(false) }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("AirBridge") }) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showManual = true },
                    icon = { Text("+") },
                    text = { Text("手动添加") }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(ui)
                if (ui.streaming) {
                    VolumeCard(
                        ui = ui,
                        onVolume = { CastController.setVolume(it) },
                        onStop = { CastController.stop(context) }
                    )
                } else {
                    DeviceList(ui, onConnect, Modifier.weight(1f))
                }
                LogPanel(ui.logs)
            }
        }
    }

    if (showManual) {
        ManualDialog(
            onDismiss = { showManual = false },
            onAdd = { name, ip, port ->
                CastController.addManual(AirplayDevice(name = name, host = ip, port = port))
                showManual = false
            }
        )
    }
}

@Composable
private fun StatusCard(ui: CastController.UiState) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(ui.status, style = MaterialTheme.typography.titleMedium)
            Text(
                if (ui.discovering) "正在扫描局域网... 已发现 ${ui.devices.size} 个设备" else "设备数: ${ui.devices.size}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (ui.connecting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DeviceList(
    ui: CastController.UiState,
    onConnect: (AirplayDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    if (ui.devices.isEmpty()) {
        Text(
            "没有发现设备。\n请确认手机和 HomePod 连接的是同一个 Wi-Fi；\n如果自动发现不到，点右下角“手动添加”，输入 HomePod 的 IP（端口 7000）。"
        )
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        items(ui.devices, key = { "${it.type}|${it.name}|${it.host}:${it.port}" }) { d ->
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(d.model ?: "AirPlay", style = MaterialTheme.typography.bodySmall)
                        Text("${d.host}:${d.port}", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onConnect(d) }, enabled = !ui.connecting) {
                        Text("连接")
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(ui: CastController.UiState, onVolume: (Float) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("正在投射", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { CastController.toggleTestTone(context) }) {
                Text(if (ui.testToneOn) "停止测试音" else "播放测试音 (440Hz)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量")
                Slider(
                    value = ui.volume,
                    onValueChange = onVolume,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    valueRange = 0f..100f
                )
                Text("${ui.volume.toInt()}%")
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("停止投射")
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>) {
    Card {
        Text(
            text = logs.takeLast(40).joinToString("\n").ifEmpty { "日志区" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
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
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = port.toIntOrNull() ?: 7000
                    if (ip.isNotBlank()) onAdd(name.ifBlank { ip }, ip.trim(), p)
                },
                enabled = ip.isNotBlank()
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
