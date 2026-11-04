package app.wordprism.ui.dev

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wordprism.mock.MockScenario
import app.wordprism.mock.MockSdk
import app.wordprism.sdk.ErrorCode
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import kotlinx.coroutines.launch

/** Debug-only levers for isolated mock development. Hidden when the SDK is real. */
@Composable
fun DevPanelScreen(onBack: () -> Unit) {
    val sdk = appSdk() as? MockSdk
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        WpTopBar("开发：模拟场景", onBack = onBack)
        if (sdk == null) { Text("当前不是模拟后端", Modifier.padding(Wp.gutter)); return@Column }
        val sc by sdk.controls.scenario.collectAsStateWithLifecycle()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).navigationBarsPadding().padding(bottom = 32.dp)) {
            Text("这些开关只影响模拟后端，用来检查各种界面状态。", style = MaterialTheme.typography.bodyMedium, color = Wp.colors.caution)
            Row(Modifier.fillMaxWidth().toggleable(sc.simulateOffline, role = Role.Switch) { sdk.controls.setSimulateOffline(it) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("模拟离线", style = MaterialTheme.typography.bodyLarge)
                    Text("AI 任务会进入“等待联网”，关闭后自动继续", style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted)
                }
                Switch(sc.simulateOffline, null)
            }
            Hairline()
            SectionTitle("AI 响应速度")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MockScenario.Latency.entries.forEachIndexed { i, l ->
                    SegmentedButton(sc.latency == l, { sdk.controls.setLatency(l) }, SegmentedButtonDefaults.itemShape(i, MockScenario.Latency.entries.size)) { Text(l.label) }
                }
            }
            Text("“慢”适合测试取消和切后台。", style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted, modifier = Modifier.padding(top = 6.dp))
            SectionTitle("下一次 AI 请求失败")
            val codes = listOf(null to "不失败", ErrorCode.ProviderTimeout to "超时", ErrorCode.ProviderRateLimited to "限流", ErrorCode.ProviderBadOutput to "格式错误", ErrorCode.ProviderTruncated to "输出截断")
            codes.forEach { (code, label) ->
                Row(Modifier.fillMaxWidth().toggleable(sc.failNextAi == code, role = Role.RadioButton) { sdk.controls.failNextAi(code) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(sc.failNextAi == code, null); Spacer(Modifier.width(8.dp)); Text(label)
                }
            }
            SectionTitle("学习日")
            OutlinedButton(onClick = { scope.launch { sdk.controls.nearBudget(); snackbar.showSnackbar("今天的用时已设为刚好不到预算") } }, modifier = Modifier.fillMaxWidth()) { Text("跳到接近预算") }
            OutlinedButton(onClick = { scope.launch { sdk.controls.newLearningDay(); snackbar.showSnackbar("已开始新的学习日") } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("开始新的学习日") }
            SectionTitle("数据")
            OutlinedButton(onClick = { scope.launch { sdk.controls.resetAll(); snackbar.showSnackbar("演示数据已重置") } }, modifier = Modifier.fillMaxWidth()) { Text("重置演示数据") }
        }
    }
}
