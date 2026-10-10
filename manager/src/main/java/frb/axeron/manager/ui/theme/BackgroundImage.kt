package frb.axeron.manager.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import java.io.File

/**
 * 自定义背景图（设置 → 外观）。
 *
 * 设计原则：
 *  - 默认关闭，关闭时**不产生任何视觉影响**（色板原样、卡片描边不画）；
 *  - 开启后：图片画在 App 最底层，主题的容器色整体转为透明/半透明，让图片透出来；
 *    文字色保持原样，再叠一层极淡的压暗/提亮层保证可读性。
 *  - 偏好独立使用一个 SharedPreferences，不污染核心 settings 表。
 */
data class BackgroundImageParams(
    val enabled: Boolean = false,
    /** 已保存的背景图绝对路径；null 表示尚未选择过图片。 */
    val path: String? = null,
    /** 覆盖在图片上的压暗（暗色主题）/ 提亮（亮色主题）层，用于保证文字可读。 */
    val scrim: Color = Color.Unspecified,
)

val LocalBackgroundImage = staticCompositionLocalOf { BackgroundImageParams() }

/**
 * 「卡片玻璃描边」开关。
 * 只有在自定义背景开启时才为 true —— 所以 [glassEdge] 在默认状态下完全不生效。
 */
val LocalGlassEdgeEnabled = staticCompositionLocalOf { false }

object BackgroundImageSettings {
    private const val PREFS = "axmanager_background"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PATH = "path"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cachedEnabled: Boolean? = null

    @Volatile
    private var cachedPath: String? = null

    @Volatile
    private var cachedPathLoaded = false

    fun isEnabled(context: Context): Boolean {
        cachedEnabled?.let { return it }
        val v = prefs(context).getBoolean(KEY_ENABLED, false)
        cachedEnabled = v
        return v
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /** 是否已经选过图片（有落盘文件才算）。 */
    fun hasImage(context: Context): Boolean {
        val p = getPath(context) ?: return false
        return File(p).let { it.exists() && it.length() > 0 }
    }

    fun getPath(context: Context): String? {
        if (!cachedPathLoaded) {
            cachedPath = prefs(context).getString(KEY_PATH, null)
            cachedPathLoaded = true
        }
        return cachedPath
    }

    fun setPath(context: Context, path: String?) {
        cachedPath = path
        cachedPathLoaded = true
        prefs(context).edit { putString(KEY_PATH, path) }
    }

    /** 清空背景图（含落盘文件）并关闭开关。 */
    fun clear(context: Context) {
        val old = getPath(context)
        setPath(context, null)
        setEnabled(context, false)
        if (old != null) runCatching { File(old).delete() }
    }

    /** 背景图落盘目录：filesDir/background（应用私有，不会被相册/其他应用看到）。 */
    fun imageDir(context: Context): File =
        File(context.filesDir, "background").apply { if (!exists()) mkdirs() }

    /** 注册偏好变化监听（供 UI 实时响应设置页开关）。 */
    fun registerListener(
        context: Context,
        onChanged: () -> Unit
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED || key == KEY_PATH) {
                cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
                cachedPath = prefs(context).getString(KEY_PATH, null)
                cachedPathLoaded = true
                onChanged()
            }
        }
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** 供 Composable 侧读取当前参数（含按主题生成的 scrim 颜色）。 */
    @Composable
    fun current(): BackgroundImageParams {
        val context = LocalContext.current
        val dark = isSystemInDarkTheme()
        val enabled = isEnabled(context)
        return BackgroundImageParams(
            enabled = enabled,
            path = if (enabled) getPath(context) else null,
            // 暗色主题：压黑一点，让浅色文字仍清楚；亮色主题：提亮一点，让深色文字仍清楚。
            scrim = if (dark) Color(0xFF000000).copy(alpha = 0.35f)
            else Color(0xFFFFFFFF).copy(alpha = 0.30f),
        )
    }
}

/**
 * 开启自定义背景时使用的色板。
 *
 * 只改「容器类」颜色角色 → 让底图透出来：
 *  - background / surface / surfaceDim / surfaceBright → 完全透明（页面底色消失）
 *  - surfaceVariant 与 surfaceContainer 五档 → 极淡的半透明白（卡片仍有一点点体积感）
 * 文字色（onSurface / onSurfaceVariant 等）**一个都不动**，所以所有文字照常显示。
 *
 * 关闭自定义背景时不会调用本函数，色板与改动前完全一致。
 */
fun glassBackgroundColorScheme(base: ColorScheme, dark: Boolean): ColorScheme = base.copy(
    background = Color.Transparent,
    surface = Color.Transparent,
    surfaceDim = Color.Transparent,
    surfaceBright = Color.Transparent,
    surfaceVariant = glassFill(dark, 0.10f),
    surfaceContainerLowest = glassFill(dark, 0.05f),
    surfaceContainerLow = glassFill(dark, 0.08f),
    surfaceContainer = glassFill(dark, 0.10f),
    surfaceContainerHigh = glassFill(dark, 0.14f),
    surfaceContainerHighest = glassFill(dark, 0.18f),
)

/**
 * 卡片填充色：暗色主题用白（提亮）、亮色主题用白但更高不透明度（避免被照片冲淡）。
 * 之所以亮色也用白：底图之上叠的是白玻璃，文字是深色，对比度反而更稳。
 */
private fun glassFill(dark: Boolean, alpha: Float): Color =
    if (dark) Color.White.copy(alpha = alpha)
    else Color.White.copy(alpha = alpha + 0.14f)
