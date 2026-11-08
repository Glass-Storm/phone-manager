package com.glassstorm.phonemanager.ui.original

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun MediaActionPanel(
    onSendPhoto: (Bitmap) -> Unit,
    onSendVideoFrame: (Bitmap) -> Unit,
    onStartVoiceRecord: () -> Unit,
    onStopVoiceRecord: () -> Unit,
    isRecordingVoice: Boolean,
    isStreamingVideo: Boolean,
    onToggleVideoStream: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isCameraPreviewVisible by remember { mutableStateOf(false) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var boundCameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }
    val latestStreaming by rememberUpdatedState(isStreamingVideo)
    val latestSendFrame by rememberUpdatedState(onSendVideoFrame)
    DisposableEffect(Unit) {
        onDispose {
            boundCameraProvider?.unbindAll()
            cameraExecutor.shutdownNow()
        }
    }
    DisposableEffect(isCameraPreviewVisible) {
        onDispose { boundCameraProvider?.unbindAll() }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Controls Row - Slim & Compact
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Camera / Photo Button
            FilledTonalButton(
                onClick = { isCameraPreviewVisible = !isCameraPreviewVisible },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .height(34.dp),
            ) {
                Icon(
                    if (isCameraPreviewVisible) Icons.Default.Close else Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(if (isCameraPreviewVisible) "關鏡頭" else "拍照/鏡頭", fontSize = 11.sp)
            }

            // Live Video Streaming Toggle
            FilledTonalButton(
                onClick = {
                    val next = !isStreamingVideo
                    if (next) isCameraPreviewVisible = true
                    onToggleVideoStream(next)
                },
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isStreamingVideo) Color(0xFFEF4444) else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (isStreamingVideo) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .height(34.dp),
            ) {
                Icon(
                    if (isStreamingVideo) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(if (isStreamingVideo) "停止視訊" else "即時影像", fontSize = 11.sp)
            }

            // Voice Clip Record Button: Click to start, click again to stop & send
            Button(
                onClick = {
                    if (isRecordingVoice) {
                        onStopVoiceRecord()
                    } else {
                        onStartVoiceRecord()
                    }
                },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = if (isRecordingVoice) Color(0xFFDC2626) else MaterialTheme.colorScheme.primary,
                    ),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .height(34.dp),
            ) {
                Icon(
                    if (isRecordingVoice) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(if (isRecordingVoice) "停止字幕 ■" else "語音字幕 ●", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Camera Preview & Snap View (Compact: height 140dp)
        if (isCameraPreviewVisible) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                if (!isCameraPreviewVisible || cameraExecutor.isShutdown) return@addListener
                                val cameraProvider = cameraProviderFuture.get()
                                boundCameraProvider = cameraProvider
                                val preview =
                                    Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }

                                val capture =
                                    ImageCapture
                                        .Builder()
                                        .setTargetRotation(previewView.display?.rotation ?: 0)
                                        .build()
                                imageCapture = capture

                                val analysis =
                                    ImageAnalysis
                                        .Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                var lastFrameTime = 0L
                                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val now = System.currentTimeMillis()
                                    if (latestStreaming && (now - lastFrameTime > 250)) {
                                        lastFrameTime = now
                                        val bitmap = imageProxy.toBitmap()
                                        val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
                                        val scaled = Bitmap.createScaledBitmap(rotated, 320, 240, true)
                                        latestSendFrame(scaled)
                                    }
                                    imageProxy.close()
                                }

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        capture,
                                        analysis,
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Snap Photo Button Overlay
                    FilledTonalButton(
                        onClick = {
                            val cap = imageCapture ?: return@FilledTonalButton
                            cap.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bitmap = image.toBitmap()
                                        val rotated = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
                                        val scaled = Bitmap.createScaledBitmap(rotated, 640, 480, true)
                                        onSendPhoto(scaled)
                                        image.close()
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        exception.printStackTrace()
                                    }
                                },
                            )
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.9f)),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 6.dp)
                                .height(28.dp),
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "拍照傳送", tint = Color.Black, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("拍照傳送", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteLiveFrameView(
    bitmap: Bitmap?,
    label: String,
    isVisible: Boolean,
    onToggleVisibility: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bitmap == null) return

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggleVisibility(!isVisible) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "LIVE $label 即時畫面",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isVisible) "收起畫面" else "展開畫面",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color(0xFF94A3B8),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        if (isVisible) Icons.Default.Close else Icons.Default.Videocam,
                        contentDescription = if (isVisible) "隱藏畫面" else "展開畫面",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            AnimatedVisibility(visible = isVisible) {
                Column {
                    Spacer(Modifier.height(3.dp))
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "即時影像",
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black),
                    )
                }
            }
        }
    }
}

fun rotateBitmap(
    bitmap: Bitmap,
    degrees: Int,
): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

@Composable
fun RichMessageBubble(
    msg: ChatMessage,
    onPlayAudio: (ByteArray) -> Unit,
) {
    val isMe = msg.isOutgoing
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = msg.senderLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isMe) MaterialTheme.colorScheme.primary else Color(0xFF0284C7),
            )
            Text(
                text = msg.formattedTime,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        Spacer(Modifier.height(2.dp))

        Surface(
            shape =
                RoundedCornerShape(
                    topStart = 10.dp,
                    topEnd = 10.dp,
                    bottomStart = if (isMe) 10.dp else 2.dp,
                    bottomEnd = if (isMe) 2.dp else 10.dp,
                ),
            color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                when (msg.type) {
                    MessageType.PHOTO -> {
                        if (msg.imageBitmap != null) {
                            Image(
                                bitmap = msg.imageBitmap.asImageBitmap(),
                                contentDescription = "相片",
                                contentScale = ContentScale.Fit,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "📷 相片",
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (isMe) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                        )
                    }
                    MessageType.AUDIO_CLIP -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        msg.audioData?.let { onPlayAudio(it) }
                                    }.padding(vertical = 2.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.VolumeUp, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            Column {
                                Text(
                                    text = "語音訊息 (${msg.audioDurationSec} 秒)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color =
                                        if (isMe) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                )
                                Text(
                                    text = "點擊以播放聲音",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                    color =
                                        if (isMe) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                alpha = 0.7f,
                                            )
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                        },
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (isMe) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                        )
                    }
                }
            }
        }
    }
}
