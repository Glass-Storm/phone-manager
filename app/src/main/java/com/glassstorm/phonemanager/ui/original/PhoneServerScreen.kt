package com.glassstorm.phonemanager.ui.original

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PhoneServerScreen(
    state: ServerUiState,
    onStartServer: (Int) -> Unit,
    onStopServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendPhoto: (Bitmap) -> Unit,
    onSendVideoFrame: (Bitmap) -> Unit,
    onStartVoiceRecord: () -> Unit,
    onStopVoiceRecord: () -> Unit,
    onPlayAudio: (ByteArray) -> Unit,
    onToggleLiveFrame: (Boolean) -> Unit,
    onToggleConnectionCard: () -> Unit,
    onInputChanged: (String) -> Unit,
    onClearMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var isStreamingVideo by remember { mutableStateOf(false) }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty() && !listState.canScrollForward) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Collapsible Server Status & IP Card (Header is always visible and clickable to expand/collapse)
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("server_status_card"),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // Header Bar (Clickable to toggle card collapse)
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggleConnectionCard() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (state.isRunning) Color(0xFF10B981) else Color(0xFF94A3B8),
                                    ),
                        )
                        Text(
                            text = if (state.isRunning) "Server (Port ${state.port}) • ${state.primaryIp}" else "Server 已停止",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.connectedClientsCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = Color(0xFFE8F5E9),
                            ) {
                                Text(
                                    text = "🟢 連線(${state.connectedClientsCount})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32),
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!state.isRunning) {
                            Button(
                                onClick = { onStartServer(8080) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier =
                                    Modifier
                                        .height(26.dp)
                                        .testTag("server_start_button"),
                            ) {
                                Text("啟動", fontSize = 10.sp)
                            }
                        } else {
                            Button(
                                onClick = onStopServer,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier =
                                    Modifier
                                        .height(26.dp)
                                        .testTag("server_stop_button"),
                            ) {
                                Text("停止", fontSize = 10.sp)
                            }
                        }

                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (state.isConnectionCardExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (state.isConnectionCardExpanded) "收起設定" else "展開設定",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Collapsible Detail Body: IP, Refresh, Copy, Client Details
                AnimatedVisibility(visible = state.isConnectionCardExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "連線 IP：",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                    Text(
                                        text = state.primaryIp,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = onRefreshNetwork,
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "重新偵測", modifier = Modifier.size(13.dp))
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("Server IP", state.primaryIp))
                                            android.widget.Toast
                                                .makeText(
                                                    context,
                                                    "已複製：${state.primaryIp}",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                        },
                                        shape = RoundedCornerShape(4.dp),
                                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 1.dp),
                                        modifier = Modifier.height(22.dp),
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(11.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("複製", fontSize = 10.sp)
                                    }
                                }
                            }
                        }

                        if (state.lastClientAddress != null) {
                            Text(
                                text = "眼鏡裝置: ${state.lastClientAddress.removePrefix("/")}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }

        // Live Remote Video Frame Stream (Received from INMO Air 3 Glasses) - Toggleable without affecting chat
        if (state.remoteLiveFrame != null) {
            RemoteLiveFrameView(
                bitmap = state.remoteLiveFrame,
                label = "眼鏡 (INMO Air 3)",
                isVisible = state.showRemoteVideo,
                onToggleVisibility = onToggleLiveFrame,
            )
        }

        // Multimedia Controls Panel (Photo, Video, Voice)
        if (state.connectedClientsCount > 0) {
            MediaActionPanel(
                onSendPhoto = onSendPhoto,
                onSendVideoFrame = onSendVideoFrame,
                onStartVoiceRecord = onStartVoiceRecord,
                onStopVoiceRecord = onStopVoiceRecord,
                isRecordingVoice = state.isRecordingVoice,
                isStreamingVideo = isStreamingVideo,
                onToggleVideoStream = { isStreamingVideo = it },
            )
        }

        // Chat Header & Quick Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "通訊紀錄 (${state.messages.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(
                    onClick = { onSendMessage("Hello Glasses") },
                    enabled = state.isRunning && state.connectedClientsCount > 0,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text("快傳: Hello Glasses", fontSize = 10.sp)
                }

                OutlinedButton(
                    onClick = onClearMessages,
                    enabled = state.messages.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(1.dp))
                    Text("清空", fontSize = 10.sp)
                }
            }
        }

        // Messages List
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(8.dp),
        ) {
            if (state.messages.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "尚未有任何訊息或影像",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Text(
                            text = "1. Server 點擊「啟動」\n2. 眼鏡端點擊「連線」即可自動直連\n3. 雙方可即時傳送文字、相片、即時影像畫面與語音訊息！",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        RichMessageBubble(msg = msg, onPlayAudio = onPlayAudio)
                    }
                }
            }
        }

        // Message Input Row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = state.inputText,
                onValueChange = onInputChanged,
                placeholder = { Text("輸入文字傳送給眼鏡...", fontSize = 12.sp) },
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag("server_message_input"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions =
                    KeyboardActions(
                        onSend = {
                            if (state.inputText.isNotBlank()) {
                                onSendMessage(state.inputText)
                            }
                        },
                    ),
                shape = RoundedCornerShape(8.dp),
            )

            Button(
                onClick = { onSendMessage(state.inputText) },
                enabled = state.isRunning && state.inputText.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("server_send_button"),
            ) {
                Icon(Icons.Default.Send, contentDescription = "傳送", modifier = Modifier.size(16.dp))
            }
        }
    }
}
