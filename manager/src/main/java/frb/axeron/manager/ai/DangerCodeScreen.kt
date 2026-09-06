package frb.axeron.manager.ai

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.api.ai.CommandAnalyzer.AnalyzeResult.Risk
import frb.axeron.api.ai.RuleEngine
import frb.axeron.manager.ui.component.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 危险代码库（第二阶段）。
 *
 * 功能：
 * - 导出：把用户自定义规则（RuleEngine extraRules）序列化为 JSON 写入 Download 目录
 * - 导入：通过 SAF 选 JSON 文件，解析后 addRule 到 RuleEngine
 * - 查看/删除已导入的自定义规则
 *
 * JSON 字段：name / pattern / risk（SAFE/LOW/MEDIUM/HIGH）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DangerCodeScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 触发刷新规则列表
    var refreshKey by remember { mutableStateOf(0) }
    val extraRules = remember(refreshKey) { RuleEngine.getExtraRules() }

    val scope = rememberCoroutineScope()

    // 导入 SAF
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                importRules(context, uri) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (success) refreshKey++
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "危险代码库",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 内置危险代码库开关
            SettingsItem(
                iconVector = Icons.Filled.VerifiedUser,
                label = "内置危险代码库",
                description = "启用软件内置的危险规则库（默认开启）。关闭时仅使用自定义导入的规则。",
                checked = RuleEngine.isBuiltinEnabled(),
                onSwitchChange = { RuleEngine.setBuiltinEnabled(it) },
            )

            // 导入
            SettingsItem(
                iconVector = Icons.Filled.Upload,
                label = "导入规则",
                description = "从 JSON 文件导入自定义危险规则",
                onClick = { importLauncher.launch("application/json") },
            )

            // 导出
            SettingsItem(
                iconVector = Icons.Filled.Download,
                label = "导出规则",
                description = "将自定义规则导出为 JSON 文件到 Download",
                onClick = {
                    scope.launch {
                        val msg = exportRules(context)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )

            // 规则列表
            Text(
                text = "已导入规则（${extraRules.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (extraRules.isEmpty()) {
                Text(
                    text = "暂无自定义规则",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                extraRules.forEach { rule ->
                    RuleItem(
                        name = rule.name,
                        pattern = rule.pattern.pattern,
                        risk = rule.risk,
                        onDelete = {
                            RuleEngine.removeExtraRule(rule.name)
                            refreshKey++
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleItem(
    name: String,
    pattern: String,
    risk: Risk,
    onDelete: () -> Unit,
) {
    val riskColor = riskColor(risk)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = risk.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = riskColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = pattern,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun riskColor(risk: Risk): Color = when (risk) {
    Risk.SAFE -> Color(0xFF4CAF50)
    Risk.LOW -> Color(0xFFFFEB3B)
    Risk.MEDIUM -> Color(0xFFFF9800)
    Risk.HIGH -> Color(0xFFF44336)
}

/** 序列化 extraRules 为 JSON 字符串并写入 Download 目录，返回提示文案。 */
suspend fun exportRules(context: android.content.Context): String = withContext(Dispatchers.IO) {
    try {
        val rules = RuleEngine.getExtraRules()
        if (rules.isEmpty()) return@withContext "没有可导出的自定义规则"

        val arr = JSONArray()
        rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("name", rule.name)
            obj.put("pattern", rule.pattern.pattern)
            obj.put("risk", rule.risk.name)
            arr.put(obj)
        }

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadDir, "danger_rules.json")
        file.writeText(arr.toString(2))

        "已导出 ${rules.size} 条规则到 ${file.absolutePath}"
    } catch (e: Exception) {
        "导出失败：${e.message}"
    }
}

/** 从 Uri 读取 JSON 并导入规则，回调 (success, message)。 */
suspend fun importRules(
    context: android.content.Context,
    uri: Uri,
    onResult: (Boolean, String) -> Unit,
) = withContext(Dispatchers.IO) {
    try {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return@withContext onResult(false, "无法读取文件")

        val arr = JSONArray(text)
        var count = 0
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name")
            val pattern = obj.optString("pattern")
            val riskName = obj.optString("risk", "MEDIUM")
            if (name.isBlank() || pattern.isBlank()) continue
            val risk = try {
                Risk.valueOf(riskName)
            } catch (e: IllegalArgumentException) {
                Risk.MEDIUM
            }
            RuleEngine.addRule(RuleEngine.Rule(name, Regex(pattern), risk))
            count++
        }
        onResult(true, "成功导入 $count 条规则")
    } catch (e: Exception) {
        onResult(false, "导入失败：${e.message}")
    }
}