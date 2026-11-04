package app.wordprism.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp

@Composable
fun SettingsScreen(
    openPreset: (String?) -> Unit, openRouting: () -> Unit, openData: () -> Unit,
    openAdjustments: () -> Unit, openTasks: () -> Unit, openDev: () -> Unit,
) {
    val sdk = appSdk()
    val presets by remember { sdk.presets.observePresets() }.collectAsStateWithLifecycle(null)
    val bindings by remember { sdk.presets.observeBindings() }.collectAsStateWithLifecycle(emptyList())
    val adapt by remember { sdk.adaptation.observeSettings() }.collectAsStateWithLifecycle(null)
    val c = Wp.colors

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).padding(bottom = 32.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(top = 20.dp))

        SectionTitle("API 预设") {
            TextButton(onClick = { openPreset(null) }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("新建") }
        }
        Text("可以保存多个模型连接，不同功能用不同预设。密钥只保存在本机。", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
        val list = presets
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState("还没有 API 预设", "添加一个预设后，AI 添加、练习生成和每日分析才能使用。") { Button(onClick = { openPreset(null) }) { Text("添加预设") } }
            else -> list.forEach { p ->
                NavRow(
                    title = p.name + if (!p.enabled) "（已停用）" else "",
                    subtitle = "${p.adapterLabel}，${p.model}" + "\n" + when {
                        !p.credential.present -> "缺少密钥"
                        p.lastTest?.ok == true -> "上次测试正常（${p.lastTest!!.at.dayAndClock()}）"
                        p.lastTest != null -> "上次测试失败"
                        else -> "还没测试过"
                    } + if (p.boundFeatures.isNotEmpty()) "，用于 ${p.boundFeatures.size} 项功能" else "",
                    trailing = if (!p.credential.present) ({ StatusPill("缺少密钥", tone = Tone.Negative) }) else null,
                ) { openPreset(p.id.value) }
            }
        }
        val pending = bindings.count { it.state == ApiReadiness.NeedsPreset || it.state == ApiReadiness.NeedsCredential }
        NavRow("功能分配与调用控制", if (pending > 0) "$pending 项功能还不能用，需要配置" else "每项 AI 功能使用哪个预设、额度和重试") { openRouting() }

        SectionTitle("学习")
        NavRow("AI 调整与每日预算", adapt?.let { (if (it.mode == AnalysisMode.Off) "每日分析已关闭" else "每天结束后分析一次") + "，预算 ${it.dailyBudgetMinutes} 分钟" } ?: "") { openAdjustments() }
        NavRow("任务记录", "AI 任务、导入导出的进度和结果") { openTasks() }

        SectionTitle("数据")
        NavRow("导入与导出", "WordPrism 专属数据包：完整备份或分享词组") { openData() }

        SectionTitle("关于")
        KeyValue("后端", sdk.implementationLabel, valueColor = if (sdk.isMock) c.caution else c.ink)
        KeyValue("接口契约", sdk.contractVersion)
        if (sdk.isMock) {
            Text("现在使用的是模拟后端：AI 结果、统计和调度都是演示数据，不会发出真实请求。", style = MaterialTheme.typography.bodySmall, color = c.caution, modifier = Modifier.padding(vertical = 4.dp))
            NavRow("开发：模拟场景", "离线、失败、预算、重置演示数据") { openDev() }
        }
    }
}

@Composable
fun NavRow(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Wp.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 12.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        trailing?.invoke()
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted)
    }
    Hairline()
}
