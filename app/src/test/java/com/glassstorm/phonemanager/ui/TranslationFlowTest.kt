package com.glassstorm.phonemanager.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `connection is required and ending conversation closes the gate`() {
        setFlow("手機端")
        composeRule.onNodeWithText("開始對話").performScrollTo().assertIsNotEnabled()
        click("模擬連接裝置")
        click("開始對話")
        composeRule.onNodeWithText("即時對話").assertIsDisplayed()
        click("結束對話")
        composeRule.onNodeWithText("開始對話").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `role selection disappears and can be changed in settings`() {
        setFlow("")
        click("使用手機端")
        composeRule.onNodeWithText("使用眼鏡端").assertDoesNotExist()
        click("設定")
        click("更改裝置角色")
        click("使用眼鏡端")
        composeRule.onNodeWithText("連接手機中樞").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("開始對話").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `sample conversation remains explicitly labelled`() {
        setFlow("眼鏡端")
        click("模擬連接裝置")
        click("開始對話")
        click("顯示示範對話")
        composeRule.onNodeWithText("你好，請問去車站應該點行？").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("UI 預覽 · 模擬資料").performScrollTo().assertIsDisplayed()
    }

    private fun click(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun setFlow(initialRole: String) {
        composeRule.setContent {
            var role by remember { mutableStateOf(initialRole) }
            HubTheme { TranslationFlow(role, onRoleChanged = { role = it }) }
        }
    }
}
