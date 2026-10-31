package frb.axeron.manager.ai

import android.util.Log
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import frb.axeron.manager.ai.axkey.AxKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端 AI 对话服务（OpenAI 兼容 chat completions）。
 *
 * 供拦截界面问答区、终端助手与「制作模块 → AI 辅助」共用。支持流式（SSE）与非流式两种模式。
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

    /**
     * 是否已配置可用的云端对话。
     *
     * 官方默认 AI（v1.4.0 起走中转站）：可用性取决于「访问密码」是否已构建期注入
     * （AXKEY_PASS_BLOB，见 manager/build.gradle.kts）；运行期再换取当日动态密钥。
     */
    fun isCloudConfigured(
        purpose: AIConfigStore.AiPurpose = AIConfigStore.AiPurpose.GENERAL,
    ): Boolean {
        // 与 resolveCloudConfig 严格同口径：只要三级路由里有一级可用就算已配置
        if (AIConfigStore.useCustomKey && AIConfigStore.hasCustomCloudConfig) return true
        if (AIConfigStore.useOfficialAi && AIConfigStore.officialAxkeyAvailable) return true
        return AIConfigStore.builtinAiAvailable
    }

    /** 最近一次云端调用的失败原因（用于在 UI 上给出可诊断提示，避免只说"API 有问题"）。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 流式回复的「空闲超时」（秒）。
     *
     * 背景：上游（尤其自建中转站/部分代理）发完最后一段内容后，常常既不发 `data: [DONE]`、
     * 也不关闭连接，只是挂着。此时 OkHttp 的 readTimeout 是整个请求级 300s，客户端会一直
     * 阻塞在 read 上 —— 表现就是「AI 的文字早就流完了，右下角还在显示生成中」。
     *
     * 处理：一旦已经收到过内容，就把读超时收紧到 [STREAM_IDLE_SEC]；再收不到新数据就
     * 当作「正常结束」（保留已生成内容），而不是等 300s 后报错。
     */
    private const val STREAM_IDLE_SEC = 45L

    /** 最近一次请求是否打到官方默认地址（中转站）。调用方据此把用量记到对应桶。 */
    var lastOfficial: Boolean = false
        private set

    /** 当前正在执行的 HTTP 调用；「停止生成」靠它真正关闭连接，而不是只停 UI。 */
    private var activeCall: okhttp3.Call? = null

    /**
     * 立即中断正在进行的请求。
     *
     * 为什么必须取消 OkHttp 的 Call：`execute()` 是阻塞调用，
     * 只 `job.cancel()` 的话线程还挂在 socket 上继续读，实际并没有断，
     * 用户会看到「点了停止但还在慢慢出字」。
     */
    fun cancelActive() {
        runCatching { activeCall?.cancel() }
    }

    private fun fail(msg: String): String? {
        lastError = msg
        Log.e(TAG, msg)
        return null
    }

    /**
     * 解析当前生效的云端配置（endpoint, apiKey, model, provider）。
     *
     * 官方默认 AI 开启时：endpoint = 中转站（含隐藏入口前缀）；apiKey = 中转站「当日
     * 动态密钥」（[AxKeyProvider.ensureKey] 运行期换取；失败时为空串，精确原因见
     * [AxKeyProvider.lastError]）；model = 用户在「官方模型」弹窗中选择的型号。
     * 否则返回用户自定义配置。
     *
     * 【防静默回退】官方取钥失败时不使用旧密钥/静态密钥兜底，由调用方明确报错。
     */
    private suspend fun resolveCloudConfig(
        purpose: AIConfigStore.AiPurpose = AIConfigStore.AiPurpose.GENERAL,
    ): Config {
        // 【1.4.1 路由定稿】
        //  1) 自定义密钥总开关开启且配置齐全 → 全局自定义模型
        //  2) 否则走官方默认 AI：制作模块用本页所选模型，其他界面用对话页所选模型
        //  3) 官方不可用 → 内置 NVIDIA 直连兜底
        if (AIConfigStore.useCustomKey && AIConfigStore.hasCustomCloudConfig) {
            return Config(
                endpoint = AIConfigStore.cloudEndpoint.orEmpty(),
                apiKey = AIConfigStore.cloudApiKey.orEmpty(),
                model = AIConfigStore.cloudModelName?.takeIf { it.isNotBlank() }
                    ?: AIConfigStore.generalModel,
                provider = AIConfigStore.cloudProvider?.takeIf { it.isNotBlank() } ?: "Custom",
            )
        }
        if (AIConfigStore.useOfficialAi && AIConfigStore.officialAxkeyAvailable) {
            val model = if (purpose == AIConfigStore.AiPurpose.MODULE_DEV) {
                AIConfigStore.officialModelName
            } else {
                AIConfigStore.generalModel
            }
            return Config(
                endpoint = AIConfigStore.OFFICIAL_AI_ENDPOINT,
                apiKey = AxKeyProvider.ensureKey().orEmpty(),
                model = model,
                provider = AIConfigStore.OFFICIAL_AI_PROVIDER,
            )
        }
        return Config(
            endpoint = AIConfigStore.BUILTIN_AI_ENDPOINT,
            apiKey = AIConfigStore.BUILTIN_AI_API_KEY,
            model = AIConfigStore.generalModel,
            provider = AIConfigStore.BUILTIN_AI_PROVIDER,
        )
    }

    /**
     * 对外暴露「当前真正生效的路由」。
     *
     * 供自己拼 HTTP 请求的调用方（如 [AIEngineManager.analyzeViaCloud]）复用，
     * 避免各处再写一套「自定义 / 官方 / 内置」判断而与对话链路产生口径分裂。
     */
    suspend fun resolveRoute(
        purpose: AIConfigStore.AiPurpose = AIConfigStore.AiPurpose.GENERAL,
    ): AIConfigStore.AiRoute {
        val cfg = resolveCloudConfig(purpose)
        return AIConfigStore.AiRoute(
            endpoint = cfg.endpoint,
            apiKey = cfg.apiKey,
            model = cfg.model,
            provider = cfg.provider,
        )
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
        purpose: AIConfigStore.AiPurpose = AIConfigStore.AiPurpose.GENERAL,
    ): String? = withContext(Dispatchers.IO) {
        // 【1.4.0 新增】非流式调用按用户设置做有限次重试（默认 0 = 只请求一次）。
        // 只重试「整体失败」：chatOnce 是整包返回，不存在半截文本重复的问题。
        val attempts = 1 + AIConfigStore.chatRetryCount.coerceIn(0, 3)
        for (attempt in 0 until attempts) {
            val reply = chatOnceInternal(system, prompt, history, modelOverride, purpose)
            if (reply != null) return@withContext reply
            if (attempt < attempts - 1) delay(600L)
        }
        null
    }

    /** [chatOnce] 的单次实现（不含重试包装）。 */
    private suspend fun chatOnceInternal(
        system: String,
        prompt: String,
        history: List<ChatMessage>,
        modelOverride: String?,
        purpose: AIConfigStore.AiPurpose,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val cfg = resolveCloudConfig(purpose)
            val endpoint = cfg.endpoint
            lastOfficial = endpoint == AIConfigStore.OFFICIAL_AI_ENDPOINT
            val apiKey = cfg.apiKey
            val model = modelOverride?.takeIf { it.isNotBlank() } ?: cfg.model
            AiTrace.log(
                "once",
                "purpose=$purpose model=$model url=${chatCompletionsUrl(endpoint, cfg.provider)} " +
                    "key=${AiTrace.keyFingerprint(apiKey)} sys=${system.length} prompt=${prompt.length} hist=${history.size}"
            )

            if (endpoint.isBlank() || !AIConfigStore.isUsableUrl(endpoint) ||
                apiKey.isBlank() || model.isBlank()
            ) {
                AiTrace.log("once", "配置不完整 endpoint=${endpoint.isNotBlank()} key=${apiKey.isNotBlank()} model=${model.isNotBlank()}")
                // 制作模块取钥失败时记录精确原因（其余情况维持既有的静默返回 null 的行为）
                if (endpoint == AIConfigStore.OFFICIAL_AI_ENDPOINT && apiKey.isBlank()) {
                    Log.e(TAG, "chatOnce 官方密钥不可用：${AxKeyProvider.lastError ?: "未知原因"}")
                }
                return@withContext null
            }

            val messages = JSONArray()
            // 【1.5.0】「对话设置 → 系统提示词」已移除，不再追加自定义提示词
            messages.put(
                JSONObject().put("role", "system").put("content", system)
            )
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

            // 【1.4.0】按用户设置的超时发请求（0 = 沿用全局 client 的默认超时）
            val timeoutSec = AIConfigStore.chatTimeoutSec
            val client = if (timeoutSec > 0) {
                axeronApp.okhttpClient.newBuilder()
                    .readTimeout(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            } else {
                axeronApp.okhttpClient
            }
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val traceErr = runCatching { resp.peekBody(4096).string() }.getOrNull().orEmpty()
                    AiTrace.log("once", "HTTP ${resp.code} model=$model body=${traceErr.take(400)}")
                    Log.e(TAG, "chatOnce HTTP ${resp.code} model=$model url=${chatCompletionsUrl(endpoint, cfg.provider)}")
                    // 日钥失效（如服务端轮换）→ 置空本地缓存，下次请求自动重取
                    if (resp.code == 401 && endpoint == AIConfigStore.OFFICIAL_AI_ENDPOINT) {
                        AxKeyProvider.invalidate()
                    }
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
            AiTrace.log("once", "异常 ${e.javaClass.simpleName}: ${e.message}")
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
        purpose: AIConfigStore.AiPurpose = AIConfigStore.AiPurpose.GENERAL,
        /** 本轮要一起发给模型看的图片，元素是可直接进 image_url 的 data URL。 */
        images: List<String> = emptyList(),
        /** 思考增量回调（上游 delta.reasoning_content）。为 null 时思考内容被丢弃，与旧行为一致。 */
        onReasoning: ((String) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        lastError = null
        val startedAt = System.currentTimeMillis()
        try {
            val cfg = resolveCloudConfig(purpose)
            val endpoint = cfg.endpoint
            lastOfficial = endpoint == AIConfigStore.OFFICIAL_AI_ENDPOINT
            val apiKey = cfg.apiKey
            val model = modelOverride?.takeIf { it.isNotBlank() } ?: cfg.model

            if (endpoint.isBlank() || !AIConfigStore.isUsableUrl(endpoint) ||
                apiKey.isBlank() || model.isBlank()
            ) {
                // 官方取钥失败时给出精确原因（网络 / 拒绝）；其余情况维持通用提示
                return@withContext fail(
                    if (AIConfigStore.useOfficialAi && apiKey.isBlank()) {
                        AxKeyProvider.lastError ?: "官方密钥获取失败"
                    } else {
                        "云端配置不完整（网址/Key/模型有空项）"
                    }
                )
            }

            AiTrace.log(
                "chat",
                "purpose=$purpose model=$model url=${chatCompletionsUrl(endpoint, cfg.provider)} " +
                    "key=${AiTrace.keyFingerprint(apiKey)} sys=${system.length} prompt=${prompt.length} hist=${history.size} " +
                    "reason=${AIConfigStore.reasoningEnabled}/${AIConfigStore.reasoningEffort} " +
                    "max=${AIConfigStore.maxMode} imgs=${images.size}"
            )

            // —— 上下文裁剪（修复「官方免费模型只能对话一次」）——
            // 原因：官方 nemotron 为 reasoning 模型，system 提示词很长，且多轮对话会把
            // 全部历史（含很长的 AI 回复）原样重发，token 迅速累积，第二轮起极易超限/
            // 触发服务端限流而失败。这里对官方模型做保守裁剪：短 system + 仅保留最近 N 轮。
            val isOfficial = endpoint == AIConfigStore.OFFICIAL_AI_ENDPOINT
            // 【1.5.0】「对话设置 → 系统提示词」已移除，这里不再拼接自定义提示词
            val systemWithExtra = system
            val levelSuffix = if (AIConfigStore.reasoningEnabled) {
                AIConfigStore.reasoningSystemSuffix
            } else {
                ""
            }
            val systemWithLevel = systemWithExtra + levelSuffix
            // 【1.4.2】system 上限由「对话设置 → 上下文与世界」决定（0 = 自动：官方 1200 / 自定义不裁）。
            // 关键：不能直接 take()——模块开发提示词的工具协议在末尾，一截就丢工具，
            // 表现为「AI 说没有这个工具 / 可调用工具数量没变化」。
            val systemCapCfg = AIConfigStore.chatSystemChars
            val systemCap = if (systemCapCfg > 0) systemCapCfg else if (isOfficial) 1200 else 0
            val systemToSend = if (systemCap > 0) {
                trimSystemKeepTools(systemWithLevel, systemCap)
            } else {
                systemWithLevel
            }
            // 【v1.4.1】历史条数：0 = 自动（官方 6 / 自定义 20），否则用用户设定的值
            val configuredLimit = AIConfigStore.chatHistoryLimit
            val maxHistory = if (configuredLimit > 0) {
                configuredLimit
            } else if (isOfficial) {
                6
            } else {
                20
            }
            val trimmedHistory = if (history.size > maxHistory) {
                history.takeLast(maxHistory)
            } else {
                history
            }

            // 【v1.4.1】是否流式：关闭后一次性返回完整回复（弱网/中转站不稳时更稳）
            val useStream = AIConfigStore.chatStreamEnabled

            val messages = JSONArray()
            // 【1.4.0 新增】思考引导：只在用户打开时追加，不改动原有 system 语义
            val guidePrefix = if (AIConfigStore.thinkingGuide) {
                "请在内部逐步推理后再作答，只输出最终结论；信息不足时明确说明缺少什么。\n\n"
            } else {
                ""
            }
            messages.put(JSONObject().put("role", "system").put("content", guidePrefix + systemToSend))
            trimmedHistory.forEach { h ->
                // 单条历史过长也裁剪，避免 reasoning 模型输入爆炸
                // 【1.4.2】单条历史上限可调（0 = 自动：官方 2000 / 自定义不裁）
                val itemCapCfg = AIConfigStore.chatHistoryItemChars
                val itemCap = if (itemCapCfg > 0) itemCapCfg else if (isOfficial) 2000 else 0
                val content = if (itemCap > 0) h.content.take(itemCap) else h.content
                messages.put(JSONObject().put("role", h.role).put("content", content))
            }
            // 【1.4.2】用户输入上限可调（0 = 自动：官方 6000 / 自定义不裁）
            val promptCapCfg = AIConfigStore.chatPromptChars
            val promptCap = if (promptCapCfg > 0) promptCapCfg else if (isOfficial) 6000 else 0
            val userText = if (promptCap > 0) prompt.take(promptCap) else prompt
            if (images.isEmpty()) {
                messages.put(JSONObject().put("role", "user").put("content", userText))
            } else {
                // 多模态：text 段 + 每张图的 image_url 段（OpenAI 兼容格式，NVIDIA/中转站都认）
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", userText))
                images.take(6).forEach { url ->
                    parts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", url))
                    )
                }
                messages.put(JSONObject().put("role", "user").put("content", parts))
            }

            // 【1.4.0 新增】把「思考模式 / 思考程度 / Max 模式」写进请求体。
            // withExtras=false 用于上游不认这些字段时（HTTP 400）去掉重试。
            fun buildBody(withExtras: Boolean): String {
                val b = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("temperature", AIConfigStore.cloudTemperature.toDouble())
                    .put("stream", useStream)
                if (withExtras) {
                    if (AIConfigStore.reasoningEnabled) {
                        // 6 档等级：上游只认 low / medium / high，高 3 档靠 max_tokens 与
                        // system 追加要求表达强度（见 AIConfigStore.upstreamReasoningEffort）。
                        b.put("reasoning_effort", AIConfigStore.upstreamReasoningEffort)
                    }
                    val levelTokens = AIConfigStore.reasoningMaxTokens
                    if (AIConfigStore.maxMode) {
                        b.put("max_tokens", 8192)
                    } else if (levelTokens != null) {
                        b.put("max_tokens", levelTokens)
                    }
                }
                return b.toString()
            }

            fun newRequest(json: String): Request = Request.Builder()
                .url(chatCompletionsUrl(endpoint, cfg.provider))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            val extrasWanted = AIConfigStore.reasoningEnabled || AIConfigStore.maxMode ||
                AIConfigStore.reasoningMaxTokens != null

            // 官方免费服务首响可能很慢（reasoning 模型），用更宽松的超时客户端。
            // 【v1.4.0 修复 · 「选了模型后发送一直没结果」】
            // 官方「慢速模型」档（如 kimi-k3）实测每次装载实例约 190~230s，旧的
            // callTimeout=180s 会在模型还没开始输出时就把请求掐断，表现就是
            // 「发送了但一直没有回复」。官方模式统一放宽到 300s；自定义 API 保持 180s。
            val autoTimeout = if (endpoint == AIConfigStore.OFFICIAL_AI_ENDPOINT) 300L else 120L
            // 【1.4.0】用户在「对话设置」里指定了超时则优先（0 = 自动）
            val officialTimeout = AIConfigStore.chatTimeoutSec
                .takeIf { it > 0 }?.toLong() ?: autoTimeout
            val client = axeronApp.okhttpClient.newBuilder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(officialTimeout, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(officialTimeout, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val firstCall = client.newCall(newRequest(buildBody(extrasWanted)))
            activeCall = firstCall
            var response = firstCall.execute()
            // 上游不认扩展参数（多为 HTTP 400）→ 去掉扩展参数重试一次，避免「调参把对话调挂」
            if (extrasWanted && response.code == 400) {
                val firstErr = runCatching { response.peekBody(4096).string() }.getOrNull().orEmpty()
                AiTrace.log("chat", "HTTP 400 且带扩展参数，去参重试：${firstErr.take(300)}")
                response.close()
                val retryCall = client.newCall(newRequest(buildBody(false)))
                activeCall = retryCall
                response = retryCall.execute()
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    AiTrace.log("chat", "HTTP ${resp.code}：${errBody.take(400)}")
                    // 日钥失效（如服务端轮换）→ 置空本地缓存，下次请求自动重取
                    if (resp.code == 401 && endpoint == AIConfigStore.OFFICIAL_AI_ENDPOINT) {
                        AxKeyProvider.invalidate()
                    }
                    return@withContext fail("云端返回 HTTP ${resp.code}：${errBody.take(300)}")
                }
                // 【v1.4.1】非流式：整包 JSON 解析。注意 body 只能读一次，
                // 所以这个分支必须在拿 source 之前完成。
                if (!useStream) {
                    val json = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    val msg = parseFullMessage(json)
                    msg?.reasoning?.takeIf { it.isNotEmpty() }?.let { onReasoning?.invoke(it) }
                    val text = msg?.content
                    if (text.isNullOrBlank()) {
                        return@withContext fail(
                            "云端未返回可解析内容（${json.take(200)}）"
                        )
                    }
                    onDelta(text)
                    AiTrace.log(
                        "chat",
                        "非流式完成 chars=${text.length} ms=${System.currentTimeMillis() - startedAt} head=${text.take(120).replace("\n", " ")}"
                    )
                    return@withContext text
                }
                val source = resp.body?.source() ?: return@withContext fail("云端响应为空")
                val sb = StringBuilder()
                var idleEnded = false
                // 首包之前不收紧超时（reasoning 模型可能长时间没有首 token）；
                // 一旦收到内容，就把读超时收紧到 STREAM_IDLE_SEC，上游挂着不发 [DONE] 时及时收尾。
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val delta = parseDelta(data) ?: continue
                        if (delta.reasoning.isNotEmpty()) onReasoning?.invoke(delta.reasoning)
                        if (delta.content.isEmpty()) {
                            // 纯思考块（正文尚未开始）：只刷新空闲计时，不当作正文，也不结束流
                            source.timeout().timeout(STREAM_IDLE_SEC, java.util.concurrent.TimeUnit.SECONDS)
                            continue
                        }
                        sb.append(delta.content)
                        onDelta(delta.content)
                        source.timeout().timeout(STREAM_IDLE_SEC, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } catch (e: java.io.InterruptedIOException) {
                    // InterruptedIOException 同时覆盖 Okio 读超时(SocketTimeoutException)
                    // 与 OkHttp callTimeout，二者都表示“上游不会再给数据了”。
                    idleEnded = true
                    AiTrace.log(
                        "chat",
                        "流式空闲 ${STREAM_IDLE_SEC}s 无新数据（上游未发 [DONE]/未关连接），按已完成收尾"
                    )
                }
                AiTrace.log(
                    "chat",
                    "流式结束 chars=${sb.length} idle=$idleEnded ms=${System.currentTimeMillis() - startedAt} " +
                        "head=${sb.take(120).toString().replace("\n", " ")}"
                )
                if (sb.isEmpty()) {
                    return@withContext fail("云端未返回任何内容（可能被限流或模型不可用）")
                }
                sb.toString()
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // 用户主动停止 / 页面退出导致协程取消：记录下来，避免日志里出现「有开始没结束」
            AiTrace.log("chat", "cancelled ms=${System.currentTimeMillis() - startedAt}")
            throw c
        } catch (e: Exception) {
            AiTrace.log(
                "chat",
                "异常 ${e.javaClass.simpleName}: ${e.message} ms=${System.currentTimeMillis() - startedAt}"
            )
            fail("云端调用异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 一个增量块：正文与思考双通道。 */
    /**
     * 【1.4.2】按上限裁剪 system 提示词，但**优先保住工具说明行**（含 `<<<`）。
     *
     * 直接 take() 的后果：模块开发提示词末尾才是 `<<<GH:owner/repo>>>`、`<<<FIND:关键词>>>`
     * 这类协议说明，被切掉后模型完全不知道这些工具存在，用户看到的就是
     * 「可调用工具的数量没有任何变化」。这里把工具行整体提出来必留，
     * 并用剩余预算按原顺序补普通说明；工具行放在最前面（模型对开头更敏感）。
     */
    private fun trimSystemKeepTools(text: String, cap: Int): String {
        if (cap <= 0 || text.length <= cap) return text
        val lines = text.split('\n')
        val toolPart = StringBuilder()
        lines.forEach { l ->
            if (l.contains("<<<")) toolPart.append(l).append('\n')
        }
        val tools = toolPart.toString()
        if (tools.length >= cap) return tools.take(cap)
        val budget = cap - tools.length
        val body = StringBuilder()
        lines.forEach { l ->
            if (!l.contains("<<<") && body.length + l.length + 1 <= budget) {
                body.append(l).append('\n')
            }
        }
        return (body.toString() + tools).trimEnd()
    }

    private data class Delta(val content: String, val reasoning: String)

    /** 解析非流式响应中的完整回复（choices[0].message 的 content + reasoning_content）。 */
    private fun parseFullMessage(json: String): Delta? {
        if (json.isBlank()) return null
        return try {
            val root = JSONObject(json)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val m = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
            // 注意：optString 对 JSONObject.NULL 会返回 ""，这里用 as? String 更准确地取字符串
            val content = (m.opt("content") as? String).orEmpty()
            val reasoning = (m.opt("reasoning_content") as? String).orEmpty()
                .ifEmpty { (m.opt("reasoning") as? String).orEmpty() }
            if (content.isEmpty() && reasoning.isEmpty()) null else Delta(content, reasoning)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 解析 SSE 块中的增量。
     * 部分模型（DeepSeek-V4-Flash 等 reasoning 模型）把思考放在 delta.reasoning_content、
     * 正文放在 delta.content；只取 content 会把思考过程整段丢掉，这里两路都收。
     */
    private fun parseDelta(data: String): Delta? {
        return try {
            val root = JSONObject(data)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val d = choices.optJSONObject(0)?.optJSONObject("delta") ?: return null
            val content = (d.opt("content") as? String).orEmpty()
            val reasoning = (d.opt("reasoning_content") as? String).orEmpty()
                .ifEmpty { (d.opt("reasoning") as? String).orEmpty() }
            if (content.isEmpty() && reasoning.isEmpty()) null else Delta(content, reasoning)
        } catch (e: Exception) {
            null
        }
    }
}
