package frb.axeron.manager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import frb.axeron.manager.ui.viewmodel.SettingsViewModel

fun hexToColor(hex: String): Color {
    val cleanHex = hex.removePrefix("#")
    val cleanDefault = basePrimaryDefault.toHexString().removePrefix("#")
    return when (cleanHex.length) {
        6 -> Color(("FF$cleanHex").toLong(16))
        8 -> Color(cleanHex.toLong(16))
        else -> Color(cleanDefault.toLong(16))
    }
}

@Composable
fun AxManagerTheme(
    // Dynamic color is available on Android 12+ ,
    contentCompose: @Composable (SettingsViewModel) -> Unit
) {
    val settingsViewModel: SettingsViewModel = viewModel<SettingsViewModel>()
    val context = LocalContext.current

    val darkTheme = when (settingsViewModel.getAppThemeId) {
        1 -> true
        2 -> false
        else -> isSystemInDarkTheme()
    }

    val dynamicColor = settingsViewModel.isDynamicColorEnabled
    val customPrimaryColor =
        hexToColor(settingsViewModel.customPrimaryColorHex)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            when {
                darkTheme -> dynamicDarkColorScheme(context)
                else -> dynamicLightColorScheme(context)
            }
        }

        darkTheme -> getVortexDarkColorScheme(customPrimaryColor)
        else -> getVortexLightColorScheme(customPrimaryColor)
    }

    // —— 自定义背景（设置 → 外观）——
    // 默认关闭：下面的参数化、色板替换、描边开关全部不生效，外观与改动前逐像素一致。
    // 开启后：容器色转透明/半透明（底图透出）+ 卡片描边开关置真（glassEdge 才画那条白线）。
    var backgroundEnabled by remember { mutableStateOf(BackgroundImageSettings.isEnabled(context)) }
    var backgroundPath by remember { mutableStateOf(BackgroundImageSettings.getPath(context)) }
    DisposableEffect(context) {
        val listener = BackgroundImageSettings.registerListener(context) {
            backgroundEnabled = BackgroundImageSettings.isEnabled(context)
            backgroundPath = BackgroundImageSettings.getPath(context)
        }
        onDispose { BackgroundImageSettings.unregisterListener(context, listener) }
    }

    val backgroundParams = BackgroundImageParams(
        enabled = backgroundEnabled,
        path = if (backgroundEnabled) backgroundPath else null,
        // 底图之上压一层极淡的色罩，保证文字可读（亮色主题提亮、暗色主题压暗）。
        scrim = if (darkTheme) {
            Color(0xFF000000).copy(alpha = 0.35f)
        } else {
            Color(0xFFFFFFFF).copy(alpha = 0.30f)
        },
    )

    val finalColorScheme =
        if (backgroundEnabled) glassBackgroundColorScheme(colorScheme, darkTheme) else colorScheme

    CompositionLocalProvider(
        LocalBackgroundImage provides backgroundParams,
        LocalGlassEdgeEnabled provides backgroundEnabled,
    ) {
        MaterialTheme(
            colorScheme = finalColorScheme,
            typography = Typography
        ) {
            contentCompose(settingsViewModel)
        }
    }
}