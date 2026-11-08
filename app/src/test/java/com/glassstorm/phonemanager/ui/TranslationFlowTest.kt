package com.glassstorm.phonemanager.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
        composeRule.onNodeWithText("開始對話").assertIsNotEnabled()
        click("模擬連接裝置")
        click("開始對話")
        composeRule.onNodeWithText("即時對話 · 紀錄 (0)").assertIsDisplayed()
        click("結束對話")
        composeRule.onNodeWithText("開始對話").assertIsNotEnabled()
    }

    @Test
    fun `role selection disappears and can be changed in settings`() {
        setFlow("")
        click("使用手機端")
        composeRule.onNodeWithText("使用眼鏡端").assertDoesNotExist()
        click("設定")
        click("更改裝置角色")
        click("使用眼鏡端")
        composeRule.onNodeWithText("等待連接 · 眼鏡端").assertIsDisplayed()
        composeRule.onNodeWithText("開始對話").assertIsNotEnabled()
    }

    @Test
    fun `sample conversation remains explicitly labelled`() {
        setFlow("眼鏡端")
        click("模擬連接裝置")
        click("開始對話")
        click("示範")
        composeRule.onNodeWithText("你好，請問去車站應該點行？").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("UI 預覽 · 模擬資料").assertIsDisplayed()
    }

    @Test
    fun `previous sentences remain after another translation and reconnect`() {
        setFlow("手機端")
        click("模擬連接裝置")
        click("開始對話")
        click("示範")
        click("示範")
        composeRule.onNodeWithText("即時對話 · 紀錄 (4)").assertIsDisplayed()
        click("結束對話")
        composeRule.onNodeWithText("對話紀錄 (4)").assertIsDisplayed()
        click("清空")
        composeRule.onNodeWithText("對話紀錄 (4)").assertIsDisplayed()
        click("取消")
        click("模擬連接裝置")
        click("開始對話")
        composeRule.onNodeWithText("即時對話 · 紀錄 (4)").assertIsDisplayed()
    }

    @Test
    fun `activity recreation retains history but requires reconnection`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { TranslationFlow("手機端", onRoleChanged = {}) }
        click("模擬連接裝置")
        click("開始對話")
        click("示範")
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("對話紀錄 (2)").assertIsDisplayed()
        composeRule.onNodeWithText("開始對話").assertIsNotEnabled()
    }

    private fun click(text: String) {
        if (text == "設定") {
            composeRule.onNodeWithContentDescription(text).performClick()
        } else if (text.startsWith("使用")) {
            composeRule.onNodeWithText(text).performScrollTo().performClick()
        } else {
            composeRule.onNodeWithText(text).performClick()
        }
    }

    private fun setFlow(initialRole: String) {
        composeRule.setContent {
            var role by remember { mutableStateOf(initialRole) }
            HubTheme { TranslationFlow(role, onRoleChanged = { role = it }) }
        }
    }
}
