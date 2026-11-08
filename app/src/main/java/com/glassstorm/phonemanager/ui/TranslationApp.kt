package com.glassstorm.phonemanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.glassstorm.phonemanager.ui.components.Surface
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.components.textfield.OutlinedTextField

/** UI-only prototype: connection, camera, recording and translation are simulated. */
@Composable
fun TranslationApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("translation_ui", 0) }
    var role by rememberSaveable { mutableStateOf(preferences.getString("role", "").orEmpty()) }
    TranslationFlow(role) {
        role = it
        preferences.edit().putString("role", it).apply()
    }
}

@Composable
internal fun TranslationFlow(
    role: String,
    onRoleChanged: (String) -> Unit,
) {
    TranslationTheme { TranslationWorkspace(role, onRoleChanged) }
}

@Composable
private fun TranslationWorkspace(
    role: String,
    onRoleChanged: (String) -> Unit,
) {
    var connected by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var choosingRole by rememberSaveable { mutableStateOf(false) }
    var largeText by rememberSaveable { mutableStateOf(false) }
    var camera by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    // Keep every line until explicitly cleared, including across activity recreation.
    var messages by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val followingLatest = !listState.canScrollForward
    LaunchedEffect(messages.size) {
        // Never pull the reader away from an earlier sentence they are reviewing.
        if (messages.isNotEmpty() && followingLatest) listState.animateScrollToItem(messages.lastIndex)
    }
    BackHandler(enabled = settings || choosingRole) {
        settings = false
        choosingRole = false
    }
    Surface(color = AppTheme.colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(4.dp), color = AppTheme.colors.surface) {
                    Text("Wi-Fi", Modifier.padding(6.dp), style = AppTheme.typography.label3, color = AppTheme.colors.primary)
                }
                Text("  INMO Link", style = AppTheme.typography.h4)
                Text(" · 悅視字幕", Modifier.weight(1f), style = AppTheme.typography.label3, color = AppTheme.colors.textSecondary)
                if (role.isNotBlank() && !choosingRole) {
                    Surface(onClick = { settings = !settings }, modifier = Modifier.size(48.dp), color = AppTheme.colors.background) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                Icons.Default.Settings,
                                "設定",
                                Modifier.size(22.dp),
                                colorFilter = ColorFilter.tint(AppTheme.colors.textSecondary),
                            )
                        }
                    }
                }
            }
            if (role.isBlank() || choosingRole || settings) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (role.isBlank() || choosingRole) {
                        Spacer(Modifier.height(24.dp))
                        Text("選擇這部裝置的角色", style = AppTheme.typography.h2)
                        Text("只需選擇一次，之後可在設定更改。", color = AppTheme.colors.textSecondary)
                        RoleChoice("手機端", "作為中樞，連接眼鏡與頸鏈。") {
                            connected = false
                            active = false
                            messages = emptyList()
                            draft = ""
                            camera = false
                            confirmClear = false
                            choosingRole = false
                            settings = false
                            onRoleChanged("手機端")
                        }
                        RoleChoice("眼鏡端", "連接手機，閱讀字幕與對話紀錄。") {
                            connected = false
                            active = false
                            messages = emptyList()
                            draft = ""
                            camera = false
                            confirmClear = false
                            choosingRole = false
                            settings = false
                            onRoleChanged("眼鏡端")
                        }
                        if (role.isNotBlank()) LinkAction("取消", onClick = { choosingRole = false })
                    } else {
                        Text("設定", style = AppTheme.typography.h2)
                        Text("目前裝置：$role")
                        LinkAction("更改裝置角色", onClick = { choosingRole = true })
                        Text("更改角色會中斷模擬連線並清除本次紀錄。", color = AppTheme.colors.textSecondary)
                        LinkAction(if (largeText) "字幕大小：特大" else "字幕大小：標準", onClick = { largeText = !largeText })
                        LinkAction("返回對話", onClick = { settings = false })
                    }
                }
            } else {
                Surface(shape = RoundedCornerShape(10.dp), color = AppTheme.colors.surface) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (connected) "已連接（模擬） · $role" else "等待連接 · $role",
                                style = AppTheme.typography.label1,
                                color = if (connected) AppTheme.colors.success else AppTheme.colors.text,
                            )
                            Text("UI 預覽 · 模擬資料", style = AppTheme.typography.label3, color = AppTheme.colors.textSecondary)
                        }
                        LinkAction(if (connected) "中斷" else "模擬連接裝置", onClick = {
                            connected = !connected
                            active = false
                            camera = false
                        })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkAction(if (camera) "收起預覽" else "影像預覽", Modifier.weight(1f), enabled = connected && active, onClick = {
                        camera =
                            !camera
                    })
                    LinkAction(if (active) "結束對話" else "開始對話", Modifier.weight(1f), enabled = connected, onClick = {
                        if (active) {
                            active = false
                            connected = false
                            camera = false
                        } else {
                            active = true
                        }
                    })
                }
                if (camera && connected && active) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppTheme.colors.surface,
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("影像預覽區 · 尚未接駁相機", color = AppTheme.colors.textSecondary) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (active) "即時對話 · 紀錄 (${messages.size})" else "對話紀錄 (${messages.size})",
                        Modifier.weight(1f),
                        style = AppTheme.typography.label1,
                    )
                    LinkAction("示範", enabled = active && connected, onClick = {
                        messages = messages + listOf("語音|你好，請問去車站應該點行？", "手語|一直向前行，然後右轉。")
                    })
                    LinkAction(if (confirmClear) "確認清空" else "清空", enabled = messages.isNotEmpty(), onClick = {
                        if (confirmClear) {
                            messages = emptyList()
                            confirmClear = false
                        } else {
                            confirmClear = true
                        }
                    })
                    if (confirmClear) LinkAction("取消", onClick = { confirmClear = false })
                }
                if (messages.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (active && connected) "準備好開始對話" else "連接裝置後開始對話", color = AppTheme.colors.textSecondary)
                            Text("字幕與回覆會依序保留在這裡\n可以隨時向上捲動，查看上一句。", color = AppTheme.colors.textSecondary, style = AppTheme.typography.body2)
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        itemsIndexed(messages) { index, line -> TranscriptBubble(index + 1, line, largeText) }
                    }
                }
                Text(
                    if (active &&
                        connected
                    ) {
                        "示範模式 · 未執行辨識或播放語音"
                    } else {
                        "先連接，再開始；舊紀錄會保留至手動清空。"
                    },
                    style = AppTheme.typography.label3,
                    color = AppTheme.colors.textSecondary,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("文字回覆", style = AppTheme.typography.label3) },
                        placeholder = { Text(if (role == "手機端") "輸入文字給眼鏡…" else "輸入文字給手機…") },
                        maxLines = 3,
                        shape = RoundedCornerShape(10.dp),
                    )
                    Surface(
                        onClick = {
                            messages = messages + "文字|$draft"
                            draft = ""
                        },
                        enabled = connected && active && draft.isNotBlank(),
                        color = if (connected && active && draft.isNotBlank()) AppTheme.colors.primary else AppTheme.colors.disabled,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                Icons.Default.Send,
                                "加入本機對話",
                                Modifier.size(22.dp),
                                colorFilter = ColorFilter.tint(AppTheme.colors.onPrimary),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleChoice(
    role: String,
    description: String,
    onSelect: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = AppTheme.colors.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(role, style = AppTheme.typography.h3)
            Text(description, color = AppTheme.colors.textSecondary)
            LinkAction("使用$role", Modifier.fillMaxWidth(), onClick = onSelect)
        }
    }
}

@Composable
private fun TranscriptBubble(
    number: Int,
    line: String,
    largeText: Boolean,
) {
    val source = line.substringBefore('|')
    val own = source != "語音"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (own) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${if (own) "我" else "講者 1"} · $source · #$number",
            style = AppTheme.typography.label3,
            color = if (own) AppTheme.colors.primary else AppTheme.colors.textSecondary,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (own) AppTheme.colors.secondary else AppTheme.colors.surface,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            SelectionContainer {
                Text(
                    line.substringAfter('|'),
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = if (largeText) AppTheme.typography.h3 else AppTheme.typography.body1,
                )
            }
        }
    }
}

@Composable
private fun LinkAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val prominent = label.startsWith("使用") || label == "開始對話" || label == "模擬連接裝置"
    val background =
        when {
            !enabled -> AppTheme.colors.disabled
            prominent -> AppTheme.colors.primary
            else -> AppTheme.colors.surface
        }
    val foreground =
        when {
            !enabled -> AppTheme.colors.textSecondary
            prominent -> AppTheme.colors.onPrimary
            else -> AppTheme.colors.text
        }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        color = background,
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = AppTheme.typography.label2, color = foreground)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TranslationRolePreview() {
    TranslationFlow(role = "", onRoleChanged = {})
}
