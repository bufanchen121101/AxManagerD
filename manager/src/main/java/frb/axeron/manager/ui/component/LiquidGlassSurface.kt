package frb.axeron.manager.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import frb.axeron.manager.ui.theme.LocalLiquidGlass

/**
 * 液态玻璃面板（Haze 方案）。
 *
 * 关键点（吸取第一版教训）：
 *  - 用 Haze 的 hazeEffect 作用在「栏/控件自身」上，Haze 会去采样它背后
 *    的 hazeSource 内容并模糊，因此只有栏背后透出的内容被虚化；
 *  - 栏上的图标 / 文字是栏自己的 child，绘制在模糊层之上，始终 100% 清晰；
 *  - 绝不使用 Modifier.blur 去糊内容区，避免「整页糊住不可用」。
 *
 * @param hazeState 由外层 rememberHazeState() 创建，并挂到内容区的 hazeSource 上
 */
@Composable
fun LiquidGlassPanel(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    blurRadius: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    interactive: Boolean = false,
    glow: Float = 1f,
    dispersion: Float = 1f,
    /** true 时按「椭圆」绘制边缘高光/色散（用于水滴光斑），false 时按形状 outline 绘制。 */
    ellipse: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val params = LocalLiquidGlass.current
    val enabled = params.enabled

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // 液态按压反馈：轻微缩放
    val scale by animateFloatAsState(
        targetValue = if (interactive && enabled && pressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "liquidGlassPress"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            blurRadius = blurRadius,
                            tints = listOf(
                                HazeTint(
                                    if (tint == Color.Unspecified) {
                                        Color.White.copy(alpha = 0.18f)
                                    } else {
                                        tint
                                    }
                                )
                            ),
                        )
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (enabled) {
                    Modifier.drawWithCache {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        val w = size.width
                        val h = size.height

                        // 内阴影：底部偏暗，制造玻璃厚度
                        val innerShadow = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.10f)
                            ),
                            startY = 0f,
                            endY = h
                        )

                        // 菲涅耳高光：顶部亮 → 中部透明 → 底部微弱反光
                        val fresnel = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = (0.55f * glow).coerceIn(0f, 1f)),
                                Color.White.copy(alpha = (0.10f * glow).coerceIn(0f, 1f)),
                                Color.Transparent,
                                Color.White.copy(alpha = (0.14f * glow).coerceIn(0f, 1f))
                            ),
                            startY = 0f,
                            endY = h
                        )

                        // 色散：极低透明度的彩虹描边（模拟边缘折射色散）
                        val dispersionBrush = Brush.sweepGradient(
                            0f to Color(0xFFFF4D4D).copy(alpha = 0.20f * dispersion),
                            0.2f to Color(0xFFFFD24D).copy(alpha = 0.16f * dispersion),
                            0.4f to Color(0xFF4DFFB8).copy(alpha = 0.18f * dispersion),
                            0.6f to Color(0xFF4DB8FF).copy(alpha = 0.18f * dispersion),
                            0.8f to Color(0xFFB84DFF).copy(alpha = 0.16f * dispersion),
                            1f to Color(0xFFFF4D4D).copy(alpha = 0.20f * dispersion)
                        )

                        onDrawWithContent {
                            drawContent()

                            val inset = 0.75.dp.toPx()
                            val innerSize = Size(
                                (w - inset * 2).coerceAtLeast(0f),
                                (h - inset * 2).coerceAtLeast(0f)
                            )
                            val innerTopLeft = Offset(inset, inset)

                            if (ellipse) {
                                // 水滴光斑：按椭圆绘制 —— 拉伸态下也能保持「水滴/透镜」观感
                                if (dispersion > 0f) {
                                    drawOval(
                                        brush = dispersionBrush,
                                        topLeft = innerTopLeft,
                                        size = innerSize,
                                        style = Stroke(width = 1.6.dp.toPx())
                                    )
                                }
                                drawOval(
                                    brush = innerShadow,
                                    topLeft = innerTopLeft,
                                    size = innerSize,
                                    style = Stroke(width = 1.2.dp.toPx())
                                )
                                drawOval(
                                    brush = fresnel,
                                    topLeft = innerTopLeft,
                                    size = innerSize,
                                    style = Stroke(width = 1.4.dp.toPx())
                                )
                            } else {
                                when (outline) {
                                    is Outline.Rounded -> {
                                        val cr = outline.roundRect.topLeftCornerRadius
                                        val corner = CornerRadius(cr.x, cr.y)
                                        drawRoundRectLayer(
                                            brush = dispersionBrush,
                                            inset = inset,
                                            width = w,
                                            height = h,
                                            corner = corner,
                                            strokeWidth = 1.6.dp.toPx(),
                                            enabled = dispersion > 0f
                                        )
                                        drawRoundRect(
                                            brush = innerShadow,
                                            topLeft = innerTopLeft,
                                            size = innerSize,
                                            cornerRadius = corner,
                                            style = Stroke(width = 1.2.dp.toPx())
                                        )
                                        drawRoundRect(
                                            brush = fresnel,
                                            topLeft = innerTopLeft,
                                            size = innerSize,
                                            cornerRadius = corner,
                                            style = Stroke(width = 1.4.dp.toPx())
                                        )
                                    }

                                    else -> {
                                        drawRect(
                                            brush = fresnel,
                                            topLeft = innerTopLeft,
                                            size = innerSize,
                                            style = Stroke(width = 1.4.dp.toPx())
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        content()
    }
}

/** 圆角矩形描边辅助（用于色散层，避免重复计算）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectLayer(
    brush: Brush,
    inset: Float,
    width: Float,
    height: Float,
    corner: CornerRadius,
    strokeWidth: Float,
    enabled: Boolean
) {
    if (!enabled) return
    drawRoundRect(
        brush = brush,
        topLeft = Offset(inset, inset),
        size = Size(
            (width - inset * 2).coerceAtLeast(0f),
            (height - inset * 2).coerceAtLeast(0f)
        ),
        cornerRadius = corner,
        style = Stroke(width = strokeWidth)
    )
}

/**
 * 水滴光斑（Liquid Glass 指示器的核心）。
 *
 * 由外到内：
 *   1. 外层光晕：radialGradient 从中心亮白 → 边缘透明，形成"发光"感
 *   2. 玻璃本体：Haze 模糊（比底栏更强）+ 主题色 tint
 *   3. 边缘高光：白色发光描边 + 轻微色散
 *
 * 形状用椭圆（圆角 50%），配合 spring 位移 + 宽度拉伸形成"液体粘连"感。
 */
@Composable
fun LiquidGlassHalo(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 36.dp,
    tint: Color = Color.Unspecified,
    haloColor: Color = Color.Unspecified,
    glow: Float = 1.4f,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val params = LocalLiquidGlass.current
    val enabled = params.enabled

    val halo = if (haloColor == Color.Unspecified) {
        Color.White.copy(alpha = 0.30f)
    } else {
        haloColor
    }

    Box(modifier = modifier) {
        // 外层光晕（玻璃本体之外，radialGradient 溢出发光）
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val glowBrush = Brush.radialGradient(
                            colors = listOf(
                                halo,
                                halo.copy(alpha = halo.alpha * 0.35f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.maxDimension * 0.62f
                        )
                        onDrawWithContent {
                            drawContent()
                            drawOval(brush = glowBrush)
                        }
                    }
            )
        }

        // 玻璃本体
        LiquidGlassPanel(
            hazeState = hazeState,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(percent = 50),
            blurRadius = blurRadius,
            tint = tint,
            interactive = false,
            glow = glow,
            dispersion = 0.8f,
            ellipse = true
        ) {
            content()
        }
    }
}

/**
 * 液态玻璃表面（不带按压反馈的通用版本），用于开关底板 / 选项卡片等。
 */
@Composable
fun LiquidGlassSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    blurRadius: Dp = 20.dp,
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    LiquidGlassPanel(
        hazeState = hazeState,
        modifier = modifier,
        shape = shape,
        blurRadius = blurRadius,
        tint = tint,
        interactive = false,
        content = content
    )
}