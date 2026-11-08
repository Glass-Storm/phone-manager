package com.glassstorm.phonemanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class TranslationLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun portraitKeepsComposerVisible() {
        renderConversation("portrait")
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    fun landscapeKeepsComposerVisible() {
        renderConversation("landscape")
    }

    private fun renderConversation(name: String) {
        lateinit var rootView: View
        composeRule.setContent {
            rootView = LocalView.current.rootView
            TranslationFlow("手機端", onRoleChanged = {})
        }
        composeRule.onNodeWithText("模擬連接裝置").performClick()
        composeRule.onNodeWithText("開始對話").performClick()
        composeRule.onNodeWithText("示範").performClick()
        composeRule.onNodeWithText("你好，請問去車站應該點行？").assertIsDisplayed()
        composeRule.onNodeWithText("一直向前行，然後右轉。").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("加入本機對話").assertIsDisplayed()
        val output = File("build/ui-review/$name.png")
        output.parentFile?.mkdirs()
        composeRule.runOnIdle {
            val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            rootView.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
