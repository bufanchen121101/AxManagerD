package frb.axeron.manager.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.viewmodel.ActivateViewModel
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal

/**
 * 【v1.6.0】DP 差异化提权界面。
 *
 * 从「激活」页的「DP 差异化激活」卡片跳转进入，自动执行：
 * ```
 * ① 检查 Shizuku 通道（仅 DP Role 授予需要 shell 身份）
 * ② 授予 DP Role
 * ③ 非 ADB 直连：forceUpdateUserSetupComplete → setActiveAdmin → setDeviceOwner
 *    ★ 应用自身身份，命中非 ADB 分支 → **不清账户、不删隐藏账号**
 * ④ 汇总结果
 * ```
 *
 * ## 与旧版（v1.4.8）的区别
 *
 * 旧版走 `ActivateViewModel.runElevateFlow()`：Shizuku 以 shell(ADB) 身份执行
 * `dpm set-device-owner`，命中 ADB 分支，**必须清空账户**，否则失败。
 * 新版走 `ActivateViewModel.runElevateDirectFlow()`：应用自身身份直连 binder，
 * 命中非 ADB 分支，只受 `hasUserSetupCompleted` 一道闸（已被第 ③ 步反向覆盖）。
 *
 * A13→A17 五版本源码取证见 `AxManagerD_AOSP_13to17_DP差异化取证.md`。
 *
 * ## 界面构成
 * - 顶部：[ASCII_LOGO] —— 用纯 ASCII 半角字符（`/` `\` `_`）拼出的品牌大字
 * - 中部：提权进度提示 + 实时命令输出（可滚动、自动滚底）
 * - 底部右下角：成功显示旋转的 Refresh 图标（与安装模块后同一个图标，
 *   点击返回）；失败显示叉号
 *
 * ## 稳定性约束（v1.4.8 闪退修复，沿用）
 * 流程**不**跑在 `LaunchedEffect` 里，而是由 `ActivateViewModel.startElevateDirectFlow()`
 * 在 `viewModelScope` 上执行。见该方法注释。
 */
/**
 * 【v1.5.0】顶部品牌大字。
 *
 * ## 为什么改写
 *
 * 旧实现（v1.4.8）用的是「斜杠屋顶 + 半角块字符 ▄▀█ + 全角框线」的**混合字符画**，
 * 真机（一加 ACE 6T / ColorOS）实测显示错位、右半边塌陷。原因：
 *  · 混用了三类宽度体系完全不同的字符：ASCII 半角（`/` `\` `_`）、
 *    Unicode Block Elements（`▄▀█`）、CJK 全角框线；
 *  · Android 上 `FontFamily.Monospace` 只保证 ASCII 半角等宽，块字符会
 *    回落到其他字体，宽度 ≠ 半角 → 逐行错位累积；
 *  · 原始字符串里的行尾尾随空格被一并保留，配合 `TextAlign.Center`
 *    导致每行视觉起点不一致。
 *
 * ## 现在
 *
 * 全部改用**纯 ASCII 半角字符**（`/` `\` `_` `|` `-`）拼写的标准 figlet 大字，
 * 只有这一种宽度体系 → 在 `FontFamily.Monospace` 下**逐行严格对齐**；
 * 同时**删除所有行尾空格**，避免居中偏斜。
 *
 * 配套（见下方 RenderLogo）：
 *  · `softWrap = false` —— 禁止窄屏自动折行（折行会彻底毁掉图形）；
 *  · 外层 `horizontalScroll` —— 屏幕过窄时改为横向滚动，图形本身不变形。
 */
private const val ASCII_LOGO = """     _     __  __ __  __     _      _   _      _       ____   _____  ____    ____
    / \    \ \/ / |  \/  |   / \    | \ | |    / \     / ___| | ____| |  _ \  |  _ \
   / _ \    \  /  | |\/| |  / _ \   |  \| |   / _ \   | |  _  |  _|   | |_) | | | | |
  / ___ \   /  \  | |  | | / ___ \  | |\  |  / ___ \  | |_| | | |___  |  _ <  | |_| |
 /_/   \_\ /_/\_\ |_|  |_|/_/   \_\ |_| \_| /_/   \_\  \____| |_____| |_| \_\ |____/"""

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun ElevateScreen(navigator: DestinationsNavigator, viewModelGlobal: ViewModelGlobal) {
    val vm = viewModelGlobal.activateViewModel

    // 【v1.4.8 闪退修复】不再在此处直接跑流程。
    // 旧写法 `LaunchedEffect(Unit) { vm.runElevateFlow() }` 会让长任务绑定在
    // Composable 生命周期上：用户一切后台/返回，协程被取消而底层命令仍在执行，
    // 随后回写已销毁的状态 → 高版本 Android 直接闪退。
    // 现在只做「状态重置 + 触发」，流程本身由 ViewModel 托管。
    //
    // 【v1.6.1】本界面由两张卡片共用，按 ViewModel 中的模式分流：
    //   · ELEVATE_MODE_DP_SHIZUKU（1）：卡片 1 → runElevateFlow()（先试激活，
    //     失败则删 999 隐藏用户 + 只冻结非系统账户应用后重试）
    //   · ELEVATE_MODE_DIRECT（2）    ：卡片 2 → runElevateDirectFlow()（非 ADB 直连）
    LaunchedEffect(Unit) {
        if (vm.elevatePhase != 1) {
            vm.resetElevateState()
            when (vm.elevateMode) {
                ActivateViewModel.ELEVATE_MODE_DIRECT -> vm.startElevateDirectFlow()
                else -> vm.startElevateFlow()
            }
        }
    }

    val isDirectMode = vm.elevateMode == ActivateViewModel.ELEVATE_MODE_DIRECT

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isDirectMode) "提权中（直连）" else "提权中（DP + Shizuku）",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
        ) {
            // ── ASCII 大字 Logo ──
            //
            // 【v1.5.0】渲染约束（配合上方 ASCII_LOGO 的改动）：
            //  · `softWrap = false`：禁止窄屏自动折行 —— 一旦折行，字符画被
            //    拦腰截断，视觉上就是「错位/塌陷」，这是旧版最明显的 bug 来源；
            //  · 外层 `horizontalScroll`：屏幕不足时改为**横向滚动**，
            //    保证每一行的字符相对位置绝对不变，图形始终完整；
            //  · 字号从 7sp 下调到 6sp：新字画是标准 figlet（约 84 列），
            //    6sp 下在主流手机屏宽内可完整显示，无需滚动。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val logoHScroll = rememberScrollState()
                    Text(
                        text = ASCII_LOGO,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 6.sp,
                        lineHeight = 7.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        softWrap = false,
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(logoHScroll),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 状态提示 ──
            val statusText = when (vm.elevatePhase) {
                1 -> "正在提权中，请勿离开 ..."
                2 -> "提权全部成功"
                3 -> "提权失败"
                else -> "准备中 ..."
            }
            val statusColor = when (vm.elevatePhase) {
                1, 2 -> MaterialTheme.colorScheme.primary
                3 -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                )
                if (vm.elevatePhase == 1) {
                    Spacer(Modifier.size(8.dp))
                    SpinningIndicator()
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 【v1.7.0】原「三键导航会失灵」红色警告条已移除 ──
            //
            // 背景：v1.6.6/v1.6.7 期间，卡片 2「非 ADB 直连」激活流程会**自动**
            // 临时把 user_setup_complete 置 0，期间三键导航的 Home / 最近任务键会失灵，
            // 故此处渲染一条红色危险提示。
            //
            // v1.7.0 起，置 0 已移出激活流程、改由卡片上的「第一步：准备」按钮手动完成，
            // 激活过程本身不再让导航键失灵，该警告条失去必要性，按用户要求整体删除。

            // ── 实时输出 ──
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            ) {
                // 【v1.4.9 闪退修复】输出框的滚动位置**不参与状态保存**。
                //
                // 用 rememberSaveable 时，Compose 会把「这个滚动位置所属的可保存
                // 子树」整体登记进 SaveableStateRegistry；配合下方长文本渲染，
                // 在 onSaveInstanceState 阶段会被一起打包 → 实测崩溃时
                // BundlableSavedStateRegistry 单条目达 531KB，直接触发
                // TransactionTooLargeException 崩进程（Android 15，PID 28599）。
                //
                // 这里改用普通 remember：滚动位置丢失无关紧要（每次进入都是新页面），
                // 但能确保提权界面不再往保存状态里塞任何大对象。
                val scroll = remember { ScrollState(0) }
                // 输出追加时自动滚到底部
                LaunchedEffect(vm.elevateLog.length) {
                    runCatching { scroll.animateScrollTo(scroll.maxValue) }
                }
                Text(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(scroll),
                    text = vm.elevateLog.ifBlank { "> 初始化 ..." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // ── 失败原因 ──
            if (vm.elevatePhase == 3 && vm.elevateError != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        modifier = Modifier.padding(12.dp),
                        text = vm.elevateError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            // ── 右下角结果图标 ──
            if (vm.elevatePhase == 2 || vm.elevatePhase == 3) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    val success = vm.elevatePhase == 2
                    // 【v1.4.8】成功态不再用硬编码绿色，跟随主题色（primary）。
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (success) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(52.dp),
                        onClick = { navigator.popBackStack() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (success) {
                                // 【v1.6.1】成功态：静态 Refresh 图标（**不再旋转**）。
                                // 旧版此处为 SpinningIcon() 无限旋转，视觉上像是在「继续加载」，
                                // 与「已完成、可返回」的语义不符，故改为静止图标。
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "返回",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "失败",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 旋转加载指示器（提权进行中）。 */
@Composable
private fun SpinningIndicator() {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    Text(
        text = "◌",
        modifier = Modifier.rotate(angle),
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 成功图标：旋转的 [Icons.Filled.Refresh]。
 *
 * 【v1.6.1 已废弃】成功态改为**静态** Refresh 图标（见上方结果图标处），
 * 因为无限旋转在「已完成」语义下会误导用户以为仍在加载。
 * 本函数暂时保留（不删除）以防后续需要，但当前无调用点。
 */
@Suppress("unused")
@Composable
private fun SpinningIcon() {
    val transition = rememberInfiniteTransition(label = "spinIcon")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "iconAngle",
    )
    Icon(
        imageVector = Icons.Filled.Refresh,
        contentDescription = "返回",
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.rotate(angle),
    )
}