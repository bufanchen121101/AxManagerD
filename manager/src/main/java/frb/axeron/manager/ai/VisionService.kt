package frb.axeron.manager.ai

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import frb.axeron.manager.ai.axkey.AxKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 识图（OCR / 视觉模型）服务。
 *
 * 两条路由，与对话链路的「官方 / 自定义」口径一致：
 *  - 官方：中转站 [AIConfigStore.OFFICIAL_AI_ENDPOINT] + 当日动态密钥（[AxKeyProvider]），
 *    模型固定为 [AIConfigStore.OFFICIAL_VISION_MODEL]（Nemotron-3.5 Super VL）；
 *  - 自定义：用户填写的 Base URL / Key / 模型名（选择自定义 API 时在识图面板里显示）。
 *
 * 请求体用 OpenAI 兼容的多模态格式：content = [{type:text},{type:image_url}]。
 * 图片一律以 data URL（data:image/jpeg;base64,…）内联发送，不落盘、不上传第三方存储。
 */
object VisionService {
    private const val TAG = "VisionService"

    /** OCR 模式：只提取文字。 */
    const val PROMPT_OCR =
        "请逐字提取这张图片中的所有文字，保持原有换行与阅读顺序；" +
            "不要翻译、不要解释、不要总结。若图片中没有文字，只回答：未识别到文字。"

    /** 描述模式：普通看图。 */
    const val PROMPT_DESCRIBE =
        "请用中文详细描述这张图片的内容；如果图片里有文字，请把文字完整抄出来。"

    /** 识图结果：成功时 [text] 是模型输出，失败时 [error] 是可直接展示的原因。 */
    data class VisionResult(
        val ok: Boolean,
        val text: String = "",
        val error: String? = null,
    )

    /**
     * 【1.4.3】识图当前是否可用（与 [AIConfigStore.visionAvailable] 同一口径）。
     *
     * 供工具层判断「要不要把 VISION 挂进清单」以及「调用时直接拒绝」，
     * 避免官方 nemotron 关掉、自定义 API 又没填时发起必然失败的请求。
     */
    fun isAvailable(): Boolean = AIConfigStore.visionAvailable()

    /**
     * 【1.4.3】把工程内的图片文件读成 data URL（供 <<<VISION:路径|要求>>> 使用）。
     *
     * 与附件选择一致：长边压到 [maxSide] 以内、JPEG 质量 [quality]，避免请求体过大。
     * 解码失败（不是图片 / 文件损坏 / 越权路径）返回 null，由调用方给出明确提示。
     */
    fun fileToDataUrl(file: File, maxSide: Int = 1024, quality: Int = 78): String? {
        return try {
            if (!file.isFile || file.length() <= 0L) return null
            val raw = file.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var scale = 1
            while (longest / scale > maxSide) scale *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = scale }
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            if (!bmp.isRecycled) bmp.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e(TAG, "fileToDataUrl 失败", t)
            null
        }
    }

    /**
     * 把用户填的 Base URL 归一成 chat/completions 端点。
     *
     * 三种常见写法都要能吃下：
     *  - `https://host/v1`                → `https://host/v1/chat/completions`
     *  - `https://host/v1/chat/completions` → 原样
     *  - `https://host`                   → 按 provider 补 `/v1`（GLM 用 /v4、豆包用 /v3）
     */
    private fun chatUrl(base: String, provider: String): String {
        val b = base.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) return b
        if (Regex("/v\\d+$").containsMatchIn(b)) return "$b/chat/completions"
        val version = when (provider) {
            "智谱 (ChatGLM)" -> "/v4"
            "豆包 (火山)" -> "/v3"
            else -> "/v1"
        }
        return "$b$version/chat/completions"
    }

    /**
     * 识别一张图片。
     *
     * @param imageDataUrl 形如 `data:image/jpeg;base64,…`
     * @param prompt       提示词，默认描述模式；OCR 用 [PROMPT_OCR]
     */
    suspend fun recognize(
        imageDataUrl: String,
        prompt: String = PROMPT_DESCRIBE,
    ): VisionResult = withContext(Dispatchers.IO) {
        if (!AIConfigStore.visionEnabled) {
            return@withContext VisionResult(false, error = "识图已在「识图设置」里关闭")
        }
        if (!imageDataUrl.startsWith("data:image/")) {
            return@withContext VisionResult(false, error = "图片格式不支持（需要 data:image/... 的 data URL）")
        }
        // 【1.4.5】路由统一按「有效官方路由」判断：
        // 官方默认 AI 关闭时，即使存档里还选着 nemotron，也自动改走自定义识图 API，
        // 与识图面板（官方关闭只显示自定义填写区）保持同一口径。
        if (!AIConfigStore.visionAvailable()) {
            return@withContext VisionResult(
                false,
                error = if (AIConfigStore.visionUseOfficialEffective) {
                    "官方识图不可用：官方默认 AI 未开启或访问口令缺失"
                } else {
                    "自定义识图未配置：请填写 Base URL、API Key 与模型名"
                },
            )
        }

        val endpoint: String
        val apiKey: String
        val model: String
        if (AIConfigStore.visionUseOfficialEffective) {
            val key = AxKeyProvider.ensureKey()
            if (key.isNullOrBlank()) {
                val why = AxKeyProvider.lastError ?: "请检查网络后重试"
                return@withContext VisionResult(false, error = "官方识图密钥不可用：$why")
            }
            endpoint = AIConfigStore.OFFICIAL_AI_ENDPOINT
            apiKey = key
            model = AIConfigStore.OFFICIAL_VISION_MODEL
        } else {
            val base = AIConfigStore.visionBaseUrl.trim()
            if (base.isBlank()) {
                return@withContext VisionResult(false, error = "自定义识图未填写 Base URL")
            }
            endpoint = chatUrl(base, AIConfigStore.visionProvider)
            apiKey = AIConfigStore.visionApiKey.trim()
            // 自定义路由没有「通用默认视觉模型」：官方 VL 的模型名只在官方中转站有效。
            // 留空时直接给出可操作的提示，而不是发一个必然 404 的请求。
            model = AIConfigStore.visionModel.trim()
            if (model.isBlank()) {
                return@withContext VisionResult(
                    false,
                    error = "自定义识图未填写模型名（例如 gpt-4o / qwen-vl-max）",
                )
            }
        }

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", imageDataUrl))
            )
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)
                )
            )
            .put("max_tokens", 2048)
            .put("stream", false)
            .toString()

        try {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            // 识图（尤其大图 + 大模型）比普通对话慢，单独放宽读超时。
            val client = axeronApp.okhttpClient.newBuilder()
                .readTimeout(180L, TimeUnit.SECONDS)
                .callTimeout(180L, TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (resp.code == 401 && endpoint == AIConfigStore.OFFICIAL_AI_ENDPOINT) {
                        AxKeyProvider.invalidate()
                    }
                    Log.e(TAG, "recognize HTTP ${resp.code} model=$model")
                    return@withContext VisionResult(
                        false,
                        error = "HTTP ${resp.code}：${raw.take(200)}",
                    )
                }
                val message = JSONObject(raw)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                // 视觉类模型可能只给 reasoning_content（如 Nemotron VL 的思考过程）
                val out = message?.optString("content").orEmpty()
                    .ifBlank { message?.optString("reasoning_content").orEmpty() }
                if (out.isBlank()) {
                    VisionResult(false, error = "模型没有返回内容（该模型可能不支持图片输入）")
                } else {
                    VisionResult(true, out.trim())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "recognize 异常", e)
            VisionResult(false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
