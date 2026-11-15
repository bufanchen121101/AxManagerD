package frb.axeron.manager.ai

import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URL

/**
 * 云端模型列表获取工具（参考 Operit 的 ModelListFetcher）。
 *
 * 依据"提供商 + API 网址"自动派生模型列表 URL，发送 GET 请求并解析
 * OpenAI 兼容格式（{"data":[{"id":"..."}]}）返回可用模型 id。
 */
object ModelListFetcher {

    /**
     * 从 API 端点 URL 提取 base URL（到版本路径 /v1、/v4 之前）。
     * 例：https://api.openai.com/v1/chat/completions -> https://api.openai.com
     */
    private fun extractBaseUrl(fullUrl: String): String {
        return try {
            val url = URL(fullUrl)
            val path = url.path
            val m = Regex("/v\\d+").find(path)
            if (m != null) {
                val before = path.substring(0, m.range.first)
                "${url.protocol}://${url.authority}$before"
            } else {
                "${url.protocol}://${url.authority}"
            }
        } catch (e: Exception) {
            fullUrl
        }
    }

    /**
     * 根据提供商类型派生模型列表 URL。
     * 大部分 OpenAI 兼容提供商走 /v1/models，特殊提供商（智谱 /v4、豆包 /v3）单独处理。
     */
    private fun modelsUrl(provider: String, endpoint: String): String {
        val base = extractBaseUrl(endpoint)
        return when (provider) {
            "智谱 (ChatGLM)" -> "$base/v4/models"
            "豆包 (火山)" -> "$base/v3/models"
            "Anthropic (Claude)" -> "$base/v1/models"
            else -> "$base/v1/models" // OpenAI 兼容默认
        }
    }

    /**
     * 获取模型列表。
     *
     * @param endpoint API 网址
     * @param apiKey   API Key（可空）
     * @param provider 提供商名称
     * @return 模型 id 列表（已排序）
     */
    suspend fun fetchModels(
        endpoint: String,
        apiKey: String?,
        provider: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (endpoint.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("请先填写 API 网址"))
            }

            val url = modelsUrl(provider, endpoint)
            val builder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")

            if (!apiKey.isNullOrBlank()) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = axeronApp.okhttpClient.newCall(builder.get().build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalArgumentException("请求失败：HTTP ${resp.code}")
                    )
                }
                val body = resp.body?.string() ?: return@withContext Result.failure(
                    IllegalArgumentException("响应为空")
                )
                val models = parseOpenAiModels(body)
                if (models.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("未解析到可用模型")
                    )
                }
                Result.success(models)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 解析 OpenAI 兼容格式 {"data":[{"id":"..."}]} */
    private fun parseOpenAiModels(json: String): List<String> {
        return try {
            val root = JSONObject(json)
            val data = root.optJSONArray("data") ?: return emptyList()
            val ids = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (id.isNotEmpty()) ids.add(id)
            }
            ids.sortedBy { it }
        } catch (e: Exception) {
            emptyList()
        }
    }
}