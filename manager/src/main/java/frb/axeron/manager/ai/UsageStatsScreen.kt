package frb.axeron.manager.ai

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.component.LocalBottomBarInset
import frb.axeron.manager.ai.axkey.AxKeyProvider
import frb.axeron.manager.ui.util.LocalSnackbarHost
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用量与性能（1.4.0）。
 *
 * 本机用量不联网、不上报、不写外部存储；官方额度仅从自有中转站同步。
 *
 * 版式（对齐 Operit 用量页的思路）：
 *  1. 顶部总览：调用次数做主视觉，输入/输出 Token 分列对比
 *  2. 近 7 日趋势：柱状图，今天那根用主色高亮
 *  3. 输入/输出占比：一条双色比例条
 *  4. 运行状态：堆内存、系统内存、存储可用，带进度条
 *  5. 隐私清理：Cookie / WebStorage
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun UsageStatsScreen(navigator: DestinationsNavigator) {
    UsageStatsBody(navigator = navigator)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageStatsBody(navigator: DestinationsNavigator) {
    val ctx = LocalContext.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val bottomInset = LocalBottomBarInset.current
    var perf by remember { mutableStateOf(PerfSnapshot.empty()) }
    var perfLoaded by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        perf = readPerf(ctx)
        perfLoaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用量与性能") },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { perf = readPerf(ctx) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { pv ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pv),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottomInset + 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { DeviceQuotaCard() }
            item { ApiSplitCard(official = true) }
            item { ApiSplitCard(official = false) }
            item { UsageOverviewCard() }
            item { DailyTrendCard() }
            item { RatioCard() }
            item { PerfCard(perf, perfLoaded) }
            item {
                PrivacyCard(
                    onClear = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    CookieManager.getInstance().removeAllCookies(null)
                                    CookieManager.getInstance().flush()
                                    WebStorage.getInstance().deleteAllData()
                                    true
                                } catch (t: Throwable) {
                                    false
                                }
                            }
                            snackbar.showSnackbar(if (ok) "已清除 Cookie 与本地存储" else "清除失败，请重试")
                        }
                    },
                )
            }
            item { ResetRow { AIConfigStore.resetUsage() } }
            item { UsageFooter() }
        }
    }
}

/** 官方设备的云端月度额度；样式与本页其它统计卡一致。 */
@Composable
private fun DeviceQuotaCard() {
    val scheme = MaterialTheme.colorScheme
    var quota by remember { mutableStateOf(AxKeyProvider.quotaState()) }
    var syncing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        syncing = true
        quota = AxKeyProvider.refreshQuota()
        syncing = false
    }
    val monthText = if (quota.month > 0) {
        "${quota.month / 100}-${String.format(Locale.US, "%02d", quota.month % 100)}"
    } else {
        ""
    }
    StatCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = scheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "官方 AI 月度额度",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (syncing) "同步中…" else monthText,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (quota.available) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已用 ${compactNumber(quota.used)} / ${compactNumber(quota.limit)} tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = percent(quota.used.toFloat() / quota.limit.coerceAtLeast(1L).toFloat()),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = {
                    (quota.used.toFloat() / quota.limit.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = scheme.primary,
                trackColor = scheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "本月剩余 ${compactNumber(quota.remaining)} tokens",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (quota.remaining > 0L) scheme.primary else scheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "按设备独立计算，每月自动重置。",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        } else {
            Text(
                text = "尚未连接官方 AI。请先在「AI 引擎 → 连接官方 AI（中转站）」完成序列号校验。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 总览：调用次数做主视觉，输入/输出两列对比。 */
@Composable
private fun UsageOverviewCard() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(listOf(scheme.primaryContainer, scheme.secondaryContainer)),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(20.dp),
    ) {
        Column {
            Text(
                text = "累计调用",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = AIConfigStore.statCalls.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "次",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TokenStat(
                    label = "输入",
                    tokens = AIConfigStore.statPromptTokens,
                    chars = AIConfigStore.statPromptChars,
                    modifier = Modifier.weight(1f),
                )
                TokenStat(
                    label = "输出",
                    tokens = AIConfigStore.statReplyTokens,
                    chars = AIConfigStore.statReplyChars,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TokenStat(label: String, tokens: Long, chars: Long, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface.copy(alpha = 0.40f),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "$label tokens",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = compactNumber(tokens),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onPrimaryContainer,
            )
            Text(
                text = "${compactNumber(chars)} 字符",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

/** 近 7 日柱状图：今天高亮，其余半透明。 */
@Composable
private fun DailyTrendCard() {
    val scheme = MaterialTheme.colorScheme
    // 【1.4.2】固定展示最近 10 天：缺失的日子补 0，柱图始终 10 根，
    // 不会出现「整屏只有一根孤零零柱子」的空数据画面。
    val days = fillRecentDays(
        n = 10,
        source = AIConfigStore.statDaily,
        key = { it.date },
        empty = { AIConfigStore.DailyUsage(it, 0L, 0L) },
    )
    val maxTok = (days.maxOfOrNull { it.tokens } ?: 0L).coerceAtLeast(1L)
    val totalWeek = days.sumOf { it.tokens }

    StatCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "近 10 日趋势",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${compactNumber(totalWeek)} tokens",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEachIndexed { idx, d ->
                val frac = (d.tokens.toFloat() / maxTok.toFloat()).coerceIn(0f, 1f)
                // 没有用量也给一根「最矮柱」，读图时能看出这天存在
                val barHeight = (8f + 66f * frac).dp
                val isToday = idx == days.lastIndex && d.date.isNotBlank()
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        text = if (d.tokens > 0) compactNumber(d.tokens) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(barHeight)
                            .background(
                                color = if (isToday) scheme.primary else scheme.primary.copy(alpha = 0.42f),
                                shape = RoundedCornerShape(6.dp),
                            ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (d.date.isBlank()) "—" else d.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) scheme.primary else scheme.onSurfaceVariant,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (totalWeek <= 0L) {
            Text(
                text = "还没有记录：发起一次对话后，这里会按天累计。",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

/** 输入/输出占比：一条双色比例条。 */
@Composable
private fun RatioCard() {
    val scheme = MaterialTheme.colorScheme
    val pin = AIConfigStore.statPromptTokens
    val pout = AIConfigStore.statReplyTokens
    val sum = (pin + pout).coerceAtLeast(1L)
    val inFrac = pin.toFloat() / sum.toFloat()

    StatCard {
        Text(
            text = "输入 / 输出占比",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(inFrac.coerceAtLeast(0.001f))
                    .fillMaxSize()
                    .background(scheme.primary, RoundedCornerShape(5.dp)),
            )
            Spacer(Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .weight((1f - inFrac).coerceAtLeast(0.001f))
                    .fillMaxSize()
                    .background(scheme.tertiary, RoundedCornerShape(5.dp)),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(scheme.primary)
            Text(
                text = "输入 ${percent(inFrac)}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            LegendDot(scheme.tertiary)
            Text(
                text = "输出 ${percent(1f - inFrac)}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(4.dp)),
    )
    Spacer(Modifier.width(5.dp))
}

@Composable
private fun PerfCard(perf: PerfSnapshot, loaded: Boolean) {
    val scheme = MaterialTheme.colorScheme
    StatCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "运行状态",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (loaded) "本机实时读取" else "读取中…",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        PerfBar(
            label = "应用堆内存",
            valueText = "${perf.heapUsedMb} / ${perf.heapMaxMb} MB",
            fraction = if (perf.heapMaxMb > 0) perf.heapUsedMb.toFloat() / perf.heapMaxMb.toFloat() else 0f,
            color = scheme.primary,
        )
        Spacer(Modifier.height(14.dp))
        PerfBar(
            label = "系统可用内存",
            valueText = "${perf.sysAvailMb} / ${perf.sysTotalMb} MB",
            fraction = if (perf.sysTotalMb > 0) perf.sysAvailMb.toFloat() / perf.sysTotalMb.toFloat() else 0f,
            color = scheme.tertiary,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "数据分区可用",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format(Locale.US, "%.2f GB", perf.storageFreeGb),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "应用线程数",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = perf.threads.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
        if (perf.lowMemory) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "系统处于低内存状态",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
            )
        }
    }
}

@Composable
private fun PerfBar(label: String, valueText: String, fraction: Float, color: androidx.compose.ui.graphics.Color) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = color,
            trackColor = scheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
    }
}

@Composable
private fun PrivacyCard(onClear: () -> Unit) {
    StatCard {
        Text(
            text = "隐私与数据清理",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "清除 Cookie 与网页本地存储（搜索工具、Browser 包、内置浏览器共用）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("立即清除")
        }
    }
}

@Composable
private fun ResetRow(onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "统计只保存在本机，可随时清零",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReset) { Text("清零统计") }
    }
}

@Composable
private fun UsageFooter() {
    Text(
        text = "本机 Token 按「字符数 ÷ 4」估算；官方额度由中转站按实际用量计算。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun StatCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/* ---------------- 数据 ---------------- */

private data class PerfSnapshot(
    val heapUsedMb: Int,
    val heapMaxMb: Int,
    val sysAvailMb: Int,
    val sysTotalMb: Int,
    val storageFreeGb: Double,
    val threads: Int,
    val lowMemory: Boolean,
) {
    companion object {
        fun empty() = PerfSnapshot(0, 0, 0, 0, 0.0, 0, false)
    }
}

private fun readPerf(context: Context): PerfSnapshot {
    var heapUsed = 0
    var heapMax = 0
    var threads = 0
    try {
        val rt = Runtime.getRuntime()
        heapUsed = ((rt.totalMemory() - rt.freeMemory()) / 1024 / 1024).toInt()
        heapMax = (rt.maxMemory() / 1024 / 1024).toInt()
        threads = Thread.activeCount()
    } catch (t: Throwable) {
        // 读取失败保持 0，界面显示 0 / 0
    }
    var avail = 0
    var total = 0
    var low = false
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        avail = (info.availMem / 1024 / 1024).toInt()
        total = (info.totalMem / 1024 / 1024).toInt()
        low = info.lowMemory
    } catch (t: Throwable) {
        // ignore
    }
    var freeGb = 0.0
    try {
        val sf = StatFs(context.filesDir.absolutePath)
        freeGb = sf.availableBlocksLong * sf.blockSizeLong / 1024.0 / 1024.0 / 1024.0
    } catch (t: Throwable) {
        // ignore
    }
    return PerfSnapshot(heapUsed, heapMax, avail, total, freeGb, threads, low)
}

private fun compactNumber(v: Long): String = when {
    v >= 1_000_000L -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
    v >= 1_000L -> String.format(Locale.US, "%.1fk", v / 1_000.0)
    else -> v.toString()
}

private fun percent(f: Float): String = String.format(Locale.US, "%.0f%%", f.coerceIn(0f, 1f) * 100f)

/**
 * 【1.4.2】按来源拆分的用量卡：官方默认 API / 自定义 API。
 *
 * 两张卡结构完全一致，只是取数桶与配色不同——这样用户能一眼看出
 * 「额度被谁用掉了」，而不是只有一个混在一起的总数。
 */
@Composable
private fun ApiSplitCard(official: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val stat = AIConfigStore.apiStat(official)
    val accent = if (official) scheme.primary else scheme.tertiary
    StatCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (official) Icons.Filled.Cloud else Icons.Filled.Settings,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (official) "官方默认 API" else "自定义 API",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${stat.calls} 次调用",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (official) {
                "中转站 · 设备序列号密钥（每月额度）"
            } else {
                "「云端模型配置」里填写的网址 / Key / 模型"
            },
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ApiMiniStat(
                label = "输入",
                tokens = stat.promptTokens,
                chars = stat.promptChars,
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            ApiMiniStat(
                label = "输出",
                tokens = stat.replyTokens,
                chars = stat.replyChars,
                accent = accent,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        // 固定 10 根：没记录的那天也画最矮的一根，空白画面看着不像「坏了」
        val days10 = fillRecentDays(
            n = 10,
            source = stat.daily,
            key = { it.date },
            empty = { AIConfigStore.ApiDay(it, 0L, 0L, 0L) },
        )
        val maxTok = (days10.maxOfOrNull { it.tokens } ?: 0L).coerceAtLeast(1L)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days10.forEachIndexed { idx, d ->
                val frac = (d.tokens.toFloat() / maxTok.toFloat()).coerceIn(0f, 1f)
                val isToday = idx == days10.lastIndex
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height((5f + 32f * frac).dp)
                            .background(
                                color = if (isToday) accent else accent.copy(alpha = 0.40f),
                                shape = RoundedCornerShape(5.dp),
                            ),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (d.date.isBlank()) {
                            "—"
                        } else {
                            d.label.substringAfter("-").trimStart('0').ifBlank { "0" }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** 最近 n 天的日期键（yyyy-MM-dd，升序，最后一个是今天）。 */
private fun recentDayKeys(n: Int): List<String> {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val cal = java.util.Calendar.getInstance()
    val keys = ArrayList<String>(n)
    for (i in 0 until n) {
        keys.add(fmt.format(cal.time))
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    keys.reverse()
    return keys
}

/**
 * 把稀疏的按天记录对齐成「连续 n 天」，缺失的天用 [empty] 补 0。
 *
 * 为什么必须补：柱图是按列表长度画柱子的，缺天就少根柱子，
 * 空数据时只剩一根孤柱，看起来像界面坏了。
 */
private fun <T> fillRecentDays(
    n: Int,
    source: List<T>,
    key: (T) -> String,
    empty: (String) -> T,
): List<T> {
    if (n <= 0) return emptyList()
    val byDay = source.associateBy(key)
    return recentDayKeys(n).map { k -> byDay[k] ?: empty(k) }
}

/** 来源卡里的小统计块：token 主数值 + 字符数副标题。 */
@Composable
private fun ApiMiniStat(
    label: String,
    tokens: Long,
    chars: Long,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "$label tokens",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = compactNumber(tokens),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Text(
                text = "${compactNumber(chars)} 字符",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}
