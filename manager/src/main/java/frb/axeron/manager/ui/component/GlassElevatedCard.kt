package frb.axeron.manager.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import frb.axeron.manager.ui.theme.LocalGlassEdgeEnabled

/**
 * AxManagerD 的卡片外壳：与 Material3 的 `ElevatedCard` 同名、同参数（额外多一个 [withGlassEdge]），
 * 区别只有一处 —— 自动把 [glassEdge] 描边加到卡片自己的 modifier 上。
 *
 * 为什么要「同名包装」而不是逐个调用点手改：
 *  - 全工程有 34 处 `ElevatedCard(`，逐处手改容易漏、容易把 shape 对应错；
 *  - 同包同名后，`frb.axeron.manager.ui.component` 包内的文件自动使用本实现，
 *    其他包只需把 `import androidx.compose.material3.ElevatedCard` 换成
 *    `import frb.axeron.manager.ui.component.ElevatedCard`（仅一行）；
 *  - 将来新增的卡片也自动获得同样的外观，不会出现「有的卡片有描边、有的没有」。
 *
 * 隔离性：
 *  - 描边本身在 [LocalGlassEdgeEnabled] 为 false（默认）时直接返回原 Modifier，
 *    所以**未开启自定义背景时，本包装与原生 `ElevatedCard` 行为完全一致**；
 *  - 本文件不改动 Material3 的任何参数语义，只转发。
 *
 * 参数与官方签名逐字对齐（依据 androidx-main
 * `compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Card.kt`
 * 的 L196 / L246 两处 `fun ElevatedCard`）。
 */
@Composable
fun ElevatedCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(),
    // 是否给这张卡片加玻璃描边。设置页里的「子项卡片」（CHILD，直角、与父卡连成一体）传 false。
    withGlassEdge: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ElevatedCard(
        modifier = if (withGlassEdge) modifier.glassEdge(shape) else modifier,
        shape = shape,
        colors = colors,
        elevation = glassSurfaceElevation(elevation),
        content = content,
    )
}

/** 可点击版本，参数与官方可点击重载一一对应（多一个 [withGlassEdge]）。 */
@Composable
fun ElevatedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(),
    withGlassEdge: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ElevatedCard(
        onClick = onClick,
        modifier = if (withGlassEdge) modifier.glassEdge(shape) else modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = glassSurfaceElevation(elevation),
        content = content,
    )
}

/**
 * 卡片阴影的「自定义背景」隔离处理。
 *
 * 背景：
 *  - 关闭自定义背景时，卡片容器色是不透明的（surfaceContainerLow 原值），
 *    `ElevatedCard` 默认的 1dp 阴影（`ElevatedCardTokens.ContainerElevation = Level1`）
 *    只会出现在卡片外侧，是正常的悬浮投影；
 *  - 开启自定义背景后，`glassBackgroundColorScheme` 会把容器色换成**半透明白**
 *    （`glassFill()`，alpha 约 0.22）。而阴影是绘制在卡片内容**之下**的一层暗色，
 *    容器不透明时被完全遮住，变半透明后就会从卡片内部（尤其内缘一圈）透出来，
 *    形成用户反馈的「圆角卡片里面一圈黑边」。
 *
 * 因此本函数只在 [LocalGlassEdgeEnabled]（= 自定义背景开启）为 true 时，
 * 把阴影统一压成 0；为 false（默认）时**原样透传**调用方传入的 elevation，
 * 保证未开启该设置时的外观与改动前逐像素一致。
 *
 * 各状态都显式写 0（而非依赖 `FilledCardTokens` 的默认值），
 * 这样按下 / 悬停 / 拖拽等状态也不会再冒出阴影。
 */
@Composable
private fun glassSurfaceElevation(fallback: CardElevation): CardElevation =
    if (!LocalGlassEdgeEnabled.current) {
        fallback
    } else {
        CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            draggedElevation = 0.dp,
            disabledElevation = 0.dp
        )
    }