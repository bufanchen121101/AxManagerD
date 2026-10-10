package frb.axeron.manager.ui.component

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import frb.axeron.manager.ui.theme.LocalGlassEdgeEnabled

/**
 * 卡片「玻璃描边」：**只在卡片边框那一条线上**做白色渐变 + 由内向外递减的柔光，
 * 视觉上就是「边框是一条模糊的白色细线」。
 *
 * 实现要点（依据官方源码核对的 API）：
 *  - `Modifier.border(width: Dp, brush: Brush, shape: Shape)`（androidx.compose.foundation
 *    的公开重载，内部 `drawContent()` 之后按 shape 的 outline 描边，只画在边界上）；
 *  - 三层叠加：4dp 极淡 → 2dp 稍亮 → 1dp 实感白线；越靠里越亮，形成「模糊」的过渡，
 *    不依赖 `Modifier.blur`（blur 在 API 31 以下无效，且会把整层内容一起糊掉）。
 *  - 光源方向取左上 → 右下：左上最亮、中部最暗、右下回亮，模拟玻璃反光。
 *
 * 隔离性：
 *  - 只有 [LocalGlassEdgeEnabled]（= 自定义背景开启）为 true 时才生效；
 *  - 默认状态直接返回 `this`，**不产生任何绘制与布局影响**；
 *  - 不改变卡片内容、不遮挡文字、不修改任何布局尺寸。
 */
fun Modifier.glassEdge(shape: Shape): Modifier = composed {
    if (!LocalGlassEdgeEnabled.current) return@composed this

    this
        // 最外层：很宽、很淡 —— 「模糊」的扩散感来源
        .border(
            width = 4.dp,
            brush = sheen(
                Color.White.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.03f),
                Color.White.copy(alpha = 0.07f)
            ),
            shape = shape
        )
        // 中间层：过渡
        .border(
            width = 2.dp,
            brush = sheen(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.15f)
            ),
            shape = shape
        )
        // 最内层：那一条实感白线
        .border(
            width = 1.dp,
            brush = sheen(
                Color.White.copy(alpha = 0.90f),
                Color.White.copy(alpha = 0.30f),
                Color.White.copy(alpha = 0.65f)
            ),
            shape = shape
        )
}

/** 左上 → 右下的线性渐变（`end = Offset.Infinite` 表示取绘制区域的右下角）。 */
private fun sheen(edge: Color, middle: Color, far: Color): Brush = Brush.linearGradient(
    colors = listOf(edge, middle, far),
    start = Offset.Zero,
    end = Offset.Infinite,
)