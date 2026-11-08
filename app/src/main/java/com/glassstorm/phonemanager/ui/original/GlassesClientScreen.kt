package com.glassstorm.phonemanager.ui.original

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
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GlassesClientScreen(
    state: ClientUiState,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnect: (Context) -> Unit,
    onDisconnect: () -> Unit,
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
        // Collapsible Glasses Client Connection Card
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("client_config_card"),
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
                                        when {
                                            state.isConnected -> Color(0xFF10B981)
                                            state.isConnecting -> Color(0xFFF59E0B)
                                            else -> Color(0xFF94A3B8)
                                        },
                                    ),
                        )
                        Text(
                            text =
                                when {
                                    state.isConnected -> "已連線至手機 • ${state.targetIp}"
                                    state.isConnecting -> "連線中..."
                                    else -> "未連線"
                                },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.isConnected) {
                            Button(
                                onClick = onDisconnect,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier =
                                    Modifier
                                        .height(26.dp)
                                        .testTag("client_disconnect_button"),
                            ) {
                                Text("中斷", fontSize = 10.sp)
                            }
                        } else {
                            Button(
                                onClick = { onConnect(context) },
                                enabled = !state.isConnecting,
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier =
                                    Modifier
                                        .height(26.dp)
                                        .testTag("client_connect_button"),
                            ) {
                                if (state.isConnecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(11.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text("連線中", fontSize = 10.sp)
                                } else {
                                    Icon(Icons.Default.Cable, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("連線 (自動偵測)", fontSize = 10.sp)
                                }
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

                // Collapsible Detail Body: IP & Port Inputs and Error Banner
                AnimatedVisibility(visible = state.isConnectionCardExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            OutlinedTextField(
                                value = state.targetIp,
                                onValueChange = onIpChanged,
                                label = { Text("手機 IP", fontSize = 10.sp) },
                                placeholder = { Text("192.168.43.1") },
                                enabled = !state.isConnected && !state.isConnecting,
                                modifier =
                                    Modifier
                                        .weight(0.7f)
                                        .testTag("client_ip_input"),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                shape = RoundedCornerShape(6.dp),
                            )

                            OutlinedTextField(
                                value = state.targetPort,
                                onValueChange = onPortChanged,
                                label = { Text("Port", fontSize = 10.sp) },
                                placeholder = { Text("8080") },
                                enabled = !state.isConnected && !state.isConnecting,
                                modifier =
                                    Modifier
                                        .weight(0.3f)
                                        .testTag("client_port_input"),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(6.dp),
                            )
                        }

                        if (state.errorMessage != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Text(
                                        text = state.errorMessage,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live Remote Video Frame Stream (Received from Phone) - Toggleable
        if (state.remoteLiveFrame != null) {
            RemoteLiveFrameView(
                bitmap = state.remoteLiveFrame,
                label = "手機 (Host Phone)",
                isVisible = state.showRemoteVideo,
                onToggleVisibility = onToggleLiveFrame,
            )
        }

        // Multimedia Controls Panel (Photo, Video, Voice)
        if (state.isConnected) {
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
                text = "眼鏡螢幕即時顯示 (${state.messages.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(
                    onClick = { onSendMessage("Hello Phone") },
                    enabled = state.isConnected,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text("快傳: Hello Phone", fontSize = 10.sp)
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
                            text = "點擊上方「連線」即可一鍵直連手機！\n雙方支援文字、鏡頭相片、即時畫面與語音傳送！",
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
                placeholder = { Text("輸入文字傳送給手機...", fontSize = 12.sp) },
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag("client_message_input"),
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
                enabled = state.isConnected && state.inputText.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("client_send_button"),
            ) {
                Icon(Icons.Default.Send, contentDescription = "傳送", modifier = Modifier.size(16.dp))
            }
        }
    }
}
