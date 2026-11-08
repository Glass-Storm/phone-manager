package com.glassstorm.phonemanager.ui.original

import android.graphics.Bitmap

enum class AppMode {
    PHONE_SERVER,
    GLASSES_CLIENT,
}

data class ServerUiState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val primaryIp: String = "Detecting...",
    val allIps: List<NetworkUtils.NetworkIpInfo> = emptyList(),
    val isEmulator: Boolean = false,
    val connectedClientsCount: Int = 0,
    val lastClientAddress: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val statusMessage: String = "Server stopped",
    val remoteLiveFrame: Bitmap? = null,
    val isLiveStreaming: Boolean = false,
    val showRemoteVideo: Boolean = true,
    val isConnectionCardExpanded: Boolean = false,
    val isRecordingVoice: Boolean = false,
)

data class ClientUiState(
    val targetIp: String = "192.168.43.1",
    val targetPort: String = "8080",
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isEmulator: Boolean = false,
    val statusMessage: String = "Disconnected",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val errorMessage: String? = null,
    val remoteLiveFrame: Bitmap? = null,
    val isLiveStreaming: Boolean = false,
    val showRemoteVideo: Boolean = true,
    val isConnectionCardExpanded: Boolean = false,
    val isRecordingVoice: Boolean = false,
)
