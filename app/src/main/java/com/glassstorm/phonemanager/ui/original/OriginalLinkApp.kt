package com.glassstorm.phonemanager.ui.original

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/** The original test1 screens are used directly, with live gRPC callbacks. */
@Composable
fun OriginalLinkApp(factory: ViewModelProvider.Factory) {
    val model: OriginalLinkViewModel = viewModel(factory = factory)
    val server by model.serverState.collectAsState()
    val client by model.clientState.collectAsState()
    val error by model.error.collectAsState()
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("translation_ui", 0) }
    var role by rememberSaveable { mutableStateOf(preferences.getString("role", "").orEmpty()) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val phone = role == "手機端"
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { !it }) {
                android.widget.Toast
                    .makeText(
                        context,
                        "相機或麥克風未獲授權，請在系統設定允許後再使用。",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
            }
        }
    LaunchedEffect(role) {
        if (role.isNotBlank()) {
            val missing =
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        }
    }
    BackHandler(enabled = choosing || settings) {
        choosing = false
        settings = false
    }
    OriginalLinkTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = Color(0xFF064F6B),
                        shape =
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(4.dp),
                    ) {
                        Text("Wi-Fi", Modifier.padding(horizontal = 5.dp, vertical = 3.dp), fontSize = 10.sp)
                    }
                    Text("  INMO Link", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(" · 雙向語音文字", Modifier.weight(1f), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (role.isNotBlank()) IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "設定") }
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (role.isBlank() || choosing) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Text("選擇裝置角色", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text("選擇後會記住，下次可在設定更改。")
                        Spacer(Modifier.height(24.dp))
                        listOf("手機端", "眼鏡端").forEach { selected ->
                            Button(onClick = {
                                model.stopServer()
                                model.disconnect()
                                role = selected
                                choosing = false
                                preferences.edit().putString("role", selected).apply()
                            }, modifier = Modifier.fillMaxWidth()) { Text(selected) }
                        }
                    }
                } else if (phone) {
                    PhoneServerScreen(
                        state = server,
                        onStartServer = model::startServer,
                        onStopServer = model::stopServer,
                        onRefreshNetwork = model::refreshNetwork,
                        onSendMessage = { model.sendText(true, it) },
                        onSendPhoto = { model.sendImage(true, it) },
                        onSendVideoFrame = { model.sendImage(true, it, true) },
                        onStartVoiceRecord = { model.startRecording(true) },
                        onStopVoiceRecord = model::stopRecording,
                        onPlayAudio = model::play,
                        onToggleLiveFrame = { model.showVideo(true, it) },
                        onToggleConnectionCard = { model.toggleCard(true) },
                        onInputChanged = { model.input(true, it) },
                        onClearMessages = { model.clear(true) },
                    )
                } else {
                    GlassesClientScreen(
                        state = client,
                        onIpChanged = model::setIp,
                        onPortChanged = model::setPort,
                        onConnect = { model.connect() },
                        onDisconnect = model::disconnect,
                        onSendMessage = { model.sendText(false, it) },
                        onSendPhoto = { model.sendImage(false, it) },
                        onSendVideoFrame = { model.sendImage(false, it, true) },
                        onStartVoiceRecord = { model.startRecording(false) },
                        onStopVoiceRecord = model::stopRecording,
                        onPlayAudio = model::play,
                        onToggleLiveFrame = { model.showVideo(false, it) },
                        onToggleConnectionCard = { model.toggleCard(false) },
                        onInputChanged = { model.input(false, it) },
                        onClearMessages = { model.clear(false) },
                    )
                }
            }
        }
        if (settings) {
            AlertDialog(
                onDismissRequest = { settings = false },
                title = { Text("裝置設定 · $role") },
                text = {
                    Text(
                        "手機與眼鏡必須加入同一個 Wi-Fi／手機熱點。手機按啟動，眼鏡輸入手機 IP，Port 預設 8080。\n\n語音為離線廣東話／英文轉字幕，需在 arm64 手機安裝 ASR 及 diarization 模型。模型位置：Android/data/com.glassstorm.phonemanager/files/models。詳見 docs/live-link.md。\n\n變更角色會中斷連接。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        settings = false
                        choosing = true
                    }) { Text("更改角色") }
                },
                dismissButton = { TextButton(onClick = { settings = false }) { Text("返回") } },
            )
        }
        error?.let { message ->
            AlertDialog(
                onDismissRequest = model::clearError,
                title = { Text("連線／語音狀態") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = model::clearError) { Text("知道了") } },
            )
        }
    }
}
