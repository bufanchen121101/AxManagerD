package frb.axeron.manager.ui.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import frb.axeron.manager.ui.theme.BackgroundImageParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用背景图层（设置 → 外观 → 自定义背景）。
 *
 * - 关闭或没有图片时**什么都不画**（`return`），不占任何绘制开销；
 * - 开启时：先把图片铺满（Crop），再压一层极淡色罩保证文字可读；
 * - 图片在 IO 线程解码一次（produceState 按路径缓存），不阻塞首帧。
 *
 * 挂载位置：AxActivity 内容层的最底部 —— 这样它同时是液态玻璃底栏的采样源，
 * 底栏玻璃能对背景图做真实的折射/模糊。
 */
@Composable
fun AppBackgroundLayer(
    params: BackgroundImageParams,
    modifier: Modifier = Modifier,
) {
    val path = params.path
    if (!params.enabled || path.isNullOrEmpty()) return

    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // 只解码一次；图片在保存时已按屏幕比例裁剪并按屏幕宽度缩放，内存可控。
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val image = bitmap ?: return

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(params.scrim)
        )
    }
}