package frb.axeron.manager.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * AxManagerD 官方图标（矢量路径，非位图资源）。
 *
 * 来源：`manager/src/main/res/drawable/ic_launcher_foreground.xml`
 * 说明：内联官方 pathData 字符串，交由 [PathParser] 解析为节点，
 * 再通过 [ImageVector.Builder.addPath] 构建。坐标不做任何手工换算，
 * 渲染结果与官方 vector drawable 保持一致。
 *
 * 该图标**无棕色背景**，仅描边/填充主体形状；填充色由外层 Icon 的 tint 决定，
 * 默认使用官方原色 #e3d4bd（米棕）。
 */
object AxeronIcons {

    private const val NAME = "AxeronMark"

    /** 官方 pathData（512x512 viewport，group scale=0.6 / translate=102.4）。 */
    private const val PATH_DATA =
        "M124.65,259.65l36.79,4.89l104.95,-173.24l-4.73,54.99l6.28,-5.3" +
            "l119.4,169.36l-34.13,-11.32l-13.47,14.24l9.52,-1.17" +
            "l-95.4,108.08l55.09,-101.92l-11.43,3.16l23.44,-38.29" +
            "l-67.09,-96.62l-59.75,94.27l22.75,37.91l-10.92,-2.38" +
            "l32.24,61.34z"

    /** 官方 group 变换（源自 ic_launcher_foreground.xml）。 */
    private const val GROUP_SCALE = 0.6f
    private const val GROUP_TRANSLATE = 102.4f

    /** viewport 与源资源一致。 */
    private const val VIEWPORT = 512f

    /** 官方原色（米棕）。 */
    val DEFAULT_TINT = Color(0xFFE3D4BD)

    /**
     * 无背景图标（默认 24dp）。
     *
     * 说明：`fill = SolidColor(DEFAULT_TINT)` 仅作为兜底，
     * 实际渲染时由外层 `Icon(tint = ...)` 覆盖，符合 Material 图标用法。
     */
    val AxeronMark: ImageVector by lazy { build() }

    private fun build(): ImageVector = ImageVector.Builder(
        name = NAME,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT
    ).run {
        addGroup(
            name = "group",
            rotate = 0f,
            pivotX = 0f,
            pivotY = 0f,
            scaleX = GROUP_SCALE,
            scaleY = GROUP_SCALE,
            translationX = GROUP_TRANSLATE,
            translationY = GROUP_TRANSLATE,
            clipPathData = emptyList()
        )
        addPath(
            pathData = PathParser().parsePathString(PATH_DATA).toNodes(),
            pathFillType = PathFillType.NonZero,
            name = "axeron",
            fill = SolidColor(DEFAULT_TINT)
        )
        clearGroup()
        build()
    }
}
