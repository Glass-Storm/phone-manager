package com.glassstorm.phonemanager.ui.original

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port")
class OriginalLinkLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun portraitUsesOriginalPhoneLayout() {
        render("phone", true)
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    fun landscapeUsesOriginalGlassesLayout() {
        render("glasses", false)
    }

    private fun render(
        name: String,
        phone: Boolean,
    ) {
        lateinit var root: View
        val messages =
            listOf(
                ChatMessage(text = "Hello Phone", isOutgoing = false, senderLabel = "眼鏡"),
                ChatMessage(text = "Hello Glasses", isOutgoing = true, senderLabel = "手機 (Server)"),
            )
        compose.setContent {
            root = LocalView.current.rootView
            OriginalLinkTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (phone) {
                        PhoneServerScreen(
                            ServerUiState(isRunning = true, connectedClientsCount = 1, primaryIp = "192.168.68.65", messages = messages),
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                        )
                    } else {
                        GlassesClientScreen(
                            ClientUiState(isConnected = true, targetIp = "192.168.68.65", messages = messages),
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Hello Phone").assertIsDisplayed()
        compose.onNodeWithText("Hello Glasses").assertIsDisplayed()
        val file = File("build/ui-review/original-$name.png")
        file.parentFile?.mkdirs()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
