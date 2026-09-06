package frb.axeron.manager.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import frb.axeron.api.core.AxeronSettings

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

    // ============ 官方默认 AI（英伟达 NVIDIA NIM） ============
    /** 官方默认服务的固定配置：英伟达 nemotron 模型（OpenAI 兼容）。 */
    const val OFFICIAL_AI_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
    const val OFFICIAL_AI_API_KEY = "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"
    const val OFFICIAL_AI_MODEL = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
    const val OFFICIAL_AI_PROVIDER = "英伟达 (NVIDIA)"
}