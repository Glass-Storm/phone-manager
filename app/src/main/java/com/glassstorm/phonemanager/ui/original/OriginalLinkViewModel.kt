package com.glassstorm.phonemanager.ui.original

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassstorm.phonemanager.adapter.ai.AiSpeechPort
import com.glassstorm.phonemanager.adapter.android.di.ApplicationContext
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.di.DeviceAiModule
import com.glassstorm.phonemanager.transport.grpc.ConversationClient
import com.glassstorm.phonemanager.transport.grpc.ConversationHost
import com.glassstorm.phonemanager.transport.grpc.ConversationPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/** Owns the live gRPC link; the copied screens retain their original UI state contract. */
class OriginalLinkViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val speech: SpeechPort,
    ) : ViewModel() {
        private val server = MutableStateFlow(ServerUiState())
        val serverState = server.asStateFlow()
        private val client = MutableStateFlow(ClientUiState(isConnectionCardExpanded = true))
        val clientState = client.asStateFlow()
        private val rejectedSpeechSessions =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()
        private var host: ConversationHost? = null
        private var peer: ConversationClient? = null
        private var hostJob: Job? = null
        private var peerJob: Job? = null
        private var imageJob: Job? = null
        private var recording: Job? = null
        private var recorder: AudioRecord? = null
        private val problem = MutableStateFlow<String?>(null)
        val error = problem.asStateFlow()

        init {
            refreshNetwork()
        }

        fun clearError() {
            problem.value = null
        }

        private fun failed(message: String) {
            problem.value = message
        }

        fun refreshNetwork() {
            viewModelScope.launch(Dispatchers.IO) {
                server.update {
                    it.copy(
                        primaryIp = NetworkUtils.getPrimaryIp(),
                        allIps = NetworkUtils.getLocalIPv4Addresses(),
                        isEmulator = NetworkUtils.isEmulator(),
                    )
                }
            }
        }

        fun startServer(port: Int) {
            if (hostJob?.isActive == true) return
            val previousHost = hostJob
            hostJob =
                viewModelScope.launch {
                    previousHost?.join()
                    val endpoint = ConversationHost()
                    host = endpoint
                    try {
                        require(port in 1024..65535) { "Port 必須為 1024–65535" }
                        withContext(Dispatchers.IO) {
                            endpoint.start(port, onPacket = { id, packet -> receiveServer(id, packet) }, onClosed = { id ->
                                rejectedSpeechSessions.remove(id)
                                withContext(NonCancellable) { speech.close(id) }
                            })
                        }
                        server.update { it.copy(isRunning = true, port = port) }
                        endpoint.peerCount.collect { count -> server.update { it.copy(connectedClientsCount = count) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed("無法啟動 gRPC：${e.message}")
                    } finally {
                        withContext(Dispatchers.IO + NonCancellable) { endpoint.stop() }
                        server.update { it.copy(isRunning = false, connectedClientsCount = 0) }
                    }
                }
        }

        fun stopServer() {
            stopRecording()
            hostJob?.cancel()
            server.update { it.copy(isRunning = false, connectedClientsCount = 0) }
        }

        fun setIp(value: String) {
            val (address, port) = NetworkUtils.sanitizeHostAndPort(value.removePrefix("grpc://"), client.value.targetPort)
            client.update { it.copy(targetIp = address, targetPort = port) }
        }

        fun setPort(value: String) {
            client.update { it.copy(targetPort = value) }
        }

        fun connect() {
            if (peerJob?.isActive == true) return
            val configured = client.value.targetIp.trim()
            val address =
                if ((configured.isBlank() || configured == "192.168.43.1") && !NetworkUtils.isEmulator()) {
                    NetworkUtils.getWifiGatewayIp(context) ?: configured
                } else {
                    configured
                }
            client.update { it.copy(targetIp = address) }
            val port = client.value.targetPort.toIntOrNull()
            if (address.isBlank() || port == null || port !in 1024..65535) {
                failed("請輸入手機熱點 IP 及有效 Port。")
                return
            }
            client.update { it.copy(isConnecting = true, errorMessage = null) }
            val previousPeer = peerJob
            peerJob =
                viewModelScope.launch {
                    previousPeer?.join()
                    var endpoint: ConversationClient? = null
                    try {
                        endpoint = ConversationClient(address, port)
                        peer = endpoint
                        val ack = CompletableDeferred<Unit>()
                        val timeout =
                            launch {
                                try {
                                    withTimeout(10_000) { ack.await() }
                                } catch (e: Exception) {
                                    if (isActive) {
                                        failed("連線逾時，請確認已加入手機熱點、IP 同 Port 正確，並已啟動手機 Server。")
                                        endpoint.close()
                                    }
                                }
                            }
                        try {
                            endpoint.events().collect { packet ->
                                if (packet.ready) {
                                    ack.complete(Unit)
                                    client.update { it.copy(isConnected = true, isConnecting = false) }
                                } else {
                                    receiveClient(packet)
                                }
                            }
                        } finally {
                            timeout.cancel()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed("gRPC 連線中斷：${e.message}")
                        client.update { it.copy(errorMessage = e.message, isConnectionCardExpanded = true) }
                    } finally {
                        endpoint?.close()
                        client.update { it.copy(isConnected = false, isConnecting = false, isRecordingVoice = false) }
                        stopRecording()
                    }
                }
        }

        fun disconnect() {
            stopRecording()
            peerJob?.cancel()
            peer?.close()
            peer = null
            client.update { it.copy(isConnected = false, isConnecting = false) }
        }

        fun sendText(
            phone: Boolean,
            text: String,
        ) {
            if (text.isBlank()) return
            viewModelScope.launch {
                try {
                    requireConnected(phone)
                    send(phone, ConversationPacket(text = text))
                    add(phone, ChatMessage(text = text, isOutgoing = true, senderLabel = if (phone) "手機 (Server)" else "眼鏡"))
                    input(phone, "")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed("文字未傳送：${e.message}")
                }
            }
        }

        private fun requireConnected(phone: Boolean) {
            check(if (phone) server.value.isRunning && server.value.connectedClientsCount > 0 else client.value.isConnected) { "請先連接手機與眼鏡" }
        }

        private suspend fun send(
            phone: Boolean,
            packet: ConversationPacket,
        ) {
            if (phone) {
                check(host?.send(packet) == true) { "連線已中斷或傳送佇列已滿" }
            } else {
                checkNotNull(peer).send(packet)
            }
        }

        fun sendImage(
            phone: Boolean,
            bitmap: Bitmap,
            live: Boolean = false,
        ) {
            if (live && imageJob?.isActive == true) return
            imageJob =
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        requireConnected(phone)
                        val stream = ByteArrayOutputStream()
                        val reduced = Bitmap.createScaledBitmap(bitmap, 320, 240, true)
                        reduced.compress(Bitmap.CompressFormat.JPEG, 65, stream)
                        send(phone, ConversationPacket(jpeg = stream.toByteArray()))
                        if (!live) add(phone, ChatMessage(type = MessageType.PHOTO, imageBitmap = reduced, isOutgoing = true))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failed("影像未傳送：${e.message}")
                    }
                }
        }

        private suspend fun receiveServer(
            id: String,
            packet: ConversationPacket,
        ) {
            packet.text?.let { add(true, ChatMessage(text = it, isOutgoing = false, senderLabel = "眼鏡")) }
            packet.jpeg?.let { data ->
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { bitmap ->
                    server.update { it.copy(remoteLiveFrame = bitmap) }
                }
            }
            packet.pcm16?.let { pcm ->
                if (!speechReady()) {
                    if (rejectedSpeechSessions.add(
                            id,
                        )
                    ) {
                        host?.send(ConversationPacket(text = "[語音不可用] 手機未安裝完整離線模型，或裝置不是 arm64。請查看設定內的模型說明。"))
                    }
                } else {
                    transcribe(id, pcm)
                }
            }
        }

        private fun receiveClient(packet: ConversationPacket) {
            packet.text?.let {
                if (it.startsWith("[語音不可用]")) {
                    stopRecording()
                    failed(it)
                }
                add(false, ChatMessage(text = it, isOutgoing = false, senderLabel = "手機 (Server)"))
            }
            packet.subtitle?.let { add(false, ChatMessage(text = it, isOutgoing = false, senderLabel = "即時字幕")) }
            packet.jpeg?.let { data ->
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { bitmap ->
                    client.update { it.copy(remoteLiveFrame = bitmap) }
                }
            }
        }

        private fun speechReady(): Boolean =
            speech is AiSpeechPort &&
                DeviceAiModule.supportsRealEngines() &&
                DeviceAiModule.speechModelsReady(File(context.getExternalFilesDir(null) ?: context.filesDir, "models"))

        private suspend fun transcribe(
            id: String,
            pcm: ByteArray,
        ) {
            val result = if (pcm.isEmpty()) speech.flush(id) else speech.transcribe(id, pcm)
            result?.let {
                val text = if (it.speakerName.isNullOrBlank()) it.text else "${it.speakerName}：${it.text}"
                add(true, ChatMessage(text = text, isOutgoing = false, senderLabel = "即時字幕"))
                check(host?.send(ConversationPacket(subtitle = text)) == true) { "字幕無法傳送，請重新連接" }
            }
        }

        fun startRecording(phone: Boolean) {
            if (recording?.isActive == true) return
            try {
                requireConnected(phone)
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "請先在系統設定允許麥克風權限"
                }
                if (phone) {
                    check(speechReady()) { "手機需要 arm64 及完整 ASR／diarization 模型。請查看設定內的模型說明。" }
                }
            } catch (e: Exception) {
                failed(e.message ?: "語音未準備好")
                return
            }
            val previousRecording = recording
            recording =
                viewModelScope.launch(Dispatchers.IO) {
                    previousRecording?.join()
                    var mic: AudioRecord? = null
                    try {
                        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        check(min > 0) { "裝置不支援 16kHz 麥克風" }
                        mic =
                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                16_000,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                maxOf(min, 6400),
                            )
                        check(mic.state == AudioRecord.STATE_INITIALIZED) { "麥克風初始化失敗" }
                        recorder = mic
                        mic.startRecording()
                        server.update { it.copy(isRecordingVoice = phone) }
                        client.update { it.copy(isRecordingVoice = !phone) }
                        val buffer = ByteArray(640)
                        while (isActive) {
                            val size = mic.read(buffer, 0, buffer.size)
                            if (!isActive) break
                            check(size >= 0) { "麥克風讀取失敗：$size" }
                            if (size > 0) {
                                if (phone) {
                                    transcribe(
                                        "phone-microphone",
                                        buffer.copyOf(size),
                                    )
                                } else {
                                    send(false, ConversationPacket(pcm16 = buffer.copyOf(size)))
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed("語音已停止：${e.message}")
                    } finally {
                        runCatching { mic?.stop() }
                        mic?.release()
                        recorder = null
                        server.update { it.copy(isRecordingVoice = false) }
                        client.update { it.copy(isRecordingVoice = false) }
                        withContext(NonCancellable) {
                            runCatching {
                                if (phone && speechReady()) {
                                    transcribe("phone-microphone", byteArrayOf())
                                } else if (!phone && client.value.isConnected) {
                                    send(false, ConversationPacket(pcm16 = byteArrayOf()))
                                }
                            }
                            if (phone) speech.close("phone-microphone")
                        }
                    }
                }
        }

        fun stopRecording() {
            recording?.cancel()
            runCatching { recorder?.stop() }
        }

        fun input(
            phone: Boolean,
            value: String,
        ) {
            if (phone) server.update { it.copy(inputText = value) } else client.update { it.copy(inputText = value) }
        }

        fun clear(phone: Boolean) {
            if (phone) server.update { it.copy(messages = emptyList()) } else client.update { it.copy(messages = emptyList()) }
        }

        fun toggleCard(phone: Boolean) {
            if (phone) {
                server.update {
                    it.copy(isConnectionCardExpanded = !it.isConnectionCardExpanded)
                }
            } else {
                client.update { it.copy(isConnectionCardExpanded = !it.isConnectionCardExpanded) }
            }
        }

        fun showVideo(
            phone: Boolean,
            show: Boolean,
        ) {
            if (phone) server.update { it.copy(showRemoteVideo = show) } else client.update { it.copy(showRemoteVideo = show) }
        }

        private fun add(
            phone: Boolean,
            message: ChatMessage,
        ) {
            if (phone) {
                server.update { it.copy(messages = it.messages + message) }
            } else {
                client.update {
                    it.copy(
                        messages =
                            it.messages + message,
                    )
                }
            }
        }

        fun play(data: ByteArray) {
            viewModelScope.launch { AudioHelper.playAudioClip(data) }
        }

        override fun onCleared() {
            stopRecording()
            host?.stop()
            peer?.close()
            super.onCleared()
        }
    }
