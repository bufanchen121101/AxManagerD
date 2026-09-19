package frb.axeron.manager.ai

import android.util.Log
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端 AI 对话服务（OpenAI 兼容 chat completions）。
 *
 * 供拦截界面问答区与 ChatScreen 复用。支持流式（SSE）与非流式两种模式。
 */
object AIChatService {

    private const val TAG = "AIChatService"

    /** 单条消息 */
    data class ChatMessage(val role: String, val content: String)

    /**
     * 派生 chat completions URL（复用 AIEngineManager 的 base 提取逻辑）。
     */
    private fun chatCompletionsUrl(endpoint: String, provider: String): String {
        val base = try {
            val url = java.net.URL(endpoint)
            val path = url.path
            val m = Regex("/v\\d+").find(path)
            if (m != null) {
                "${url.protocol}://${url.authority}${path.substring(0, m.range.first)}"
            } else {
                "${url.protocol}://${url.authority}"
            }
        } catch (e: Exception) {
            endpoint.trimEnd('/')
        }
        val versionPath = when (provider) {
            "智谱 (ChatGLM)" -> "/v4/chat/completions"
            "豆包 (火山)" -> "/v3/chat/completions"
            else -> "/v1/chat/completions"
        }
        return "$base$versionPath"
    }

    /** 是否已配置可用的云端对话 */
    fun isCloudConfigured(): Boolean {
        if (AIConfigStore.useOfficialAi) return true // 官方默认 AI（英伟达）始终可用
        return !AIConfigStore.cloudApiKey.isNullOrBlank() &&
            !AIConfigStore.cloudEndpoint.isNullOrBlank()
    }

    /** 最近一次云端调用的失败原因（用于在 UI 上给出可诊断提示，避免只说"API 有问题"）。 */
    @Volatile
    var lastError: String? = null
        private set

    private fun fail(msg: String): String? {
        lastError = msg
        Log.e(TAG, msg)
        return null
    }

    /**
     * 解析当前生效的云端配置（endpoint, apiKey, model, provider）。
     * 官方默认 AI 开启时返回英伟达 nemotron 固定配置；否则返回用户自定义配置。
     */
    private fun resolveCloudConfig(): Config {
        return if (AIConfigStore.useOfficialAi) {
            Config(
                endpoint = AIConfigStore.OFFICIAL_AI_ENDPOINT,
                apiKey = AIConfigStore.OFFICIAL_AI_API_KEY,
                model = AIConfigStore.OFFICIAL_AI_MODEL,
                provider = AIConfigStore.OFFICIAL_AI_PROVIDER,
            )
        } else {
            Config(
                endpoint = AIConfigStore.cloudEndpoint.orEmpty(),
                apiKey = AIConfigStore.cloudApiKey.orEmpty(),
                model = AIConfigStore.cloudModelName.orEmpty(),
                provider = AIConfigStore.cloudProvider.orEmpty(),
            )
        }
    }

    private data class Config(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val provider: String,
    )

    /**
     * AI 是否可用（仅云端已配置）。
     * 本地模型功能已移除，仅依赖云端配置。
     */
    fun isAiAvailable(): Boolean {
        return isCloudConfigured()
    }

    /**
     * 非流式单轮对话，返回完整回复文本。
     *
     * @param system  系统提示词
     * @param prompt  用户问题
     * @param modelOverride 可选，覆盖配置里的模型名（用于双 AI 场景，如审查模型）
     * @return 失败返回 null
     */
    suspend fun chatOnce(
        system: String,
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        modelOverride: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val cfg = resolveCloudConfig()
            val endpoint = cfg.endpoint
            val apiKey = cfg.apiKey
            val model = modelOverride?.takeIf { it.isNotBlank() } ?: cfg.model

            if (endpoint.isBlank() || apiKey.isBlank() || model.isBlank()) {
                return@withContext null
            }

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", system))
            history.forEach { h ->
                messages.put(JSONObject().put("role", h.role).put("content", h.content))
            }
            messages.put(JSONObject().put("role", "user").put("content", prompt))

            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", AIConfigStore.cloudTemperature.toDouble())
                .toString()

            val request = Request.Builder()
                .url(chatCompletionsUrl(endpoint, cfg.provider))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = axeronApp.okhttpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "chatOnce HTTP ${resp.code} model=$model url=${chatCompletionsUrl(endpoint, cfg.provider)}")
                    return@withContext null
                }
                val json = resp.body?.string() ?: run {
                    Log.e(TAG, "chatOnce 空响应 body")
                    return@withContext null
                }
                val root = JSONObject(json)
                val choices = root.optJSONArray("choices") ?: run {
                    Log.e(TAG, "chatOnce 无 choices: ${json.take(200)}")
                    return@withContext null
                }
                if (choices.length() == 0) return@withContext null
                choices.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
            }
        } catch (e: Exception) {
            Log.e(TAG, "chatOnce 异常", e)
            null
        }
    }

    /**
     * 流式单轮对话（SSE）。
     *
     * @param onDelta 每个增量文本片段回调
     * @return 完整文本；失败返回 null
     */
    suspend fun chatStream(
        system: String,
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        onDelta: (String) -> Unit,
        modelOverride: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        lastError = null
        try {
            val cfg = resolveCloudConfig()
            val endpoint = cfg.endpoint
            val apiKey = cfg.apiKey
            val model = modelOverride?.takeIf { it.isNotBlank() } ?: cfg.model

            if (endpoint.isBlank() || apiKey.isBlank() || model.isBlank()) {
                return@withContext fail("云端配置不完整（网址/Key/模型有空项）")
            }

            // —— 上下文裁剪（修复「官方免费模型只能对话一次」）——
            // 原因：官方 nemotron 为 reasoning 模型，system 提示词很长，且多轮对话会把
            // 全部历史（含很长的 AI 回复）原样重发，token 迅速累积，第二轮起极易超限/
            // 触发服务端限流而失败。这里对官方模型做保守裁剪：短 system + 仅保留最近 N 轮。
            val isOfficial = AIConfigStore.useOfficialAi
            val systemToSend = if (isOfficial) system.take(1200) else system
            val maxHistory = if (isOfficial) 6 else 20
            val trimmedHistory = if (history.size > maxHistory) {
                history.takeLast(maxHistory)
            } else {
                history
            }

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemToSend))
            trimmedHistory.forEach { h ->
                // 单条历史过长也裁剪，避免 reasoning 模型输入爆炸
                val content = if (isOfficial) h.content.take(2000) else h.content
                messages.put(JSONObject().put("role", h.role).put("content", content))
            }
            messages.put(JSONObject().put("role", "user").put("content", prompt.take(6000)))

            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", AIConfigStore.cloudTemperature.toDouble())
                .put("stream", true)
                .toString()

            val request = Request.Builder()
                .url(chatCompletionsUrl(endpoint, cfg.provider))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            // 官方免费服务首响可能很慢（reasoning 模型），用更宽松的超时客户端。
            val client = axeronApp.okhttpClient.newBuilder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    return@withContext fail("云端返回 HTTP ${resp.code}：${errBody.take(300)}")
                }
                val source = resp.body?.source() ?: return@withContext fail("云端响应为空")
                val sb = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = parseDelta(data) ?: continue
                    sb.append(delta)
                    onDelta(delta)
                }
                if (sb.isEmpty()) {
                    return@withContext fail("云端未返回任何内容（可能被限流或模型不可用）")
                }
                sb.toString()
            }
        } catch (e: Exception) {
            fail("云端调用异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 解析 SSE 块中的增量文本。 */
    private fun parseDelta(data: String): String? {
        return try {
            val root = JSONObject(data)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            choices.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content")
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
