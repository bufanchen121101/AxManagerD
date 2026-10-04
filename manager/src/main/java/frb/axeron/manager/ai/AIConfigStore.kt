package frb.axeron.manager.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import frb.axeron.api.core.AxeronSettings
import frb.axeron.manager.BuildConfig

/**
 * AI 相关配置的持久化存储（基于 AxeronSettings.getPreferences()）。
 *
 * 覆盖：
 * - 微型分析模型（规则引擎）开关
 * - 本地模型：导入路径、系列配置开关
 * - 云端模型：官方默认开关、提供商、网址、API Key、模型名
 *
 * 说明：属性暴露为只读读值，写操作统一走 setXxx 方法并在内部持久化到 SharedPreferences。
 */
object AIConfigStore {

    private val prefs get() = AxeronSettings.getPreferences()

    // ============ AI 引擎总开关 ============
    private var _aiMasterEnabled by mutableStateOf(
        prefs.getBoolean(KEY_AI_MASTER, true)
    )
    val aiMasterEnabled: Boolean get() = _aiMasterEnabled

    fun setAiMasterEnabled(enabled: Boolean) {
        _aiMasterEnabled = enabled
        prefs.edit().putBoolean(KEY_AI_MASTER, enabled).apply()
    }

    // ============ 微型分析模型（规则引擎） ============
    private var _isMicroAnalysisEnabled by mutableStateOf(
        prefs.getBoolean(KEY_MICRO_ANALYSIS, true)
    )
    val isMicroAnalysisEnabled: Boolean get() = _isMicroAnalysisEnabled

    fun setMicroAnalysisEnabled(enabled: Boolean) {
        _isMicroAnalysisEnabled = enabled
        prefs.edit().putBoolean(KEY_MICRO_ANALYSIS, enabled).apply()
    }

    /**
     * 【合并后的 AI 引擎总开关】
     *
     * 原先「AI 引擎」与「微型分析模型」是两个互不联动的开关，但拦截链路里
     * 任一开关为 false 都会直接放行，导致用户看到的现象是“两个开关都没用”。
     * 现在 UI 只暴露这一个开关，读值取两者之与，写入同时落到两个 key 上，
     * 保证旧的持久化数据（只开了一个）也能被正确归一。
     */
    val aiEngineEnabled: Boolean
        get() = _aiMasterEnabled && _isMicroAnalysisEnabled

    fun setAiEngineEnabled(enabled: Boolean) {
        setAiMasterEnabled(enabled)
        setMicroAnalysisEnabled(enabled)
    }

    // ============ 白名单 ============
    /** 白名单模块集合（存储 pluginDirId:pluginName，命中则不拦截运行/启用/安装） */
    private var _whitelist by mutableStateOf(
        prefs.getStringSet(KEY_WHITELIST, emptySet())?.toSet() ?: emptySet()
    )
    val whitelist: Set<String> get() = _whitelist

    fun isWhitelisted(pluginDirId: String?, pluginName: String?): Boolean {
        val id = pluginDirId.orEmpty()
        if (id.isNotEmpty() && _whitelist.contains(id)) return true
        if (id.isNotEmpty() && _whitelist.any { it.startsWith("$id:") }) return true
        val name = pluginName.orEmpty()
        if (name.isNotEmpty() && _whitelist.any { it.endsWith(":$name") }) return true
        return false
    }

    fun addWhitelist(pluginDirId: String?, pluginName: String?) {
        val key = buildString {
            append(pluginDirId.orEmpty())
            append(':')
            append(pluginName.orEmpty())
        }
        if (key == ":") return
        val next = _whitelist + key
        _whitelist = next
        prefs.edit().putStringSet(KEY_WHITELIST, next).apply()
    }

    fun removeWhitelist(key: String) {
        val next = _whitelist - key
        _whitelist = next
        prefs.edit().putStringSet(KEY_WHITELIST, next).apply()
    }

    /** 解析白名单条目为 (dirId, name) */
    fun parseWhitelistEntry(key: String): Pair<String, String> {
        val idx = key.indexOf(':')
        return if (idx < 0) key to key else key.substring(0, idx) to key.substring(idx + 1)
    }

    // ============ 云端模型 ============
    /** 是否使用官方默认 AI（关 -> 用户自定义） */
    private var _useOfficialAi by mutableStateOf(
        prefs.getBoolean(KEY_USE_OFFICIAL, true)
    )
    val useOfficialAi: Boolean get() = _useOfficialAi

    fun setUseOfficialAi(v: Boolean) {
        _useOfficialAi = v
        prefs.edit().putBoolean(KEY_USE_OFFICIAL, v).apply()
    }

    private var _cloudProvider by mutableStateOf(
        prefs.getString(KEY_CLOUD_PROVIDER, "OpenAI")
    )
    val cloudProvider: String? get() = _cloudProvider

    fun setCloudProvider(v: String) {
        _cloudProvider = v
        prefs.edit().putString(KEY_CLOUD_PROVIDER, v).apply()
    }

    private var _cloudEndpoint by mutableStateOf(
        prefs.getString(KEY_CLOUD_ENDPOINT, "")
    )
    val cloudEndpoint: String? get() = _cloudEndpoint

    fun setCloudEndpoint(v: String) {
        _cloudEndpoint = v
        prefs.edit().putString(KEY_CLOUD_ENDPOINT, v).apply()
    }

    private var _cloudApiKey by mutableStateOf(
        prefs.getString(KEY_CLOUD_API_KEY, "")
    )
    val cloudApiKey: String? get() = _cloudApiKey

    fun setCloudApiKey(v: String) {
        _cloudApiKey = v
        prefs.edit().putString(KEY_CLOUD_API_KEY, v).apply()
    }

    private var _cloudModelName by mutableStateOf(
        prefs.getString(KEY_CLOUD_MODEL, "")
    )
    val cloudModelName: String? get() = _cloudModelName

    fun setCloudModelName(v: String) {
        _cloudModelName = v
        prefs.edit().putString(KEY_CLOUD_MODEL, v).apply()
    }

    private var _cloudTemperature by mutableStateOf(
        prefs.getFloat(KEY_CLOUD_TEMP, 0.7f)
    )
    val cloudTemperature: Float get() = _cloudTemperature

    fun setCloudTemperature(v: Float) {
        _cloudTemperature = v
        prefs.edit().putFloat(KEY_CLOUD_TEMP, v).apply()
    }

    // ============ 审查 AI 模型（双 AI 方案的第二个模型） ============
    /**
     * 审查 AI 使用的模型名（与生成 AI 分离）。
     * 为空时回退到主模型 [cloudModelName] / 官方默认模型。
     * 双 AI 方案：生成 AI 负责产出恢复脚本，审查 AI 负责校验语法/符号错误。
     */
    private var _reviewModelName by mutableStateOf(
        prefs.getString(KEY_REVIEW_MODEL, "")
    )
    val reviewModelName: String? get() = _reviewModelName

    fun setReviewModelName(v: String) {
        _reviewModelName = v
        prefs.edit().putString(KEY_REVIEW_MODEL, v).apply()
    }

    /** 审查 AI 实际生效的模型名（未单独配置则回退到生成模型）。 */
    fun resolveReviewModel(): String? =
        _reviewModelName?.takeIf { it.isNotBlank() }
            ?: if (useOfficialAi) OFFICIAL_AI_MODEL else cloudModelName

    /** 云端自动识别到的可用模型列表（仅内存态，不持久化） */
    private var _availableModels by mutableStateOf<List<String>>(emptyList())
    val availableModels: List<String> get() = _availableModels

    fun setAvailableModels(models: List<String>) {
        _availableModels = models
    }

    // ============ 拦截抓取时长 ============
    /**
     * 运行时拦截（strace + sh -x）抓取模块真实执行指令的时长（秒）。
     *
     * 作用：
     * - 值越大抓取越完整（能捕获更晚执行的核心指令），但拦截等待越久；
     * - 值越小等待越短，但可能漏抓（模块解密/启动较慢时来不及执行到核心指令）。
     * 默认 15 秒：对大多数模块（含常驻型 service.sh，如 while true 循环）足够抓到
     * 「解密 → 部署 → 核心动作」的前段，且等待可接受。常驻脚本会在 to 时间点被强杀，不会卡死。
     */
    private var _traceTimeoutSeconds by mutableStateOf(
        prefs.getInt(KEY_TRACE_TIMEOUT_SECONDS, 15)
    )
    val traceTimeoutSeconds: Int get() = _traceTimeoutSeconds

    fun setTraceTimeoutSeconds(v: Int) {
        _traceTimeoutSeconds = v.coerceIn(3, 120)
        prefs.edit().putInt(KEY_TRACE_TIMEOUT_SECONDS, _traceTimeoutSeconds).apply()
    }

    // ============ 常量 ============
    private const val KEY_AI_MASTER = "ai_master_enabled"
    private const val KEY_MICRO_ANALYSIS = "ai_micro_analysis_enabled"
    private const val KEY_WHITELIST = "ai_whitelist"
    private const val KEY_USE_OFFICIAL = "ai_use_official"
    private const val KEY_CLOUD_PROVIDER = "ai_cloud_provider"
    private const val KEY_CLOUD_ENDPOINT = "ai_cloud_endpoint"
    private const val KEY_CLOUD_API_KEY = "ai_cloud_api_key"
    private const val KEY_CLOUD_MODEL = "ai_cloud_model"
    private const val KEY_CLOUD_TEMP = "ai_cloud_temperature"
    private const val KEY_REVIEW_MODEL = "ai_review_model"
    private const val KEY_TRACE_TIMEOUT_SECONDS = "ai_trace_timeout_seconds"

    /** 云端可选的提供商列表（参考 Operit） */
    val cloudProviders = listOf(
        "官方默认 AI",
        "OpenAI",
        "Anthropic (Claude)",
        "Google (Gemini)",
        "英伟达 (NVIDIA)",
        "DeepSeek",
        "Moonshot (Kimi)",
        "智谱 (ChatGLM)",
        "百度 (文心一言)",
        "阿里云 (通义千问)",
        "豆包 (火山)",
        "硅基流动",
        "Ollama (本地)",
        "自定义",
    )

    // ============ 官方默认 AI（英伟达 NVIDIA NIM 托管） ============
    /**
     * 官方默认服务的固定配置（OpenAI 兼容接口）。
     *
     * 【修复】原模型 `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning` 在 NIM 上持续返回
     * 503 `ResourceExhausted: Worker local total request limit reached (49/16)`，
     * 实测（2026-10-03）已无法使用，导致「官方默认 AI」整条链路失败。
     * 换为本账号实测稳定的 `openai/gpt-oss-20b`（开源 20B 模型，非旗舰，
     * 在不传 max_tokens 的默认请求下 content 即为最终答案，finish_reason=stop）。
     */
    const val OFFICIAL_AI_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
    const val OFFICIAL_AI_MODEL = "openai/gpt-oss-20b"
    const val OFFICIAL_AI_PROVIDER = "英伟达 (NVIDIA)"

    /**
     * 官方默认 AI 的 API Key。
     *
     * 【不再硬编码】密钥由构建期注入（见 manager/build.gradle.kts 的
     * `officialAiKeyBlob()` -> BuildConfig.OFFICIAL_AI_KEY_BLOB）：
     *   - 源码与 git 仓库内没有明文；
     *   - APK 的 dex 里只有 XOR+十六进制混淆后的字节，`strings` 无法直接提取；
     *   - 未注入（未配置 CI Secret/Variable 且本地未写 local.properties）时为空串，
     *     由 [officialAiKeyAvailable] 判定不可用，回退到用户自定义配置。
     */
    val OFFICIAL_AI_API_KEY: String by lazy { decodeOfficialAiKey(BuildConfig.OFFICIAL_AI_KEY_BLOB) }

    /** 官方默认 AI 是否真的可用（构建期密钥是否已注入）。 */
    val officialAiKeyAvailable: Boolean get() = OFFICIAL_AI_API_KEY.isNotBlank()

    /** 与 manager/build.gradle.kts:OFFICIAL_AI_KEY_SEED 严格对应（改一处必须同步改另一处）。 */
    private const val OFFICIAL_AI_KEY_SEED = "frb.axeron.manager|AxManagerD/axkey/v1"

    /**
     * 解码构建期注入的密钥 blob（小写十六进制）；失败返回空串
     * （当作未配置，避免把垃圾串当 key 发出去）。
     *
     * 与 manager/build.gradle.kts 的 `officialAiKeyBlob()` 严格对应：
     * XOR（掩码按 seed 循环）-> 小写十六进制。改一处必须同步改另一处。
     * 不使用 java.util.Base64：十六进制自解码无任何依赖，也避开 Base64 的 API 级别限制。
     */
    private fun decodeOfficialAiKey(blob: String): String {
        val hex = blob.trim()
        if (hex.isEmpty() || hex.length % 2 != 0) return ""
        return try {
            val cipher = ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val mask = OFFICIAL_AI_KEY_SEED.toByteArray(Charsets.UTF_8)
            if (mask.isEmpty() || cipher.isEmpty()) return ""
            val plain = ByteArray(cipher.size) { i ->
                (cipher[i].toInt() xor mask[i % mask.size].toInt()).toByte()
            }
            String(plain, Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }
}