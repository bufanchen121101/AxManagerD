package frb.axeron.manager.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.api.ai.CommandAnalyzer.AnalyzeResult.Risk
import frb.axeron.api.ai.RuleEngine

/**
 * AI 参考文档界面（Bug7）。
 *
 * 集中、只读地展示三类与 AI 分析相关的「底层资料」，方便用户查阅与核对：
 *  1. AI 底层提示词（身份/环境/任务/最高权限声明，即每次对话注入的 system prompt）
 *  2. 参考代码库（内置危险规则 BUILTIN_RULES，AI 规则引擎实际匹配的依据）
 *  3. 危险代码库（用户自定义导入的规则 extraRules）
 *
 * 设计目标：全部内容一页可滚到底、分区块清晰、规则以「名称 + 风险 + 正则」逐条列出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AiReferenceScreen(navigator: DestinationsNavigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 底层提示词（静态文本，仅生成一次）
    val systemPrompt = remember { AIEnvironment.systemDeclaration() }
    val builtinRules = remember { RuleEngine.getBuiltinRules() }
    val extraRules = remember { RuleEngine.getExtraRules() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI 参考文档",
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReferenceSectionTitle("1. AI 底层提示词")
            SectionHint("这是每次与云端 AI 对话时，自动注入到最前面的 system 提示词（决定 AI 的身份、任务与权限认知）。")
            CodeBlock(systemPrompt)

            ReferenceSectionTitle("2. 参考代码库（内置危险规则）")
            SectionHint("规则引擎实际匹配的「内置参考代码库」，共 ${builtinRules.size} 条。命中即会参与风险判定。")
            RuleList(builtinRules)

            ReferenceSectionTitle("3. 危险代码库（自定义规则）")
            SectionHint("用户自行导入的危险规则，共 ${extraRules.size} 条。与内置规则一起参与匹配。")
            if (extraRules.isEmpty()) {
                SectionHint("暂无自定义规则。可在「危险代码库」界面导入 JSON 规则。")
            } else {
                RuleList(extraRules)
            }

            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ReferenceSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SectionHint(hint: String) {
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 纯文本代码块（等宽字体 + 浅色底），适合展示提示词。 */
@Composable
private fun CodeBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** 规则列表：逐条以「名称 + 风险徽标 + 正则」展示，便于逐行阅读。 */
@Composable
private fun RuleList(rules: List<RuleEngine.Rule>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rules.forEachIndexed { index, rule ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${index + 1}. ${rule.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        RiskBadge(rule.risk)
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = rule.pattern.pattern,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(risk: Risk) {
    val (label, color) = when (risk) {
        Risk.SAFE -> "安全" to Color(0xFF4CAF50)
        Risk.LOW -> "低" to Color(0xFFFFB300)
        Risk.MEDIUM -> "中" to Color(0xFFFF9800)
        Risk.HIGH -> "高" to Color(0xFFF44336)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.18f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
