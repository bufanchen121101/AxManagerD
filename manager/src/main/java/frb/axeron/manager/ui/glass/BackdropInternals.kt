/*
 * backdrop 内部工具（移植自 Kyant0/AndroidLiquidGlass 的 backdrop 库，Apache-2.0）。
 * 原库用 expect/actual 分平台；这里全部落到 Android 实现，并去掉 Kotlin context receiver 语法。
 */
package frb.axeron.manager.ui.glass

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultCameraDistance
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toIntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ============ 形状提供者 ============

/**
 * 形状包装：把 `() -> Shape` 缓存成可复用的 Outline，避免每帧重复计算（圆角矩形 outline 计算不便宜）。
 */
@Immutable
internal class ShapeProvider(val shapeBlock: () -> Shape) {

    private var _shape: Shape? = null
    private var _outline: Outline? = null
    private var _size: Size = Size.Unspecified
    private var _layoutDirection: LayoutDirection? = null
    private var _density: Float? = null

    val innerShape
        get() = shapeBlock()

    val shape = object : Shape {

        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density
        ): Outline {
            val shape = shapeBlock()
            if (_shape != shape) {
                _shape = shape
                _outline = null
            }
            if (_outline == null || _size != size || _layoutDirection != layoutDirection || _density != density.density) {
                _size = size
                _layoutDirection = layoutDirection
                _density = density.density
                _outline = shape.createOutline(size, layoutDirection, density)
            }

            return _outline!!
        }
    }
}

// ============ 图层录制 ============

/**
 * 把一个 DrawScope 的绘制内容录进 GraphicsLayer。
 *
 * 注意：原库在这里额外把 drawContext.density 覆盖成节点的 density（Kotlin context receiver 写法）。
 * 这里改成直接录制 —— GraphicsLayer 创建时会带上当前 composition 的 density，
 * 本项目不存在自定义 Density 覆盖，行为等价且更少踩 API 差异。
 */
internal fun DrawScope.recordLayer(
    layer: GraphicsLayer,
    size: IntSize = this.size.toIntSize(),
    block: DrawScope.() -> Unit
) {
    // Compose 1.11 的 GraphicsLayer.record 没有默认参数，density / layoutDirection 必须显式传入
    layer.record(
        density = this,
        layoutDirection = layoutDirection,
        size = size,
        block = block
    )
}

/** 按 Outline 裁剪画布（矩形 / 圆角 / 任意路径）。 */
internal fun Canvas.clipOutline(outline: Outline, path: Path?) {
    when (outline) {
        is Outline.Rectangle -> clipRect(outline.rect)
        is Outline.Rounded -> {
            path!!.rewind()
            path.addRoundRect(outline.roundRect)
            clipPath(path)
        }

        is Outline.Generic -> clipPath(outline.path)
    }
}

/** 给画笔挂模糊遮罩（用于边缘高光的柔化）。 */
@Suppress("DEPRECATION")
internal fun Paint.blur(radius: Float) {
    this.asFrameworkPaint().maskFilter =
        if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
}

/** 给画笔挂 AGSL RuntimeShader（用于边缘高光的方向性着色）。 */
@Suppress("DEPRECATION")
internal fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    this.asFrameworkPaint().shader = runtimeShader?.asAndroidRuntimeShader()
}

// ============ RenderEffect 组合 ============

/** 把 RenderEffect 串成链：注意 Android 的 createChainEffect(outer, inner) 是「先 inner 后 outer」。 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun RenderEffect?.chainCompat(other: RenderEffect): RenderEffect {
    return if (this != null) {
        android.graphics.RenderEffect.createChainEffect(
            other.asAndroidRenderEffect(),
            this.asAndroidRenderEffect()
        ).asComposeRenderEffect()
    } else {
        other
    }
}

/** 用 AGSL RuntimeShader 对图层内容做逐像素变换（content 即被采样的背景层）。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShaderEffectCompat(
    runtimeShader: RuntimeShader,
    uniformShaderName: String
): RenderEffect {
    return android.graphics.RenderEffect.createRuntimeShaderEffect(
        runtimeShader.asAndroidRuntimeShader(),
        uniformShaderName
    ).asComposeRenderEffect()
}

/** 颜色滤镜效果（饱和度/增艳等）。 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun ColorFilterEffectCompat(
    renderEffect: RenderEffect?,
    colorFilter: ColorFilter
): RenderEffect {
    return if (renderEffect != null) {
        android.graphics.RenderEffect.createColorFilterEffect(
            colorFilter.asAndroidColorFilter(),
            renderEffect.asAndroidRenderEffect()
        ).asComposeRenderEffect()
    } else {
        android.graphics.RenderEffect.createColorFilterEffect(
            colorFilter.asAndroidColorFilter(),
        ).asComposeRenderEffect()
    }
}

// ============ 逆变换作用域 ============

/**
 * 用于把「玻璃节点的图形层变换」在采样背景层时反向施加，
 * 这样玻璃自身做缩放/旋转时，采到的背景仍是正确的对应区域（原库 LayerBackdrop 用）。
 */
internal class InverseLayerScope : GraphicsLayerScope {

    override var size: Size = Size.Unspecified
    override var density: Float = 1f
    override var fontScale: Float = 1f

    override var scaleX: Float = 1f
    override var scaleY: Float = 1f
    override var alpha: Float = 0f
    override var translationX: Float = 0f
    override var translationY: Float = 0f
    override var shadowElevation: Float = 0f
    override var ambientShadowColor: Color = DefaultShadowColor
    override var spotShadowColor: Color = DefaultShadowColor
    override var rotationX: Float = 0f
    override var rotationY: Float = 0f
    override var rotationZ: Float = 0f
    override var cameraDistance: Float = DefaultCameraDistance
    override var transformOrigin: TransformOrigin = TransformOrigin.Center
    override var shape: Shape = RectangleShape
    override var clip: Boolean = false
    override var renderEffect: RenderEffect? = null
    override var blendMode: BlendMode = BlendMode.SrcOver
    override var colorFilter: ColorFilter? = null
    override var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto

    private var matrix: Matrix? = null

    fun DrawTransform.inverseTransform(
        density: Density,
        layerBlock: GraphicsLayerScope.() -> Unit
    ) {
        this@InverseLayerScope.size = size
        this@InverseLayerScope.density = density.density
        fontScale = density.fontScale

        layerBlock()

        inverseTransformAtTopLeft(
            rotationZ = rotationZ,
            scaleX = scaleX,
            scaleY = scaleY
        )
    }

    fun reset() {
        size = Size.Unspecified
        density = 1f
        fontScale = 1f

        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        translationX = 0f
        translationY = 0f
        shadowElevation = 0f
        ambientShadowColor = DefaultShadowColor
        spotShadowColor = DefaultShadowColor
        rotationX = 0f
        rotationY = 0f
        rotationZ = 0f
        cameraDistance = DefaultCameraDistance
        transformOrigin = TransformOrigin.Center
        shape = RectangleShape
        clip = false
        renderEffect = null
        blendMode = BlendMode.SrcOver
        colorFilter = null
        compositingStrategy = CompositingStrategy.Auto

        matrix = null
    }

    private fun DrawTransform.inverseTransformAtTopLeft(
        rotationZ: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ) {
        if (rotationZ == 0f) {
            if (scaleX != 0f && scaleY != 0f) {
                scale(1f / scaleX, 1f / scaleY, Offset.Zero)
            }
            return
        }

        val matrix = matrix ?: Matrix().also { matrix = it }
        if (matrix.values.size < 16) return

        val rz = rotationZ * (PI / 180.0)
        val rsz = sin(rz).toFloat()
        val rcz = cos(rz).toFloat()

        val a00 = rcz * scaleX
        val a01 = rsz * scaleY
        val a10 = -rsz * scaleX
        val a11 = rcz * scaleY

        val det = a00 * a11 - a01 * a10
        if (det == 0f) return
        val invDet = 1f / det
        matrix[0, 0] = a11 * invDet
        matrix[0, 1] = -a01 * invDet
        matrix[1, 0] = -a10 * invDet
        matrix[1, 1] = a00 * invDet

        transform(matrix)
    }
}