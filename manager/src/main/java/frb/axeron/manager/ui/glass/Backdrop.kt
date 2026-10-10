/*
 * 液态毛玻璃（backdrop）实现 —— 移植自 Kyant0/AndroidLiquidGlass 的 backdrop 库（Apache-2.0）。
 * 原库为 Compose Multiplatform 的 expect/actual 结构，这里只保留 Android 实现并压平成单包。
 */
package frb.axeron.manager.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density

/**
 * 「背景图层」抽象：把某块区域（通常是整屏内容/背景）录制成一张可被重复采样、变换的图层，
 * 玻璃节点就能在这张图层上做折射/模糊/色散等基于像素的效果。
 */
interface Backdrop {

    /** 是否依赖绘制坐标（LayerBackdrop 为 true：需要知道自己相对背景层的位置）。 */
    val isCoordinatesDependent: Boolean

    fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)? = null
    )
}

/** 把已有 backdrop 包一层「绘制前/后」钩子。 */
@Composable
fun rememberBackdrop(
    backdrop: Backdrop,
    onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit
): Backdrop = remember(backdrop, onDraw) { WrappedBackdrop(backdrop, onDraw) }

@Immutable
private class WrappedBackdrop(
    val backdrop: Backdrop,
    val onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = backdrop.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        onDraw { with(backdrop) { drawBackdrop(density, coordinates, layerBlock) } }
    }
}

/** 两张背景层叠加（例如：页面内容层 + 图标层）。 */
@Composable
fun rememberCombinedBackdrop(backdrop1: Backdrop, backdrop2: Backdrop): Backdrop =
    remember(backdrop1, backdrop2) { Combined2Backdrops(backdrop1, backdrop2) }

@Composable
fun rememberCombinedBackdrop(backdrop1: Backdrop, backdrop2: Backdrop, backdrop3: Backdrop): Backdrop =
    remember(backdrop1, backdrop2, backdrop3) { Combined3Backdrops(backdrop1, backdrop2, backdrop3) }

@Immutable
private class Combined2Backdrops(val backdrop1: Backdrop, val backdrop2: Backdrop) : Backdrop {

    override val isCoordinatesDependent: Boolean =
        backdrop1.isCoordinatesDependent || backdrop2.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        with(backdrop1) { drawBackdrop(density, coordinates, layerBlock) }
        with(backdrop2) { drawBackdrop(density, coordinates, layerBlock) }
    }
}

@Immutable
private class Combined3Backdrops(
    val backdrop1: Backdrop,
    val backdrop2: Backdrop,
    val backdrop3: Backdrop
) : Backdrop {

    override val isCoordinatesDependent: Boolean =
        backdrop1.isCoordinatesDependent ||
            backdrop2.isCoordinatesDependent ||
            backdrop3.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        with(backdrop1) { drawBackdrop(density, coordinates, layerBlock) }
        with(backdrop2) { drawBackdrop(density, coordinates, layerBlock) }
        with(backdrop3) { drawBackdrop(density, coordinates, layerBlock) }
    }
}
