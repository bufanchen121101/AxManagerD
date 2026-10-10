package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import frb.axeron.manager.R
import frb.axeron.manager.ui.theme.BackgroundImageSettings
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** 背景图文件读写（全部在应用私有目录，不写相册、不需要任何存储权限）。 */
object BackgroundImageStore {

    /**
     * 把相册/文件选择器返回的 Uri 解码成一张「够裁剪用」的位图。
     * 先只读边界拿原始尺寸，再按最大边降采样到 [maxDim] —— 避免几千万像素原图直接 OOM。
     */
    fun decodeForCrop(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        var sample = 1
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    /**
     * 保存裁剪结果：先按目标宽度等比缩放（超过 [targetWidthPx] 才缩），再以 JPEG 落盘。
     * 只保留最新一张，避免私有目录越攒越大。返回绝对路径；失败返回 null。
     */
    fun save(context: Context, bitmap: Bitmap, targetWidthPx: Int): String? = runCatching {
        val scaled = if (targetWidthPx > 0 && bitmap.width > targetWidthPx) {
            val h = (bitmap.height * (targetWidthPx.toFloat() / bitmap.width)).roundToInt()
            Bitmap.createScaledBitmap(bitmap, targetWidthPx, h, true)
        } else {
            bitmap
        }

        val dir = BackgroundImageSettings.imageDir(context)
        // 先清掉旧图，只保留一份
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val file = File(dir, "bg_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        file.absolutePath
    }.getOrNull()
}

/** 裁剪框相对可用区域的水平内边距（预览与按钮共用同一套换算）。 */
private val CROP_FRAME_HORIZONTAL_PADDING = 16.dp

/**
 * 在给定可用区域里算出「屏幕比例」裁剪框的像素尺寸。
 * 纯函数（不读任何 Composable 上下文），供预览与最终裁剪共用，保证所见即所得。
 */
private fun computeFrameSize(areaWpx: Float, areaHpx: Float, aspectRatio: Float, padPx: Float): Offset {
    val availW = areaWpx - padPx * 2f
    // 上下各留一点，避免裁剪框贴死可点区域
    val availH = areaHpx - padPx
    if (availW <= 0f || availH <= 0f || aspectRatio <= 0f) return Offset.Zero
    val w = if (availW / availH > aspectRatio) availH * aspectRatio else availW
    return Offset(w, w / aspectRatio)
}

/**
 * 背景裁剪界面（全屏对话框）。
 *
 * - 裁剪框比例 = [aspectRatio]（宽/高，由调用方传「手机屏幕比例」）；
 * - 双指缩放（1x–8x）+ 单指拖动；拖动范围被夹住，**任何情况下都不会露出黑边**；
 * - 确定后按当前可视区域从原图裁出对应矩形，交给 [onConfirm]。
 */
@Composable
fun BackgroundCropDialog(
    source: Bitmap,
    aspectRatio: Float,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val image = remember(source) { source.asImageBitmap() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    val padPx = with(density) { CROP_FRAME_HORIZONTAL_PADDING.toPx() }
    val frame = remember(areaSize, aspectRatio, padPx) {
        computeFrameSize(
            areaWpx = areaSize.width.toFloat(),
            areaHpx = areaSize.height.toFloat(),
            aspectRatio = aspectRatio,
            padPx = padPx
        )
    }
    val frameWpx = frame.x
    val frameHpx = frame.y

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xF7000000)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.custom_background_crop_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.custom_background_crop_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { areaSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    if (frameWpx > 0f && frameHpx > 0f) {
                        Box(
                            modifier = Modifier
                                .size(
                                    width = with(density) { frameWpx.toDp() },
                                    height = with(density) { frameHpx.toDp() }
                                )
                                .clip(RoundedCornerShape(20.dp))
                                .clipToBounds()
                                .background(Color.Black)
                                .pointerInput(frameWpx, frameHpx, source) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, 8f)
                                        val s = baseScale(frameWpx, frameHpx, source) * newScale
                                        // 夹住位移：图片始终覆盖裁剪框，不露黑边
                                        val maxX = ((source.width * s - frameWpx) / 2f).coerceAtLeast(0f)
                                        val maxY = ((source.height * s - frameHpx) / 2f).coerceAtLeast(0f)
                                        val nx = (offset.x + pan.x).coerceIn(-maxX, maxX)
                                        val ny = (offset.y + pan.y).coerceIn(-maxY, maxY)
                                        scale = newScale
                                        offset = Offset(nx, ny)
                                    }
                                }
                        ) {
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.custom_background_crop_cancel),
                            color = Color.White
                        )
                    }
                    Button(
                        onClick = {
                            val cropped = runCatching {
                                cropBitmap(source, frameWpx, frameHpx, scale, offset)
                            }.getOrNull()
                            if (cropped != null) onConfirm(cropped) else onDismiss()
                        },
                        enabled = frameWpx > 0f && frameHpx > 0f,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(text = stringResource(R.string.custom_background_crop_confirm))
                    }
                }
            }
        }
    }
}

/** ContentScale.Crop 的基础缩放：保证图片铺满裁剪框。 */
private fun baseScale(frameWpx: Float, frameHpx: Float, source: Bitmap): Float =
    if (source.width <= 0 || source.height <= 0) 1f
    else max(frameWpx / source.width, frameHpx / source.height)

/**
 * 按「预览时的手势状态」从原图裁出可见区域。
 *
 * 推导（与预览完全同一套公式）：
 *  基础缩放 base = max(frameW/iw, frameH/ih)（Crop 铺满）
 *  实际缩放 s = base * scale；图片中心 = 框中心 + offset
 *  → 框左上角在图片坐标 = (iw*s/2 - (frameW/2 + offset.x)) / s
 */
private fun cropBitmap(
    source: Bitmap,
    frameWpx: Float,
    frameHpx: Float,
    scale: Float,
    offset: Offset,
): Bitmap {
    if (frameWpx <= 0f || frameHpx <= 0f) return source
    val iw = source.width.toFloat()
    val ih = source.height.toFloat()
    val s = baseScale(frameWpx, frameHpx, source) * scale
    if (s <= 0f) return source

    val leftF = (iw * s / 2f - (frameWpx / 2f + offset.x)) / s
    val topF = (ih * s / 2f - (frameHpx / 2f + offset.y)) / s
    val wF = frameWpx / s
    val hF = frameHpx / s

    val x = leftF.roundToInt().coerceIn(0, max(0, source.width - 1))
    val y = topF.roundToInt().coerceIn(0, max(0, source.height - 1))
    val w = wF.roundToInt().coerceIn(1, source.width - x)
    val h = hF.roundToInt().coerceIn(1, source.height - y)
    return Bitmap.createBitmap(source, x, y, w, h)
}