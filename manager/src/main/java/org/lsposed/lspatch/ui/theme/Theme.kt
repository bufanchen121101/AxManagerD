package org.lsposed.lspatch.ui.theme

import org.lsposed.lspatch.util.findActivity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import org.lsposed.lspatch.ui.appearance.LSPATCH_SEED
import org.matrix.vector.ui.theme.SeedScheme
import org.matrix.vector.ui.theme.ThemeMode
import org.matrix.vector.ui.theme.toAmoled

/**
 * The app theme, resolved from the user's appearance choices.
 *
 * The amber brand palette is LSPatch's default so its identity is visible out of the box; dynamic
 * colour is opt-in and only exists from Android 12 onward. [amoled] collapses dark backgrounds to
 * true black over whichever dark scheme is in use. Built the same way as Vector's theme so the two
 * apps resolve appearance identically.
 */
@Composable
fun LSPTheme(
    themeMode: String = ThemeMode.System.key,
    dynamicColor: Boolean = false,
    seed: Int = LSPATCH_SEED,
    amoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark =
        when (ThemeMode.from(themeMode)) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var colorScheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        else -> remember(seed, dark) { SeedScheme.of(seed, dark) }
    }
    if (dark && amoled) colorScheme = colorScheme.toAmoled()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // findActivity, not `as Activity`: a language override wraps the context, and the blind
            // cast would crash the theme the moment a language is chosen.
            view.context.findActivity()?.window?.statusBarColor = colorScheme.background.toArgb()
            ViewCompat.getWindowInsetsController(view)?.isAppearanceLightStatusBars = !dark
        }
    }

    // MaterialTheme (standard, non-expressive) so the app resolves the same way as AxManager's
    // existing theme. The expressive component defaults / motion scheme of the original Vector theme
    // are internal in the current material3 alpha, so the tonal-container and motion niceties are
    // dropped here rather than crash on an internal API.
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
