package com.glassstorm.phonemanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.Surface
import com.glassstorm.phonemanager.ui.components.Text

/** UI-only conversation prototype. No action starts a transport or AI engine. */
@Composable
fun TranslationApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("translation_ui", 0) }
    var role by rememberSaveable { mutableStateOf(preferences.getString("role", "").orEmpty()) }
    TranslationFlow(
        role = role,
        onRoleChanged = {
            role = it
            preferences.edit().putString("role", it).apply()
        },
    )
}

@Composable
internal fun TranslationFlow(
    role: String,
    onRoleChanged: (String) -> Unit,
) {
    // A restored activity must reconnect; a saved role is not evidence of a connection.
    var connected by remember { mutableStateOf(false) }
    var page by rememberSaveable(role) { mutableStateOf("connection") }
    var choosingRole by rememberSaveable { mutableStateOf(false) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var showExample by rememberSaveable { mutableStateOf(false) }
    var largeText by rememberSaveable { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    LaunchedEffect(page, role, choosingRole) { scrollState.scrollTo(0) }

    BackHandler(enabled = page != "connection" || choosingRole) {
        page = "connection"
        choosingRole = false
    }
    Surface(color = AppTheme.colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(scrollState)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("悅視", style = AppTheme.typography.h2, color = AppTheme.colors.primary)
            Text("讓每一句話，都被看見。", style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary)
            if (role.isBlank() || choosingRole) {
                TranslationRolePicker(
                    onSelect = {
                        connected = false
                        paused = false
                        showExample = false
                        choosingRole = false
                        page = "connection"
                        onRoleChanged(it)
                    },
                )
                if (role.isNotBlank()) Action("取消", onClick = { choosingRole = false })
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(role, style = AppTheme.typography.label1)
                    Text("UI 預覽 · 模擬資料", style = AppTheme.typography.label2, color = AppTheme.colors.textSecondary)
                }
                when (page) {
                    "settings" -> {
                        TranslationPanel("個人化設定", "此裝置目前使用 $role")
                        Action("更改裝置角色", onClick = { choosingRole = true })
                        Action(if (largeText) "字幕大小：特大" else "字幕大小：標準", onClick = { largeText = !largeText })
                        Action("返回", onClick = { page = if (connected) "conversation" else "connection" })
                    }
                    "conversation" -> {
                        if (connected) {
                            TranslationPanel("即時對話", if (paused) "已暫停顯示" else "模擬連線已建立 · 廣東話 / 香港手語")
                            if (showExample) {
                                TranslationPanel(
                                    "講者 1 · 語音 → 字幕",
                                    "你好，請問去車站應該點行？",
                                    largeText,
                                )
                                TranslationPanel(
                                    "我 · 手語 → 語音",
                                    "一直向前行，然後右轉。",
                                    largeText,
                                )
                                Text("以上為示範對話，未執行辨識或語音播放。", style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary)
                            } else {
                                TranslationPanel("準備好開始對話", "對話會按講者顯示字幕，手語回覆會以文字呈現。")
                            }
                            Action("顯示示範對話", enabled = !paused, onClick = { showExample = true })
                            Action(if (paused) "繼續顯示" else "暫停顯示", onClick = { paused = !paused })
                            Action("結束對話", onClick = {
                                connected = false
                                showExample = false
                                paused = false
                                page = "connection"
                            })
                        } else {
                            TranslationPanel("連線已結束", "請先返回裝置連線頁。")
                            Action("返回連線", onClick = { page = "connection" })
                        }
                        Action("設定", onClick = { page = "settings" })
                    }
                    else -> {
                        TranslationPanel("先連線，再開始", "01 選擇角色   /   02 連接裝置   /   03 開始對話")
                        TranslationPanel(
                            if (role == "手機端") "手機中樞" else "連接手機中樞",
                            if (role == "手機端") {
                                "開啟手機熱點，讓眼鏡或頸鏈加入同一個網絡。連接完成後，才開始翻譯對話。"
                            } else {
                                "先加入手機提供的 Wi-Fi 熱點，再連接手機中樞以接收字幕及手語結果。"
                            },
                        )
                        TranslationPanel(
                            if (connected) "裝置已連接（模擬）" else "等待連接",
                            if (connected) "可以開始體驗對話介面。" else "尚未建立連線，對話功能暫未開放。",
                        )
                        Action(if (connected) "中斷模擬連接" else "模擬連接裝置", onClick = { connected = !connected })
                        Action("開始對話", enabled = connected, onClick = { page = "conversation" })
                        Action("設定", onClick = { page = "settings" })
                        Text("目前是介面原型，模擬連接不會啟動熱點、相機或麥克風。", style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationRolePicker(onSelect: (String) -> Unit) {
    TranslationPanel("這部裝置係？", "首次使用請選擇角色。之後會記住你的選擇，也可以隨時在設定更改。")
    TranslationPanel("手機端", "作為對話中樞，連接眼鏡與頸鏈，查看字幕及手語結果。")
    Action("使用手機端", onClick = { onSelect("手機端") })
    TranslationPanel("眼鏡端", "連接手機，閱讀清晰字幕，跟上每一段對話。")
    Action("使用眼鏡端", onClick = { onSelect("眼鏡端") })
}

@Composable
private fun TranslationPanel(
    title: String,
    description: String,
    largeText: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.outline),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = AppTheme.typography.label1, color = AppTheme.colors.primary)
            Text(description, style = if (largeText) AppTheme.typography.h2 else AppTheme.typography.body1)
        }
    }
}

@Composable
private fun Action(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(text = label, enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
}

@Preview(showBackground = true)
@Composable
private fun TranslationRolePreview() {
    HubTheme { TranslationFlow(role = "", onRoleChanged = {}) }
}
