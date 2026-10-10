/*
 * 液态毛玻璃底栏（移植自 Kyant0/AndroidLiquidGlass 的 LiquidBottomTabs，Apache-2.0）。
 *
 * 与上一版（Haze 方案）的区别 —— 这一版用的是参考项目真正的「背板采样 + 折射」管线：
 *   1. 内容层（页面 + 自定义背景）被录进 GraphicsLayer（由调用方用 Modifier.layerBackdrop 挂上）；
 *   2. 底栏把这张图层当作「背板」，按圆角形状画进来，并挂上 RenderEffect 链：
 *          vibrancy()（增艳）→ blur(8dp)（磨砂）→ lens(24dp, 24dp)（边缘折射）
 *   3. 折射由 AGSL RuntimeShader 完成：沿圆角矩形 SDF 法线做 circleMap 位移采样，
 *      所以玻璃边缘会把背后的内容「掰弯」——这才是液态玻璃的光线透感来源；
 *   4. 再叠 highlight（边缘高光）/ shadow（外阴影）/ innerShadow（内阴影），
 *      最后 onDrawSurface 画半透明容器色（亮 Color(0xFFFAFAFA)@40%、暗 Color(0xFF121212)@40%）。
 *
 * 交互（点击反馈）：
 *   - DampedDragAnimation：按下 → 整条栏轻微横向鼓起 + 选中胶囊放大；松手 → 弹簧回弹；
 *   - 选中胶囊用弹簧在 tab 之间滑动（animateToValue），滑动中带速度形变（液体粘连感）；
 *   - 按下时胶囊的折射量 / 色散 / 高光 / 内外阴影随 pressProgress 一起出现（参考实现同款）；
 *   - InteractiveHighlight：手指位置的白色柔光光斑。
 */
package frb.axeron.manager.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import frb.axeron.manager.ui.glass.Backdrop
import frb.axeron.manager.ui.glass.DampedDragAnimation
import frb.axeron.manager.ui.glass.Highlight
import frb.axeron.manager.ui.glass.InnerShadow
import frb.axeron.manager.ui.glass.InteractiveHighlight
import frb.axeron.manager.ui.glass.Shadow
import frb.axeron.manager.ui.glass.blur
import frb.axeron.manager.ui.glass.drawBackdrop
import frb.axeron.manager.ui.glass.lens
import frb.axeron.manager.ui.glass.vibrancy
import frb.axeron.manager.ui.navigation.BottomBarDestination

/**
 * 悬浮液态毛玻璃底栏。
 *
 * @param backdrop 内容层背板（调用方用 `Modifier.layerBackdrop(backdrop)` 挂在铺满全屏的内容层上）
 * @param destinations 当前可见的 tab（Axeron 未运行时部分 tab 会被过滤掉）
 * @param selectedIndex 当前选中索引（跟随真实导航状态）
 * @param moduleUpdateCount 模块更新数（>0 时给「插件」tab 打角标）
 * @param onSelect 点击回调，参数是 [destinations] 里的索引
 */
@Composable
fun LiquidGlassBottomBar(
    backdrop: Backdrop,
    destinations: List<BottomBarDestination>,
    selectedIndex: Int,
    moduleUpdateCount: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (destinations.isEmpty()) return

    val isLightTheme = !isSystemInDarkTheme()

    // 容器色 / 强调色取自参考实现（LiquidBottomTabs）：中性半透明色，不会把背景染蓝
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(alpha = 0.4f)
        else Color(0xFF121212).copy(alpha = 0.4f)
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)

    val barShape = RoundedCornerShape(28.dp)
    val pillShape = RoundedCornerShape(percent = 50)

    val tabsCount = destinations.size
    val animationScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 每个 tab 的宽度：栏内均分（外层已留出左右边距）
        val tabWidth = maxWidth / tabsCount

        val animation = remember(animationScope, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // 按下时胶囊放大到 1.35 倍左右（参考实现用 78/56 ≈ 1.39）
                pressedScale = 1.35f,
                onDragStarted = {},
                onDragStopped = {},
                // 本版不做「拖动切换 tab」，只借用它的按压/回弹状态机
                onDrag = { _, _ -> }
            )
        }

        // 外部选中项变化（点击 tab / 返回栈变化 / 深链）→ 胶囊用弹簧滑过去，并带一次按压缩放反馈
        LaunchedEffect(selectedIndex, animation) {
            animation.animateToValue(selectedIndex.toFloat())
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(animationScope)
        }

        val indicatorWidth = tabWidth * 0.68f
        val indicatorHeight = 46.dp

        // ---- 整条栏：液态玻璃表面 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(24.dp.toPx(), 24.dp.toPx(), depthEffect = true)
                    },
                    layerBlock = {
                        // 按下时整条栏横向轻微「鼓起」，模拟液体表面张力
                        val progress = animation.pressProgress
                        val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                // 手指位置的白色柔光（画在玻璃表面之上、图标之下）
                .then(interactiveHighlight.modifier)
                .then(interactiveHighlight.gestureModifier)
        ) {
            // ---- 第 1 层：选中项的液态玻璃胶囊（独立的第二层玻璃） ----
            Box(
                modifier = Modifier
                    .offset(
                        x = animation.value.let { tabWidth * it } +
                                (tabWidth - indicatorWidth) / 2,
                        y = (64.dp - indicatorHeight) / 2
                    )
                    .width(indicatorWidth)
                    .height(indicatorHeight)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { pillShape },
                        effects = {
                            val progress = animation.pressProgress
                            vibrancy()
                            blur(4.dp.toPx())
                            // 按下才出现折射 + 七通道色散（彩虹边）——「点击时的液体感」就来自这里
                            lens(
                                10.dp.toPx() * progress,
                                14.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = animation.pressProgress)
                        },
                        shadow = {
                            Shadow(alpha = animation.pressProgress)
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 8.dp * animation.pressProgress,
                                alpha = animation.pressProgress
                            )
                        },
                        layerBlock = {
                            scaleX = animation.scaleX
                            scaleY = animation.scaleY
                        },
                        onDrawSurface = {
                            val progress = animation.pressProgress
                            // 常态：一层很淡的黑/白薄雾，把选中项从栏体上「托」出来
                            drawRect(
                                if (isLightTheme) Color.Black.copy(alpha = 0.1f)
                                else Color.White.copy(alpha = 0.1f),
                                alpha = 1f - progress
                            )
                            // 按下：再压深一点，强化「被按下去」的手感
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
            )

            // ---- 第 2 层：图标 + 角标（画在玻璃之上，始终 100% 清晰） ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEachIndexed { index, destination ->
                    val isSelected = index == selectedIndex
                    val label = stringResource(id = destination.labelId)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                // 只负责导航；selectedIndex 由上层跟随真实路由更新，胶囊位置永远等于当前页面
                                onSelect(index)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val iconTint =
                            if (isSelected) accentColor
                            else MaterialTheme.colorScheme.onSurfaceVariant

                        if (destination == BottomBarDestination.Plugin && moduleUpdateCount > 0) {
                            BadgedBox(badge = { Badge { Text(moduleUpdateCount.toString()) } }) {
                                Icon(
                                    imageVector = if (isSelected) destination.iconSelected
                                    else destination.iconNotSelected,
                                    contentDescription = label,
                                    tint = iconTint
                                )
                            }
                        } else {
                            Icon(
                                imageVector = if (isSelected) destination.iconSelected
                                else destination.iconNotSelected,
                                contentDescription = label,
                                tint = iconTint
                            )
                        }
                    }
                }
            }
        }
    }
}
