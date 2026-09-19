package frb.axeron.manager.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/**
 * 液态玻璃（Liquid Glass）全局参数。
 *
 * 设计原则（吸取第一版教训）：
 *  - 模糊只作用于「栏 / 控件背后透出的内容」，绝不糊住内容本身；
 *  - 玻璃层是叠加在内容之上的半透明层，前景文字/图标保持 100% 清晰。
 */
data class LiquidGlassParams(
    val enabled: Boolean = false,
    /** 底栏/顶栏模糊半径（dp），对齐参考项目 liquid-glass-lab 的 Haze 默认值 */
    val blurRadiusDp: Float = 24f,
    /** 玻璃染色（含 alpha），透出的内容会被这层淡色覆盖 */
    val tint: Color = Color.Unspecified,
)

val LocalLiquidGlass = staticCompositionLocalOf { LiquidGlassParams() }

/** 偏好读写：独立 SharedPreferences，不依赖核心 API 的 settings 表。 */
object LiquidGlassSettings {
    private const val PREFS = "axmanager_liquid_glass"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cachedEnabled: Boolean? = null

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

    /**
     * 注册偏好变化监听（用于让 UI 实时响应设置页的开关）。
     * 返回 listener 引用，供 [unregisterListener] 反注册。
     */
    fun registerListener(
        context: Context,
        onChanged: (Boolean) -> Unit
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) {
                val v = prefs(context).getBoolean(KEY_ENABLED, false)
                cachedEnabled = v
                onChanged(v)
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

    /** 供 Composable 侧读取当前偏好，生成玻璃参数。 */
    @Composable
    fun current(): LiquidGlassParams {
        val context = LocalContext.current
        val dark = isSystemInDarkTheme()
        return LiquidGlassParams(
            enabled = isEnabled(context),
            blurRadiusDp = 24f,
            tint = if (dark) {
                Color(0xFF1C1B1F).copy(alpha = 0.55f)
            } else {
                Color(0xFFFFFFFF).copy(alpha = 0.55f)
            },
        )
    }
}