package com.glassstorm.phonemanager.ui.original

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class MessageType {
    TEXT,
    PHOTO,
    AUDIO_CLIP,
    VIDEO_FRAME,
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val type: MessageType = MessageType.TEXT,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val senderLabel: String = if (isOutgoing) "You" else "Remote",
    val imageBitmap: Bitmap? = null,
    val audioDurationSec: Int = 0,
    val audioData: ByteArray? = null,
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    companion object {
        fun createTextJson(
            text: String,
            sender: String,
        ): String {
            val json = JSONObject()
            json.put("type", "TEXT")
            json.put("text", text)
            json.put("sender", sender)
            json.put("time", System.currentTimeMillis())
            return json.toString()
        }

        fun createPhotoJson(
            bitmap: Bitmap,
            sender: String,
        ): String {
            val stream = ByteArrayOutputStream()
            // Compress JPEG to maintain fast local Wi-Fi transfer
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val byteArray = stream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)

            val json = JSONObject()
            json.put("type", "PHOTO")
            json.put("image_base64", base64)
            json.put("sender", sender)
            json.put("time", System.currentTimeMillis())
            return json.toString()
        }

        fun createVideoFrameJson(
            bitmap: Bitmap,
            sender: String,
        ): String {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            val byteArray = stream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)

            val json = JSONObject()
            json.put("type", "VIDEO_FRAME")
            json.put("image_base64", base64)
            json.put("sender", sender)
            json.put("time", System.currentTimeMillis())
            return json.toString()
        }

        fun createAudioClipJson(
            audioPcm: ByteArray,
            durationSec: Int,
            sender: String,
        ): String {
            val base64 = Base64.encodeToString(audioPcm, Base64.NO_WRAP)
            val json = JSONObject()
            json.put("type", "AUDIO_CLIP")
            json.put("audio_base64", base64)
            json.put("duration_sec", durationSec)
            json.put("sender", sender)
            json.put("time", System.currentTimeMillis())
            return json.toString()
        }

        fun parseFromJson(
            jsonStr: String,
            defaultSender: String,
        ): ChatMessage? {
            return try {
                if (!jsonStr.startsWith("{")) {
                    // Plain text backward compatibility
                    return ChatMessage(
                        text = jsonStr,
                        type = MessageType.TEXT,
                        isOutgoing = false,
                        senderLabel = defaultSender,
                    )
                }

                val json = JSONObject(jsonStr)
                val typeStr = json.optString("type", "TEXT")
                val sender = json.optString("sender", defaultSender)
                val time = json.optLong("time", System.currentTimeMillis())

                when (typeStr) {
                    "TEXT" -> {
                        ChatMessage(
                            text = json.optString("text", ""),
                            type = MessageType.TEXT,
                            isOutgoing = false,
                            timestamp = time,
                            senderLabel = sender,
                        )
                    }
                    "PHOTO" -> {
                        val base64 = json.optString("image_base64", "")
                        val bytes = Base64.decode(base64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ChatMessage(
                            text = "📷 [相片]",
                            type = MessageType.PHOTO,
                            isOutgoing = false,
                            timestamp = time,
                            senderLabel = sender,
                            imageBitmap = bitmap,
                        )
                    }
                    "VIDEO_FRAME" -> {
                        val base64 = json.optString("image_base64", "")
                        val bytes = Base64.decode(base64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ChatMessage(
                            text = "📹 [即時影像畫面]",
                            type = MessageType.VIDEO_FRAME,
                            isOutgoing = false,
                            timestamp = time,
                            senderLabel = sender,
                            imageBitmap = bitmap,
                        )
                    }
                    "AUDIO_CLIP" -> {
                        val base64 = json.optString("audio_base64", "")
                        val bytes = Base64.decode(base64, Base64.DEFAULT)
                        val duration = json.optInt("duration_sec", 0)
                        ChatMessage(
                            text = "🎤 [語音訊息 ${duration}s]",
                            type = MessageType.AUDIO_CLIP,
                            isOutgoing = false,
                            timestamp = time,
                            senderLabel = sender,
                            audioDurationSec = duration,
                            audioData = bytes,
                        )
                    }
                    else -> {
                        ChatMessage(
                            text = json.optString("text", jsonStr),
                            type = MessageType.TEXT,
                            isOutgoing = false,
                            timestamp = time,
                            senderLabel = sender,
                        )
                    }
                }
            } catch (e: Exception) {
                // If parsing failed, treat as plain text
                ChatMessage(
                    text = jsonStr,
                    type = MessageType.TEXT,
                    isOutgoing = false,
                    senderLabel = defaultSender,
                )
            }
        }
    }
}
