package app.wordprism.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.wordprism.sdk.ApiModel
import app.wordprism.ui.theme.Wp

@Composable
internal fun ModelPickerDialog(models: List<ApiModel>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(models, search) {
        val query = search.trim()
        models.filter { it.id.contains(query, ignoreCase = true) || it.ownedBy?.contains(query, ignoreCase = true) == true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("列表由服务商提供；是否支持当前协议，可在保存后测试连接。", style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted)
                OutlinedTextField(search, { search = it }, singleLine = true, label = { Text("搜索模型") }, modifier = Modifier.fillMaxWidth())
                Text("${filtered.size} / ${models.size} 个模型", style = MaterialTheme.typography.labelMedium, color = Wp.colors.muted)
                if (filtered.isEmpty()) Text("没有匹配的模型", modifier = Modifier.padding(vertical = 16.dp))
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(filtered, key = { it.id }) { model ->
                        ListItem(
                            headlineContent = { Text(model.id) },
                            supportingContent = model.ownedBy?.let { owner -> { Text(owner) } },
                            trailingContent = { RadioButton(selected = model.id == selected, onClick = null) },
                            modifier = Modifier.selectable(selected = model.id == selected, role = Role.RadioButton, onClick = { onSelect(model.id) }),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
