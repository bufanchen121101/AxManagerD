// Copyright 2025, Colin McKee
// SPDX-License-Identifier: Apache-2.0
//
// Compose 液态玻璃效果（AGSL RuntimeShader 实现）。
//
// 实现思路参考 FletchMcKee/liquid（Apache-2.0）：
//   https://github.com/FletchMcKee/liquid
//
// 三件套：
//   1. liquefiable(源)：把「背景内容」录进 GraphicsLayer，供效果节点采样；
//   2. LiquidGlassState：登记所有源节点；
//   3. liquidGlass(效果)：把源像素画进自己的 layer，再给 layer 挂 RuntimeShader 的
//      RenderEffect，实现透镜折射 / 色散 / 边缘光；自身内容绘制在效果之上，始终清晰。
//
// 兼容性：
//   - Android 13 (API 33) 及以上：完整效果（折射 + 曲率 + 色散 + 边缘光）。
//   - Android 13 以下：RuntimeShader 不可用，自动降级为「透明 layer + 纯内容」，
//     由调用方用 Haze 模糊兜底外观。
package frb.axeron.manager.ui.component

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/** 单个「可被采样」的背景源。 */
@Stable
internal class Liquefiable {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var boundsInWindow: Rect by mutableStateOf(Rect.Zero)
}

/**
 * 液态玻璃共享状态：登记所有 liquefiable 源节点，供 liquidGlass 效果节点采样。
 */
@Stable
class LiquidGlassState {
    internal val liquefiables: MutableList<Liquefiable> = mutableListOf()
}

/** 创建并记住一个 [LiquidGlassState]。 */
@Composable
fun rememberLiquidGlassState(): LiquidGlassState = remember { LiquidGlassState() }

/**
 * 把该节点的内容登记为「可采样的背景源」。
 *
 * 挂在需要被玻璃采样的内容上（例如内容区 / 底栏背后的页面）。
 */
fun Modifier.liquefiable(state: LiquidGlassState): Modifier =
    this then LiquefiableElement(state)

private class LiquefiableElement(
    private val state: LiquidGlassState,
) : ModifierNodeElement<LiquefiableNode>() {
    override fun create(): LiquefiableNode = LiquefiableNode(state)
    override fun update(node: LiquefiableNode) {
        node.state = state
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiquefiableElement) return false
        return state === other.state
    }

    override fun hashCode(): Int = state.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "liquefiable"
    }
}
private class LiquefiableNode(
    var state: LiquidGlassState,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    TraversableNode {

    /**
     * 防递归关键：标识「liquefiable 源节点」。
     *
     * 效果节点（liquidGlass）绘制时会查找最近的 liquefiable 祖先并把「自己」从采样源中排除，
     * 避免出现「源 → 效果 → 源 → 效果 …」的无限递归（会直接 SIGSEGV 闪退）。
     *
     * 注：Compose 1.11 中 TraversableNode 的 traverseKey 类型为 Any，
     * 用 object 单例作为「节点类型标识」即可。
     */
    internal object LiquefiableKey

    override val traverseKey: Any = LiquefiableKey

    /** 供效果节点（LiquidGlassNode）做防递归判断时读取。 */
    internal val liquefiable = Liquefiable()

    private fun obtainGraphicsLayer(): GraphicsLayer =
        liquefiable.layer?.takeUnless { it.isReleased }
            ?: currentValueOf(LocalGraphicsContext)
                .createGraphicsLayer()
                .also { liquefiable.layer = it }

    override val shouldAutoInvalidate: Boolean = false

    override fun onAttach() {
        state.liquefiables += liquefiable
    }

    override fun onDetach() = Snapshot.withMutableSnapshot {
        state.liquefiables -= liquefiable
        liquefiable.layer?.let { currentValueOf(LocalGraphicsContext).releaseGraphicsLayer(it) }
        liquefiable.layer = null
        liquefiable.boundsInWindow = Rect.Zero
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        // 用「窗口坐标」而非「根布局坐标」——与效果节点保持同一基准，
        // 否则两者坐标系不一致会导致采样偏移错位（画面全空/白圈）。
        liquefiable.boundsInWindow = coordinates.boundsInWindow()
    }

    override fun ContentDrawScope.draw() {
        if (size.minDimension < 1f) {
            drawContent()
            return
        }
        val contentLayer = Snapshot.withoutReadObservation { obtainGraphicsLayer() }
        contentLayer.record { this@draw.drawContent() }
        drawLayer(contentLayer)
    }
}

/** 供 `liquidGlass { ... }` 块内配置参数的作用域。 */
@Stable
class LiquidGlassScope internal constructor() {
    /** 磨砂（模糊）半径 —— 玻璃背后内容的虚化程度。 */
    var frost: Dp = 0.dp

    /** 背景扭曲强度（透镜折射）。0 表示无透镜效果。 */
    var refraction: Float = 0.25f

    /** 透镜中心与边缘的曲率差异。0 表示无透镜效果。 */
    var curve: Float = 0.25f

    /** 色散（RGB 通道分离）强度 —— 边缘的彩虹光。 */
    var dispersion: Float = 0f

    /** 边缘轮廓光宽度 —— 边缘的白色发光亮线。 */
    var edge: Float = 0f

    /** 背景对比度。 */
    var contrast: Float = 1f

    /** 背景饱和度。 */
    var saturation: Float = 1f

    /** 叠加的染色（alpha 决定强度）。 */
    var tint: Color = Color.Unspecified

    /** 形状（决定 SDF 轮廓与圆角）。 */
    var shape: Shape = RoundedCornerShape(0.dp)
}

/**
 * 液态玻璃效果。
 *
 * Android 13+ 用 RuntimeShader 做真正的折射 / 色散 / 边缘光；
 * 低版本不绘制效果层（内容仍正常显示，由调用方兜底外观）。
 */
fun Modifier.liquidGlass(
    state: LiquidGlassState,
    block: LiquidGlassScope.() -> Unit = {},
): Modifier = this then LiquidGlassElement(state, block)

private class LiquidGlassElement(
    private val state: LiquidGlassState,
    private val block: LiquidGlassScope.() -> Unit,
) : ModifierNodeElement<LiquidGlassNode>() {
    override fun create(): LiquidGlassNode = LiquidGlassNode(state, block)
    override fun update(node: LiquidGlassNode) {
        node.state = state
        node.block = block
        node.refreshBlock()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiquidGlassElement) return false
        return state === other.state && block === other.block
    }

    override fun hashCode(): Int = 31 * state.hashCode() + block.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "liquidGlass"
    }
}

private class LiquidGlassNode(
    var state: LiquidGlassState,
    var block: LiquidGlassScope.() -> Unit,
) : Modifier.Node(),
    GlobalPositionAwareModifierNode,
    DrawModifierNode,
    CompositionLocalConsumerModifierNode,
    TraversableNode {

    override val traverseKey: Any = "liquidGlass"

    private val scope = LiquidGlassScope()
    private var cachedLayer: GraphicsLayer? = null
    private var positionInWindow: Offset = Offset.Zero
    private var boundsInWindow: Rect = Rect.Zero
    private var size: Size = Size.Zero

    override val shouldAutoInvalidate: Boolean = false

    private fun obtainGraphicsLayer(): GraphicsLayer =
        cachedLayer?.takeUnless { it.isReleased }
            ?: currentValueOf(LocalGraphicsContext)
                .createGraphicsLayer()
                .also { cachedLayer = it }

    internal fun refreshBlock() {
        if (!isAttached) return
        block(scope)
        invalidateDraw()
    }

    override fun onAttach() {
        refreshBlock()
    }

    override fun onDetach() {
        cachedLayer?.let { currentValueOf(LocalGraphicsContext).releaseGraphicsLayer(it) }
        cachedLayer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!isAttached) return
        positionInWindow = coordinates.positionInWindow()
        boundsInWindow = coordinates.boundsInWindow()
        size = coordinates.size.toSize()
        refreshBlock()
    }

    override fun ContentDrawScope.draw() {
        // API < 33 或尺寸无效：直接画内容（降级）
        if (Build.VERSION.SDK_INT < 33 || size.minDimension < 1f) {
            drawContent()
            return
        }
        // 防递归：若本效果节点存在「liquefiable 祖先」，说明自己是该祖先的后代 ——
        // 采样它会导致「祖先画自己 → 自己采祖先」的无限递归（SIGSEGV 闪退）。
        // 这里把最近的 liquefiable 祖先对应的源剔除掉。
        val ancestorLiquefiable = Snapshot.withoutReadObservation {
            (findNearestAncestor(LiquefiableNode.LiquefiableKey) as? LiquefiableNode)?.liquefiable
        }

        val sources = state.liquefiables.filter {
            it.layer != null &&
                !it.layer!!.isReleased &&
                it !== ancestorLiquefiable &&
                it.boundsInWindow.overlaps(boundsInWindow)
        }

        // ⚠️ 关键：即使采样源为空，也必须走 shader 路径（拿到「透明背景」而不是
        // 直接 drawContent 只剩兜底外观）。原实现在这里提前 return，导致画面只剩
        // 调用方画的白色描边 —— 看起来就是「一个白色的圈」。
        val layer = obtainGraphicsLayer()

        // 1. 把背景源按「相对本节点左上角」的位置录进 layer
        layer.record(IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))) {
            sources.forEach { src ->
                val srcLayer = src.layer ?: return@forEach
                val dx = src.boundsInWindow.left - positionInWindow.x
                val dy = src.boundsInWindow.top - positionInWindow.y
                withTransform({ translate(left = dx, top = dy) }) {
                    drawLayer(srcLayer)
                }
            }
        }

        // 2. 给 layer 挂 shader RenderEffect
        layer.renderEffect = createRenderEffect()

        // 3. 画出带效果的 layer，再画自己的内容（清晰）
        drawLayer(layer)
        drawContent()
    }

    @RequiresApi(33)
    private fun createRenderEffect(): RenderEffect? {
        val density: Density = currentValueOf(LocalDensity)
        val shader = RuntimeShader(LIQUID_GLASS_SHADER)
        val w = size.width
        val h = size.height

        val radii = normalizedCornerRadii(scope.shape, size, density)

        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("cornerRadii", radii[0], radii[1], radii[2], radii[3])
        shader.setFloatUniform("refraction", scope.refraction)
        shader.setFloatUniform("curve", scope.curve)
        shader.setFloatUniform("dispersion", scope.dispersion)
        shader.setFloatUniform("edge", scope.edge)
        shader.setFloatUniform("contrast", scope.contrast)
        shader.setFloatUniform("saturation", scope.saturation)

        val tintColor = if (scope.tint == Color.Unspecified) Color.Transparent else scope.tint
        shader.setColorUniform(
            "tint",
            android.graphics.Color.argb(
                (tintColor.alpha * 255f).toInt().coerceIn(0, 255),
                (tintColor.red * 255f).toInt().coerceIn(0, 255),
                (tintColor.green * 255f).toInt().coerceIn(0, 255),
                (tintColor.blue * 255f).toInt().coerceIn(0, 255),
            ),
        )

        val effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        val frostPx = with(density) { scope.frost.toPx() }
        val finalEffect = if (frostPx >= 1f) {
            android.graphics.RenderEffect.createChainEffect(
                effect,
                android.graphics.RenderEffect.createBlurEffect(
                    frostPx,
                    frostPx,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        } else {
            effect
        }
        return finalEffect.asComposeRenderEffect()
    }
}

/**
 * 把 Compose [Shape] 换算为 shader 需要的「归一化圆角半径」四元组
 * （顺序：bottomEnd, topEnd, bottomStart, topStart），已按短边归一化并钳制到 0.5。
 */
@androidx.annotation.Size(value = 4)
private fun normalizedCornerRadii(
    shape: Shape,
    size: Size,
    density: Density,
): FloatArray = when (shape) {
    // 圆形（水滴指示器）：四角全 0.5 → shader 里就是标准圆。
    // 与官方实现一致，避免 RoundedCornerShape(percent=50) 在不同尺寸下换算偏差。
    androidx.compose.foundation.shape.CircleShape ->
        floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)

    is RoundedCornerShape -> {
        val minDim = size.minDimension
        if (minDim <= 0f) {
            floatArrayOf(0f, 0f, 0f, 0f)
        } else {
            var topStart = shape.topStart.toPx(size, density)
            var topEnd = shape.topEnd.toPx(size, density)
            var bottomEnd = shape.bottomEnd.toPx(size, density)
            var bottomStart = shape.bottomStart.toPx(size, density)
            if (topStart + bottomStart > minDim) {
                val s = 1f / (topStart + bottomStart)
                topStart *= s
                bottomStart *= s
            } else {
                topStart /= minDim
                bottomStart /= minDim
            }
            if (topEnd + bottomEnd > minDim) {
                val s = 1f / (topEnd + bottomEnd)
                topEnd *= s
                bottomEnd *= s
            } else {
                topEnd /= minDim
                bottomEnd /= minDim
            }
            floatArrayOf(
                bottomEnd.coerceIn(0f, 0.5f),
                topEnd.coerceIn(0f, 0.5f),
                bottomStart.coerceIn(0f, 0.5f),
                topStart.coerceIn(0f, 0.5f),
            )
        }
    }

    else -> floatArrayOf(0f, 0f, 0f, 0f)
}