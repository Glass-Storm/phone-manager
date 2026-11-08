package com.glassstorm.phonemanager.ui.original

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.model.SpeechUtterance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
class OriginalLinkIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `phone and glasses view models exchange real messages and reject fake speech`() {
        val speech =
            object : SpeechPort {
                override suspend fun transcribe(
                    sessionId: String,
                    pcm16: ByteArray,
                ): SpeechUtterance? = error("No speech engine should run without models")

                override suspend fun flush(sessionId: String): SpeechUtterance? = error("No fake final transcript")

                override suspend fun close(sessionId: String) = Unit
            }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val phone = OriginalLinkViewModel(app, speech)
        val glasses = OriginalLinkViewModel(ApplicationProvider.getApplicationContext(), speech)
        val port = ServerSocket(0).use { it.localPort }
        compose.setContent { }
        try {
            compose.runOnIdle { phone.startServer(port) }
            await { phone.serverState.value.isRunning || phone.error.value != null }
            org.junit.Assert.assertNull(phone.error.value)
            compose.runOnIdle {
                glasses.setIp("127.0.0.1")
                glasses.setPort(port.toString())
                glasses.connect()
            }
            await { glasses.clientState.value.isConnected && phone.serverState.value.connectedClientsCount == 1 }
            compose.runOnIdle { glasses.sendText(false, "Hello Phone") }
            await {
                phone.serverState.value.messages
                    .any { it.text == "Hello Phone" && !it.isOutgoing }
            }
            compose.runOnIdle { phone.sendText(true, "Hello Glasses") }
            await {
                glasses.clientState.value.messages
                    .any { it.text == "Hello Glasses" && !it.isOutgoing }
            }
            compose.runOnIdle { phone.startRecording(true) }
            await { phone.error.value != null }
            org.junit.Assert.assertFalse(phone.serverState.value.isRecordingVoice)
            org.junit.Assert.assertTrue(
                phone.error.value
                    .orEmpty()
                    .contains("模型"),
            )
            compose.runOnIdle { glasses.disconnect() }
            await { !glasses.clientState.value.isConnected && phone.serverState.value.connectedClientsCount == 0 }
        } finally {
            compose.runOnIdle {
                glasses.disconnect()
                phone.stopServer()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
}
