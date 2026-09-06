package frb.axeron.manager.ai

import android.content.Intent
import android.util.Log
import frb.axeron.api.ai.CommandAnalyzer
import frb.axeron.api.ai.CommandAnalyzer.AnalyzeResult
import frb.axeron.api.ai.RuleEngine
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 引擎管理器（manager 层，实现 [CommandAnalyzer] 接口）。
 *
 * Phase 1：仅实现规则引擎（本地微型数据分析）。
 * Phase 2/3：云端 AI / 本地 LLM（后续接入，统一走此处）。
 */
object AIEngineManager : CommandAnalyzer {

    private const val TAG = "AIEngineManager"

    /** 当前挂起等待用户决策的请求（同一时刻只有一个分析弹窗） */
    @Volatile
    private var pendingDeferred: CompletableDeferred<Boolean>? = null

    /** 上一次分析结果（供 AIAnalysisActivity 展示） */
    @Volatile
    var lastAnalysis: AnalysisPayload? = null
        private set

    /** 最近一次运行捕获的真实执行指令流（供 AI 提问时作为第一手证据读取） */
    @Volatile
    var lastRuntimeTrace: String? = null
        private set

    /** 由执行层在运行结束后更新真实指令流缓存 */
    fun updateRuntimeTrace(trace: String?) {
        lastRuntimeTrace = trace
    }

    /**
     * 最近一次运行拦截时读取到的「脚本原文」缓存（明文 action.sh / service.sh 等）。
     * 运行时拦截（strace/sh -x）可能因加密、shell 内建命令等原因漏抓或抓错，
     * 因此把脚本原文一并缓存，作为「解析结果可能不完整时的参考」喂给 AI 兜底。
     */
    @Volatile
    var lastScriptText: String? = null
        private set

    /** 由执行层在拦截时更新脚本原文缓存 */
    fun updateScriptText(text: String?) {
        lastScriptText = text
    }

    /** 最近一次运行后，云端 AI 基于真实指令流生成的「模块行为总结」 */
    @Volatile
    var lastRuntimeSummary: String? = null
        private set

    /**
     * 把运行时抓取到的「真实执行指令流」喂给云端 AI，生成模块行为总结。
     *
     * 这是手段一（set -x 截获）的核心出口：抓到的每条真实指令（含加密脚本
     * 解密后真正执行的部分）都会被拼成完整上下文，连同环境声明一起发送给
     * 云端 AI，由 AI 基于真实指令回答"这个模块做了什么"，并缓存供后续提问复用。
     */
    suspend fun analyzeRuntimeTrace(pluginName: String?, traceText: String) {
        lastRuntimeTrace = traceText
        // 未抓到任何真实指令则直接返回（保留缓存原文）
        if (traceText.isBlank() || traceText.contains("未捕获到真实执行指令")) {
            return
        }
        // 云端未配置且本地模型未导入则不做 AI 总结
        if (!AIChatService.isAiAvailable()) return

        val system = AIEnvironment.systemDeclaration(currentPluginName = pluginName)
        val prompt = "以下是模块「${pluginName ?: "未知"}」在运行时被截获的真实执行指令流，" +
            "请基于这些真实指令（而非模块名猜测）总结：该模块实际做了哪些操作、会修改/优化什么、" +
            "有什么风险。不要泛泛而谈，逐项列出依据的指令。\n\n$traceText"

        val reply = AIChatService.chatOnce(system = system, prompt = prompt)
        if (!reply.isNullOrBlank()) {
            lastRuntimeSummary = reply
        }
    }

    /** 分析载荷：传递给 UI 展示 */
    data class AnalysisPayload(
        val cmd: String,
        val ctx: CommandAnalyzer.CommandContext,
        val result: AnalyzeResult,
        /** 模块解析结果（安装/运行时由 ModuleInspector 真实探测模块目录得到） */
        val inspection: ModuleInspector.InspectionResult? = null,
        /** 运行时真实执行指令流（由 RuntimeCommandTracer 抓取，喂给 AI） */
        val runtimeTrace: String? = null,
        /** 脚本原文（明文 action.sh/service.sh），解析结果可能不完整时作为参考喂给 AI */
        val scriptText: String? = null,
    )

    override suspend fun analyze(
        cmd: String,
        ctx: CommandAnalyzer.CommandContext,
    ): AnalyzeResult? {
        Log.i(TAG, "analyze() 进入 source=${ctx.source} pluginDirId=${ctx.pluginDirId} masterEnabled=${AIConfigStore.aiMasterEnabled} microEnabled=${AIConfigStore.isMicroAnalysisEnabled} cmd=${cmd.take(120)}")

        // 白名单：命中则不拦截（运行/启用/安装等所有分析直接放行）
        if (AIConfigStore.isWhitelisted(ctx.pluginDirId, ctx.pluginName)) {
            Log.i(TAG, "analyze() 命中白名单 pluginDirId=${ctx.pluginDirId} pluginName=${ctx.pluginName}，直接放行")
            return AnalyzeResult(
                allow = true,
                risk = AnalyzeResult.Risk.SAFE,
                matchedRules = emptyList(),
                summary = "该模块已加入白名单",
            )
        }

        // AI 引擎总开关关闭：整体放行，不做任何分析/弹窗
        if (!AIConfigStore.aiMasterEnabled) {
            Log.i(TAG, "analyze() 总开关关闭，直接放行")
            return AnalyzeResult(
                allow = true,
                risk = AnalyzeResult.Risk.SAFE,
                matchedRules = emptyList(),
                summary = "AI 引擎已关闭",
            )
        }

        // 规则引擎分析（纯内存匹配，极快，本地始终可用）
        val ruleResult = RuleEngine.analyze(cmd)

        // 微型分析模型（规则引擎）关闭时：
        // 仍先做本地规则分析（不拦截/不弹窗），并尝试云端 AI 增强；
        // 但云端失败或未配置时，绝不静默放行 ACTION/INSTALL 强制弹窗场景。
        if (!AIConfigStore.isMicroAnalysisEnabled) {
            val cloudKey = AIConfigStore.cloudApiKey
            val useOfficial = AIConfigStore.useOfficialAi
            val forceDialog = ctx.source == CommandAnalyzer.CommandContext.Source.INSTALL ||
                ctx.source == CommandAnalyzer.CommandContext.Source.ACTION

            // 有自定义 API Key 且非官方：尝试云端分析
            if (!cloudKey.isNullOrBlank() && !useOfficial) {
                val cloudResult = analyzeViaCloud(cmd)
                if (cloudResult != null) {
                    return awaitUserDecision(cmd, ctx, cloudResult)
                }
                Log.i(TAG, "analyze() 云端失败回退规则引擎 risk=${ruleResult.risk}")
            }

            // 云端不可用：强制场景仍然弹窗（用规则引擎结果），非强制且无风险才放行
            if (forceDialog || ruleResult.risk == AnalyzeResult.Risk.MEDIUM ||
                ruleResult.risk == AnalyzeResult.Risk.HIGH) {
                return awaitUserDecision(cmd, ctx, ruleResult)
            }
            return ruleResult
        }

        // 微型分析开启：直接规则引擎分析并弹窗
        return awaitUserDecision(cmd, ctx, ruleResult)
    }

    /**
     * 根据 source 决定是否弹窗，并在需要时挂起等待用户决策。
     *
     * 提速关键：对 INTERNAL（脚本内部逐条命令）不逐条弹窗，
     * 而是累积风险、合并为一次决策，避免"一次只拦一个 + 慢"。
     */
    private suspend fun awaitUserDecision(
        cmd: String,
        ctx: CommandAnalyzer.CommandContext,
        result: AnalyzeResult,
    ): AnalyzeResult {
        val forceDialog = ctx.source == CommandAnalyzer.CommandContext.Source.INSTALL ||
            ctx.source == CommandAnalyzer.CommandContext.Source.ACTION

        val isRisk = result.risk == CommandAnalyzer.AnalyzeResult.Risk.MEDIUM ||
            result.risk == CommandAnalyzer.AnalyzeResult.Risk.HIGH

        // 非强制场景（INTERNAL）且无风险：直接放行，不弹窗
        if (!forceDialog && !isRisk) {
            Log.i(TAG, "awaitUserDecision() 非强制且无风险，直接放行")
            return result
        }

        // —— 批量拦截：若已有挂起的决策，直接复用其结果，避免重复弹窗（不论 source）——
        val existing = pendingDeferred
        if (existing != null) {
            Log.i(TAG, "awaitUserDecision() 复用已有挂起决策 pendingDeferred=${existing}")
            val alreadyAllowed = existing.await()
            return result.copy(allow = alreadyAllowed)
        }

        Log.i(TAG, "awaitUserDecision() 准备弹窗 forceDialog=$forceDialog isRisk=$isRisk risk=${result.risk}")

        // 用模块目录真实探测结果填充 inspection（拦截到的关键指令列表），
        // 供 AIAnalysisActivity 的「模块解析结果」区块展示真实拦截到的指令。
        val inspection = withContext(Dispatchers.IO) {
            runCatching { ModuleInspector.inspect(ctx.pluginDirId) }.getOrNull()
        }

        // 强制弹窗或命中风险：弹整页分析界面上用户决策
        lastAnalysis = AnalysisPayload(
            cmd, ctx, result,
            inspection = inspection,
            runtimeTrace = lastRuntimeTrace,
            scriptText = lastScriptText,
        )

        val allowed = withContext(Dispatchers.Main) {
            showAnalysisDialog()
        }

        return result.copy(allow = allowed)
    }

    /**
     * 调用云端 AI（OpenAI 兼容 chat completions）分析命令是否危险。
     *
     * 返回 null 表示云端分析失败（网络异常 / 未配置 / 响应异常）。
     *
     * 公开化：供拦截界面左上角的「AI 分析」按钮直接调用，把当前输出的指令代码
     * 喂给 AI，由 AI 判断是否有危险（返回 SAFE/LOW/MEDIUM/HIGH + 说明）。
     */
    suspend fun analyzeViaCloud(cmd: String): AnalyzeResult? = withContext(Dispatchers.IO) {
        try {
            // 官方默认 AI（英伟达）开启时使用固定配置，否则用自定义配置
            val useOfficial = AIConfigStore.useOfficialAi
            val endpoint = if (useOfficial) AIConfigStore.OFFICIAL_AI_ENDPOINT
                else AIConfigStore.cloudEndpoint.orEmpty()
            val apiKey = if (useOfficial) AIConfigStore.OFFICIAL_AI_API_KEY
                else AIConfigStore.cloudApiKey.orEmpty()
            val model = if (useOfficial) AIConfigStore.OFFICIAL_AI_MODEL
                else AIConfigStore.cloudModelName.orEmpty()
            val provider = if (useOfficial) AIConfigStore.OFFICIAL_AI_PROVIDER
                else AIConfigStore.cloudProvider.orEmpty()
            val temperature = AIConfigStore.cloudTemperature

            if (endpoint.isBlank() || apiKey.isBlank() || model.isBlank()) {
                return@withContext null
            }

            val chatUrl = buildChatCompletionsUrl(endpoint, provider)

            val messages = JSONArray()
                .put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "你是 Android 模块安全分析助手。请分析以下模块/命令是否包含危险操作" +
                            "（如删除文件、修改系统设置、窃取数据、提权、恶意下载等）。" +
                            "请用自然语言简要说明，并在结尾单独一行明确给出风险等级，" +
                            "格式为：【风险等级：安全/低/中/高】"
                    )
                })
                .put(JSONObject().apply {
                    put("role", "user")
                    put("content", cmd)
                })

            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", temperature.toDouble())
                .toString()

            val request = Request.Builder()
                .url(chatUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            // 云端分析也需要足够长的读超时：reasoning 类模型（如官方 nemotron）首响很慢
            val shortTimeoutClient = axeronApp.okhttpClient.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = shortTimeoutClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val respBody = resp.body?.string() ?: return@withContext null
                val parsed = parseCloudResult(respBody)
                    ?: return@withContext null
                return@withContext parsed
            }
        } catch (e: Exception) {
            Log.e(TAG, "云端分析异常", e)
            return@withContext null
        }
    }

    /**
     * 根据 endpoint 派生 chat completions URL。
     * 部分提供商（智谱 /v4、豆包 /v3）与 OpenAI 默认 /v1 不同。
     */
    private fun buildChatCompletionsUrl(endpoint: String, provider: String): String {
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

    /** 解析云端返回，提取风险等级。 */
    private fun parseCloudResult(body: String): AnalyzeResult? {
        return try {
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val content = choices.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?: return null

            // 尝试从 content 中提取 risk
            val risk = parseRiskFromContent(content)
            AnalyzeResult(
                allow = risk == AnalyzeResult.Risk.SAFE || risk == AnalyzeResult.Risk.LOW,
                risk = risk,
                matchedRules = emptyList(),
                summary = content,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 从云端返回的文本中解析风险等级。 */
    private fun parseRiskFromContent(content: String): AnalyzeResult.Risk {
        // 优先解析「【风险等级：xxx】」中文标记
        runCatching {
            val m = Regex("""风险等级[:：]\s*(安全|低|中|高)""").find(content)
            if (m != null) {
                return when (m.groupValues[1]) {
                    "高" -> AnalyzeResult.Risk.HIGH
                    "中" -> AnalyzeResult.Risk.MEDIUM
                    "低" -> AnalyzeResult.Risk.LOW
                    else -> AnalyzeResult.Risk.SAFE
                }
            }
        }
        // 尝试解析 JSON
        runCatching {
            val extracted = content.substringAfter('{').substringBefore('}')
            val json = JSONObject("{$extracted}")
            val risk = json.optString("risk", "").uppercase()
            return when (risk) {
                "HIGH" -> AnalyzeResult.Risk.HIGH
                "MEDIUM" -> AnalyzeResult.Risk.MEDIUM
                "LOW" -> AnalyzeResult.Risk.LOW
                else -> AnalyzeResult.Risk.SAFE
            }
        }
        // 降级：关键词匹配（先高后低，避免「无高风险」误判为高）
        val upper = content.uppercase()
        return when {
            upper.contains("高风险") || upper.contains("风险等级：高") || upper.contains("危险") -> AnalyzeResult.Risk.HIGH
            upper.contains("MEDIUM") || upper.contains("中风险") || upper.contains("风险等级：中") -> AnalyzeResult.Risk.MEDIUM
            upper.contains("LOW") || upper.contains("低风险") || upper.contains("风险等级：低") -> AnalyzeResult.Risk.LOW
            else -> AnalyzeResult.Risk.SAFE
        }
    }

    /**
     * 弹出整页分析界面，挂起等待用户选择。
     * 返回 true = 放行，false = 拦截。
     */
    private suspend fun showAnalysisDialog(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingDeferred = deferred

        val ctx = frb.axeron.api.core.Engine.application
        val intent = Intent(ctx, AIAnalysisActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AIAnalysisActivity", e)
            pendingDeferred = null
            return true // 弹窗失败则不拦截，避免卡死
        }

        return deferred.await()
    }

    /** 由 AIAnalysisActivity 调用，通知用户已选择 */
    fun onUserDecision(allow: Boolean) {
        val d = pendingDeferred
        pendingDeferred = null
        d?.complete(allow)
    }
}
