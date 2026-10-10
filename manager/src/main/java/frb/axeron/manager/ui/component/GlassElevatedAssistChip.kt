package frb.axeron.manager.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ChipElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import frb.axeron.manager.ui.theme.LocalGlassEdgeEnabled

/**
 * AxManagerD 的「升高型辅助芯片」外壳：与 Material3 的 `ElevatedAssistChip` 同名、同参数，
 * 区别只有一处 —— 在「自定义背景」（[LocalGlassEdgeEnabled]）开启时把芯片阴影压成 0。
 *
 * 为什么要做这个包装（真实反馈）：
 *  - 关闭自定义背景时，芯片容器色是不透明的（`SurfaceContainerLow` 原值），
 *    `ElevatedAssistChip` 默认的 1dp 阴影（`AssistChipTokens.ElevatedContainerElevation`）
 *    只出现在芯片外侧，是正常的悬浮投影；
 *  - 开启自定义背景后，`glassBackgroundColorScheme` 会把容器色换成**半透明白**
 *    （`glassFill()`），阴影绘制在芯片内容之下，容器不透明时被遮住，变半透明后
 *    就会从芯片内部（尤其内缘一圈）透出来，形成用户反馈的「里面一圈黑边」。
 *
 * 这与 [GlassElevatedCard.kt] 里 `glassSurfaceElevation()` 对卡片的处理是**同一套策略**：
 * 卡片早已压 0，唯独芯片没有对应包装 —— 而全工程只有「运行时模块」界面用到
 * `ElevatedAssistChip`（`RuntimeModulePanel.kt`），因此黑边只在该界面出现。
 *
 * 隔离性：
 *  - [LocalGlassEdgeEnabled] 为 false（默认）时，本包装与原生 `ElevatedAssistChip`
 *    行为完全一致（elevation 原样透传），保证未开启该设置时外观逐像素不变；
 *  - 本文件不改动 Material3 的任何参数语义，只转发。
 *
 * 参数与官方签名逐字对齐（依据 androidx material3 1.4.0
 * `commonMain/androidx/compose/material3/Chip.kt` 的 L293 `fun ElevatedAssistChip`）。
 */
@Composable
fun ElevatedAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = AssistChipDefaults.shape,
    colors: ChipColors = AssistChipDefaults.elevatedAssistChipColors(),
    elevation: ChipElevation? = AssistChipDefaults.elevatedAssistChipElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    androidx.compose.material3.ElevatedAssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = colors,
        elevation = glassChipElevation(elevation),
        border = border,
        interactionSource = interactionSource,
    )
}

/**
 * 芯片阴影的「自定义背景」隔离处理。
 *
 * 只在 [LocalGlassEdgeEnabled]（= 自定义背景开启）为 true 时，把六态阴影统一压成 0；
 * 为 false（默认）时**原样透传**调用方传入的 elevation，保证未开启该设置时的外观与改动前一致。
 *
 * 各状态都显式写 0（而非只改 defaultElevation），
 * 这样按下 / 聚焦 / 悬停 / 拖拽等状态也不会再冒出阴影。
 */
@Composable
private fun glassChipElevation(fallback: ChipElevation?): ChipElevation? =
    if (!LocalGlassEdgeEnabled.current) {
        fallback
    } else {
        AssistChipDefaults.elevatedAssistChipElevation(
            elevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            draggedElevation = 0.dp,
            disabledElevation = 0.dp,
        )
    }
