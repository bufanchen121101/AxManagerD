package frb.axeron.manager.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * LSPatch 官方图标（矢量路径，非位图资源）。
 *
 * 来源：LSPosed/LSPatch
 *   `manager/src/main/res/drawable/ic_launcher_foreground.xml`
 *   Copyright (C) 2022 LSPosed Contributors — GPL-3.0
 *
 * 说明：内联官方 pathData 字符串，交由 [PathParser] 解析为节点，
 * 再通过 [ImageVector.Builder.addPath] 构建。坐标不做任何手工换算，
 * 渲染结果与官方 vector drawable 保持一致。
 */
object LspatchIcons {

    private const val NAME = "LspatchMark"

    /** 官方 pathData（108x108 viewport，含主体 + 4 个圆点 + 2 个镂空菱形）。 */
    private const val PATH_DATA =
        "M167.13,107.36l27.34,-27.34c2.65,-2.67 2.65,-6.97 0,-9.64l-29.8,-29.39c-2.67,-2.65 " +
            "-6.97,-2.65 -9.64,0l-27.34,27.34 -27.34,-27.34c-1.22,-1.21 -2.86,-1.92 -4.58,-1.98 " +
            "-1.79,0 -3.51,0.72 -4.79,1.98l-29.67,29.67c-2.47,2.63 -2.47,6.73 0,9.37l27.34,27.34 " +
            "-27.34,27.34c-2.65,2.67 -2.65,6.97 0,9.64l29.67,29.67c2.67,2.65 6.97,2.65 9.64,0" +
            "l27.34,-27.34 27.34,27.34c1.29,1.28 3.04,1.99 4.85,1.98 1.82,0.01 3.56,-0.7 " +
            "4.85,-1.98l29.67,-29.67c2.65,-2.67 2.65,-6.97 0,-9.64l-27.55,-27.34Z" +
            "M127.69,86.85c3.78,0 6.84,3.06 6.84,6.84s-3.06,6.84 -6.84,6.84 -6.84,-3.06 " +
            "-6.84,-6.84 3.06,-6.84 6.84,-6.84Z" +
            "M95.77,100.52l-24.81,-25.02 24.81,-24.81 25.09,24.75 -25.09,25.09Z" +
            "M114.02,114.19c-3.78,0 -6.84,-3.06 -6.84,-6.84s3.06,-6.84 6.84,-6.84 6.84,3.06 " +
            "6.84,6.84 -3.06,6.84 -6.84,6.84Z" +
            "M127.69,127.86c-3.78,0 -6.84,-3.06 -6.84,-6.84s3.06,-6.84 6.84,-6.84 6.84,3.06 " +
            "6.84,6.84 -3.06,6.84 -6.84,6.84Z" +
            "M141.36,100.52c3.78,0 6.84,3.06 6.84,6.84s-3.06,6.84 -6.84,6.84 -6.84,-3.06 " +
            "-6.84,-6.84 3.06,-6.84 6.84,-6.84Z" +
            "M159.54,164.37l-24.81,-24.75 24.81,-24.81 24.75,24.75 -24.75,24.81Z"

    /**
     * 官方 group 变换参数（源自 ic_launcher_foreground.xml）。
     * 官方 108 viewport 下图形实际只占中间约 62 单位，直接沿用会显得偏小；
     * 这里把 viewport 收窄到 [VIEWPORT] 并同步抵消平移，使图形铺满图标方框。
     */
    private const val GROUP_SCALE = 0.27421874f
    private const val GROUP_TRANSLATE = 18.9f

    /** 图形在官方 108 画布中的实际外接尺寸与左上偏移。 */
    private const val MARK_SIZE = 62f
    private const val MARK_LEFT = 23.2f

    /** 收窄后的 viewport：图形铺满（24dp 图标内视觉尺寸与 Material 图标一致）。 */
    private const val VIEWPORT = MARK_SIZE

    /** 未选中态（由外层 Icon 的 tint 上色）。 */
    val XposedOutlined: ImageVector by lazy { build() }

    /** 选中态（与未选中共用同一几何，仅由外层 Icon 的 tint 区分）。 */
    val XposedFilled: ImageVector by lazy { build() }

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
            // 抵消图形在官方 108 画布中的左上偏移，使其对齐收窄后的 viewport 原点。
            translationX = GROUP_TRANSLATE - MARK_LEFT,
            translationY = GROUP_TRANSLATE - MARK_LEFT,
            clipPathData = emptyList()
        )
        addPath(
            pathData = PathParser().parsePathString(PATH_DATA).toNodes(),
            pathFillType = PathFillType.NonZero,
            name = "lspatch",
            fill = SolidColor(Color.Black)
        )
        clearGroup()
        build()
    }
}