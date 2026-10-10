package frb.axeron.manager.ui.glass

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/** RenderEffect（模糊/链式效果）从 Android 12 / API 31 起可用。 */
@ChecksSdkIntAtLeast(Build.VERSION_CODES.S)
fun isRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** AGSL RuntimeShader（真正的折射/色散）从 Android 13 / API 33 起可用。 */
@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
