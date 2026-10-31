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
    // 【v1.9.1】默认改为「关闭」：
    //  - 关闭 = 不做沙箱预演拦截、不弹信任/分析弹窗，但**仍然抓取**——
    //    运行模块时在真实执行过程中抓（sh -x + strace 后果判定），
    //    并把结果写入卸载恢复名单（filesDir/rollback/{dirId}/*.axd_log）；
    //  - 开启 = 保留原有的「运行前沙箱预演 + AI/规则拦截分析」行为。
    private var _aiMasterEnabled by mutableStateOf(
        prefs.getBoolean(KEY_AI_MASTER, false)
    )
    val aiMasterEnabled: Boolean get() = _aiMasterEnabled

    fun setAiMasterEnabled(enabled: Boolean) {
        _aiMasterEnabled = enabled
        prefs.edit().putBoolean(KEY_AI_MASTER, enabled).apply()
    }

    // ============ 微型分析模型（规则引擎） ============
    private var _isMicroAnalysisEnabled by mutableStateOf(
        prefs.getBoolean(KEY_MICRO_ANALYSIS, false)
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

    /**
     * 【1.4.1】总开关：是否使用用户自己填的 API 密钥。
     *
     * 口径（按需求定稿）：
     *  - 开启且「网址 + Key」齐全 → **全局**所有 AI 功能都用自定义模型；
     *  - 关闭 → 走官方默认 AI：制作模块用「制作模块页选择的模型」，
     *    其他界面用「对话页选择的模型」；
     *  - 官方不可用时才回退内置 NVIDIA 直连。
     */
    private var _useCustomKey by mutableStateOf(
        prefs.getBoolean(
            KEY_USE_CUSTOM_KEY,
            // 迁移默认值：老用户若早已填好「网址 + Key」，视为已确认使用自定义密钥
            !prefs.getString(KEY_CLOUD_ENDPOINT, "").isNullOrBlank() &&
                !prefs.getString(KEY_CLOUD_API_KEY, "").isNullOrBlank(),
        )
    )
    val useCustomKey: Boolean get() = _useCustomKey
    fun setUseCustomKey(v: Boolean) {
        _useCustomKey = v
        prefs.edit().putBoolean(KEY_USE_CUSTOM_KEY, v).apply()
    }

    /** 「其他界面」选择的模型（官方链路下使用；自定义密钥开启时被 cloudModelName 覆盖）。 */
    private var _generalModel by mutableStateOf(prefs.getString(KEY_GENERAL_MODEL, "").orEmpty())
    val generalModel: String
        get() = _generalModel.takeIf { it.isNotBlank() } ?: GENERAL_AI_MODEL
    fun setGeneralModel(v: String) {
        _generalModel = v.trim()
        prefs.edit().putString(KEY_GENERAL_MODEL, _generalModel).apply()
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

    // ============ 对话参数（v1.4.1 新增，参考 Operit 的模型参数设置） ============

    /**
     * 历史消息条数上限（0 = 自动：官方 6 条、自定义 20 条）。
     *
     * 为什么可调：官方免费模型对上下文长度极敏感，历史越长 token 越容易超限，
     * 表现为「第一轮正常、第二轮开始失败」。用户可调小来换取稳定性。
     */
    private var _chatHistoryLimit by mutableStateOf(
        prefs.getInt(KEY_CHAT_HISTORY_LIMIT, 0)
    )
    val chatHistoryLimit: Int get() = _chatHistoryLimit

    fun setChatHistoryLimit(v: Int) {
        _chatHistoryLimit = v.coerceIn(0, 40)
        prefs.edit().putInt(KEY_CHAT_HISTORY_LIMIT, _chatHistoryLimit).apply()
    }

    /** 追加在内置系统提示词之后的自定义提示词（空 = 不追加）。 */
    private var _chatSystemPrompt by mutableStateOf(
        prefs.getString(KEY_CHAT_SYSTEM_PROMPT, "") ?: ""
    )
    val chatSystemPrompt: String get() = _chatSystemPrompt

    fun setChatSystemPrompt(v: String) {
        _chatSystemPrompt = v
        prefs.edit().putString(KEY_CHAT_SYSTEM_PROMPT, v).apply()
    }

    /** 是否使用流式输出。关闭后回复一次性返回（弱网/中转站不稳时更稳，但无逐字效果）。 */
    private var _chatStreamEnabled by mutableStateOf(
        prefs.getBoolean(KEY_CHAT_STREAM, true)
    )
    val chatStreamEnabled: Boolean get() = _chatStreamEnabled

    fun setChatStreamEnabled(v: Boolean) {
        _chatStreamEnabled = v
        prefs.edit().putBoolean(KEY_CHAT_STREAM, v).apply()
    }

    /**
     * 【1.4.0 新增】非流式调用的失败自动重试次数（0 = 不重试）。
     *
     * 只作用于 [AIChatService.chatOnce]（整包返回，重试不会产生重复文本）；
     * 流式对话不自动重试，避免把已输出的半截内容重复追加。
     */
    private var _chatRetryCount by mutableStateOf(
        prefs.getInt(KEY_CHAT_RETRY, 0)
    )
    val chatRetryCount: Int get() = _chatRetryCount

    fun setChatRetryCount(v: Int) {
        _chatRetryCount = v.coerceIn(0, 3)
        prefs.edit().putInt(KEY_CHAT_RETRY, _chatRetryCount).apply()
    }

    /**
     * 【1.4.0 新增】单次 AI 请求超时（秒）。0 = 自动
     * （制作模块 300s / 其余 180s，沿用历史行为）。
     *
     * 为什么可调：弱网下 180s 被掐断会表现为「发了没反应」；
     * 网络好时又没必要等太久，交给用户按实际情况取舍。
     */
    private var _chatTimeoutSec by mutableStateOf(
        prefs.getInt(KEY_CHAT_TIMEOUT, 0)
    )
    val chatTimeoutSec: Int get() = _chatTimeoutSec

    fun setChatTimeoutSec(v: Int) {
        _chatTimeoutSec = v.coerceIn(0, 600)
        prefs.edit().putInt(KEY_CHAT_TIMEOUT, _chatTimeoutSec).apply()
    }

    /**
     * 【1.4.0 新增】思考模式 / 思考程度 / 思考引导 / Max 模式（对齐 Operit 对话页的这组开关）。
     *
     * 这些开关**真的写进请求体**：reasoning_effort、max_tokens；思考引导拼在 system
     * 提示词前面。若上游不认这些字段直接回 400，[AIChatService] 会自动去掉它们重试
     * 一次，保证不会因为调参把对话弄挂。
     */
    /** 6 档等级的内部 key（顺序即滑条顺序）。 */
    val REASONING_LEVELS = listOf("light", "medium", "high", "extra_high", "max", "ultra")
    val REASONING_LABELS = mapOf(
        "light" to "Light（轻度）",
        "medium" to "Medium（中度）",
        "high" to "High（高度）",
        "extra_high" to "Extra High（极高）",
        "max" to "Max（最高）",
        "ultra" to "Ultra（极限）",
    )
    val REASONING_DESCS = mapOf(
        "light" to "规则明确、几乎不用判断的机械任务。",
        "medium" to "速度、质量与推理投入之间取平衡，日常普通工作。",
        "high" to "需要多步骤规划、跨文件整合的复杂任务。",
        "extra_high" to "侧重严谨性与自我审查，方案审查等高风险场景。",
        "max" to "进一步拉高推理投入上限。",
        "ultra" to "消耗额度最快，推理投入最大。",
    )

    /** 旧版本只存 low / high / max，读出来统一归一化，避免老配置失效。 */
    private fun normalizeReasoningLevel(v: String?): String = when {
        v.isNullOrBlank() -> "high"
        v == "low" -> "light"
        REASONING_LEVELS.contains(v) -> v
        else -> "high"
    }

    /**
     * 可选的 GitHub Personal Access Token（只需公共仓库读权限，可不勾任何 scope）。
     *
     * 未配置时 GitHub API 按出口 IP 限流 60 次/小时——实测在国内网络下很容易 429，
     * 那样 GH/FIND 工具会一直提示「限流」。配置后额度 5000 次/小时。
     */
    var githubToken: String
        get() = prefs.getString(KEY_GH_TOKEN, "").orEmpty().trim()
        set(v) {
            prefs.edit().putString(KEY_GH_TOKEN, v.trim()).apply()
        }

    /**
     * 【1.4.2】上下文长度：三项都能在「对话设置 → 上下文与世界」里调，全部 0 = 自动。
     *
     * 为什么需要：旧实现把上限写死在 AIChatService 里（system 1200 / 单条历史 2000 /
     * 输入 6000）。官方通道的 system 一截断就会把模块开发提示词末尾的
     * `<<<GH:...>>>`、`<<<FIND:...>>>` 工具说明整段切掉，模型于是"不知道有这个工具"，
     * 表现就是「工具数量没变化」。改成可配 + 保住工具行的裁剪后，工具清单一定送达。
     */
    private var _chatSystemChars by mutableStateOf(prefs.getInt(KEY_CHAT_SYSTEM_CHARS, 0))
    val chatSystemChars: Int get() = _chatSystemChars

    fun setChatSystemChars(v: Int) {
        _chatSystemChars = if (v <= 0) 0 else v.coerceIn(600, 32000)
        prefs.edit().putInt(KEY_CHAT_SYSTEM_CHARS, _chatSystemChars).apply()
    }

    private var _chatHistoryItemChars by mutableStateOf(prefs.getInt(KEY_CHAT_HISTORY_ITEM_CHARS, 0))
    val chatHistoryItemChars: Int get() = _chatHistoryItemChars

    fun setChatHistoryItemChars(v: Int) {
        _chatHistoryItemChars = if (v <= 0) 0 else v.coerceIn(300, 32000)
        prefs.edit().putInt(KEY_CHAT_HISTORY_ITEM_CHARS, _chatHistoryItemChars).apply()
    }

    private var _chatPromptChars by mutableStateOf(prefs.getInt(KEY_CHAT_PROMPT_CHARS, 0))
    val chatPromptChars: Int get() = _chatPromptChars

    fun setChatPromptChars(v: Int) {
        _chatPromptChars = if (v <= 0) 0 else v.coerceIn(500, 64000)
        prefs.edit().putInt(KEY_CHAT_PROMPT_CHARS, _chatPromptChars).apply()
    }

    /** 【1.4.2】是否在对话气泡里展示模型思考内容（可折叠块）。关掉只是不显示，不影响请求。 */
    private var _showReasoning by mutableStateOf(prefs.getBoolean(KEY_SHOW_REASONING, true))
    val showReasoning: Boolean get() = _showReasoning

    fun setShowReasoning(v: Boolean) {
        _showReasoning = v
        prefs.edit().putBoolean(KEY_SHOW_REASONING, v).apply()
    }

    // ==================== 【1.4.3】识图（OCR / 视觉模型） ====================
    /**
     * 官方配套的识图模型：中转站上的 NVIDIA Nemotron-3.5 Super VL（视觉语言模型）。
     * 2026-10-23 实测：POST /v1/chat/completions 带 image_url 可正常返回图片描述。
     */
    const val OFFICIAL_VISION_MODEL = "nvidia/nemotron-3.5-super-vl-120b-a12b"
    /** 识图总开关（对话界面「识图」入口的开关）。 */
    private var _visionEnabled by mutableStateOf(prefs.getBoolean(KEY_VISION_ENABLED, true))
    val visionEnabled: Boolean get() = _visionEnabled
    fun setVisionEnabled(v: Boolean) {
        _visionEnabled = v
        prefs.edit().putBoolean(KEY_VISION_ENABLED, v).apply()
    }
    /** 是否使用「官方默认 AI + nemotron-3.5-super 识图」（走中转站日钥）。 */
    private var _visionUseOfficial by mutableStateOf(prefs.getBoolean(KEY_VISION_USE_OFFICIAL, true))
    val visionUseOfficial: Boolean get() = _visionUseOfficial
    fun setVisionUseOfficial(v: Boolean) {
        // 【1.4.9】nemotron 视觉辅助受「官方默认 AI」联动：官方 AI 关闭时不允许打开。
        // 存档值不改写：重新打开官方默认 AI 后，用户原来的选择自动恢复。
        if (v && !useOfficialAi) return
        _visionUseOfficial = v
        prefs.edit().putBoolean(KEY_VISION_USE_OFFICIAL, v).apply()
    }
    /** 自定义识图提供商（与 cloudProviders 同口径）。 */
    private var _visionProvider by mutableStateOf(prefs.getString(KEY_VISION_PROVIDER, "官方默认 AI") ?: "官方默认 AI")
    val visionProvider: String get() = _visionProvider
    fun setVisionProvider(v: String) {
        _visionProvider = v
        prefs.edit().putString(KEY_VISION_PROVIDER, v).apply()
    }
    /** 自定义识图 Base URL（留空 = 官方中转站）。 */
    private var _visionBaseUrl by mutableStateOf(prefs.getString(KEY_VISION_BASE_URL, "") ?: "")
    val visionBaseUrl: String get() = _visionBaseUrl
    fun setVisionBaseUrl(v: String) {
        _visionBaseUrl = v
        prefs.edit().putString(KEY_VISION_BASE_URL, v).apply()
    }
    /** 自定义识图 API Key（留空 = 用官方日钥）。 */
    private var _visionApiKey by mutableStateOf(prefs.getString(KEY_VISION_API_KEY, "") ?: "")
    val visionApiKey: String get() = _visionApiKey
    fun setVisionApiKey(v: String) {
        _visionApiKey = v
        prefs.edit().putString(KEY_VISION_API_KEY, v).apply()
    }
    /** 自定义识图模型名（留空 = OFFICIAL_VISION_MODEL）。 */
    private var _visionModel by mutableStateOf(prefs.getString(KEY_VISION_MODEL, "") ?: "")
    val visionModel: String get() = _visionModel
    fun setVisionModel(v: String) {
        _visionModel = v
        prefs.edit().putString(KEY_VISION_MODEL, v).apply()
    }

    /**
     * 【1.4.3】工具是 agent 的通用能力，不是可选辅助：固定启用，不再允许关闭。
     *
     * 旧版本（1.4.2）曾把 MKDIR/LS/READ/DEL/RUN/SEARCH/GH/FIND 做成「小工具」开关，
     * 关掉后既不写进 system 提示词、执行时也被拒绝；用户明确要求改成工具列表里的
     * 通用工具，因此这里恒为 true。保留 val/set 的函数签名只为兼容旧调用点
     * （设置页的开关已移除），存量的旧偏好值不再参与判断。
     */
    val toolFileEnabled: Boolean get() = true
    val toolRunEnabled: Boolean get() = true
    val toolSearchEnabled: Boolean get() = true
    val toolGithubEnabled: Boolean get() = true
    val toolFindEnabled: Boolean get() = true
    @Deprecated("工具固定启用，不再允许关闭", ReplaceWith(""))
    fun setToolFileEnabled(v: Boolean) {}
    @Deprecated("工具固定启用，不再允许关闭", ReplaceWith(""))
    fun setToolRunEnabled(v: Boolean) {}
    @Deprecated("工具固定启用，不再允许关闭", ReplaceWith(""))
    fun setToolSearchEnabled(v: Boolean) {}
    @Deprecated("工具固定启用，不再允许关闭", ReplaceWith(""))
    fun setToolGithubEnabled(v: Boolean) {}
    @Deprecated("工具固定启用，不再允许关闭", ReplaceWith(""))
    fun setToolFindEnabled(v: Boolean) {}
    private var _reasoningEnabled by mutableStateOf(prefs.getBoolean(KEY_REASONING_ON, false))
    val reasoningEnabled: Boolean get() = _reasoningEnabled
    fun setReasoningEnabled(v: Boolean) {
        _reasoningEnabled = v
        prefs.edit().putBoolean(KEY_REASONING_ON, v).apply()
    }

    /**
     * 推理等级（6 档）：light → medium → high → extra_high → max → ultra。
     *
     * 等级语义对齐主流推理产品的六档口径：低档是机械任务，高档是多步规划/自我审查。
     * 上游能接受的 `reasoning_effort` 只有 low / medium / high，所以高 3 档不是靠
     * 传一个上游不认识的值，而是「high + 更大的 max_tokens + system 追加自审要求」
     * 来表达强度差异，见 [upstreamReasoningEffort] / [reasoningMaxTokens] /
     * [reasoningSystemSuffix]。
     */
    private var _reasoningEffort by mutableStateOf(
        normalizeReasoningLevel(prefs.getString(KEY_REASONING_EFFORT, "high"))
    )
    val reasoningEffort: String get() = _reasoningEffort

    /** 兼容旧入口：传入 6 档之一或旧的 low（自动归到 light）。 */
    fun setReasoningEffort(v: String) {
        _reasoningEffort = normalizeReasoningLevel(v)
        prefs.edit().putString(KEY_REASONING_EFFORT, _reasoningEffort).apply()
    }

    /** 当前档位下标（0..5），给滑条用。 */
    val reasoningLevelIndex: Int
        get() = REASONING_LEVELS.indexOf(_reasoningEffort).coerceAtLeast(0)

    /** 按滑条下标设置档位。 */
    fun setReasoningLevel(index: Int) {
        val i = index.coerceIn(0, REASONING_LEVELS.size - 1)
        setReasoningEffort(REASONING_LEVELS[i])
    }

    /** 档位中文标签（如「High（高度）」）。 */
    val reasoningLabel: String
        get() = REASONING_LABELS[_reasoningEffort] ?: REASONING_LABELS.getValue("high")

    /** 档位一句话说明（面板里显示）。 */
    val reasoningDesc: String
        get() = REASONING_DESCS[_reasoningEffort] ?: REASONING_DESCS.getValue("high")

    /** 真正发给上游的 reasoning_effort（只会是 low / medium / high）。 */
    val upstreamReasoningEffort: String
        get() = when (_reasoningEffort) {
            "light" -> "low"
            "medium" -> "medium"
            else -> "high"
        }

    /** 该档位要不要带 max_tokens；null 表示不带（交给上游默认）。 */
    val reasoningMaxTokens: Int?
        get() = when (_reasoningEffort) {
            "extra_high" -> 8192
            "max" -> 12288
            "ultra" -> 16384
            else -> null
        }

    /** 高 3 档在 system 末尾追加的强度要求（不改变原有语义）。 */
    val reasoningSystemSuffix: String
        get() = when (_reasoningEffort) {
            "extra_high" -> "\n\n回答前先自检：是否有遗漏、是否有更好的方案；直接给最终结论，不要展示推理过程。"
            "max" -> "\n\n回答前先做多步规划并自检一遍；结论要完整、可执行，不要展示推理过程。"
            "ultra" -> "\n\n回答前先做多步规划，逐条自检边界情况与失败路径，再给完整可执行结论；不要展示推理过程。"
            else -> ""
        }

    private var _thinkingGuide by mutableStateOf(prefs.getBoolean(KEY_THINKING_GUIDE, false))
    val thinkingGuide: Boolean get() = _thinkingGuide
    fun setThinkingGuide(v: Boolean) {
        _thinkingGuide = v
        prefs.edit().putBoolean(KEY_THINKING_GUIDE, v).apply()
    }

    /** Max 模式：放开输出上限（max_tokens=8192），适合长代码/长方案。 */
    private var _maxMode by mutableStateOf(prefs.getBoolean(KEY_MAX_MODE, false))
    val maxMode: Boolean get() = _maxMode
    fun setMaxMode(v: Boolean) {
        _maxMode = v
        prefs.edit().putBoolean(KEY_MAX_MODE, v).apply()
    }

    /** Waifu 模式：AI 回复按句分段呈现（对齐 Operit 的「分句发送」）。 */
    private var _waifuSplit by mutableStateOf(prefs.getBoolean(KEY_WAIFU_SPLIT, false))
    val waifuSplit: Boolean get() = _waifuSplit
    fun setWaifuSplit(v: Boolean) {
        _waifuSplit = v
        prefs.edit().putBoolean(KEY_WAIFU_SPLIT, v).apply()
    }

    /** 语音服务：AI 回复完成后用系统 TTS 自动朗读。 */
    private var _ttsAutoRead by mutableStateOf(prefs.getBoolean(KEY_TTS_AUTO_READ, false))
    val ttsAutoRead: Boolean get() = _ttsAutoRead
    fun setTtsAutoRead(v: Boolean) {
        _ttsAutoRead = v
        prefs.edit().putBoolean(KEY_TTS_AUTO_READ, v).apply()
    }

    /** TTS 语速（0.5 ~ 2.0，1.0 为系统默认）。 */
    private var _ttsRate by mutableStateOf(prefs.getFloat(KEY_TTS_RATE, 1.0f))
    val ttsRate: Float get() = _ttsRate
    fun setTtsRate(v: Float) {
        _ttsRate = v.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat(KEY_TTS_RATE, _ttsRate).apply()
    }

    /**
     * 本机 Token 用量统计（估算：字符数 / 4，与 OpenAI 的中英混排经验值一致）。
     * 只统计本机发起的对话，不联网、不上报。
     */
    private var _statCalls by mutableStateOf(prefs.getLong(KEY_STAT_CALLS, 0L))
    private var _statPromptChars by mutableStateOf(prefs.getLong(KEY_STAT_PROMPT, 0L))
    private var _statReplyChars by mutableStateOf(prefs.getLong(KEY_STAT_REPLY, 0L))
    val statCalls: Long get() = _statCalls
    val statPromptChars: Long get() = _statPromptChars
    val statReplyChars: Long get() = _statReplyChars
    val statPromptTokens: Long get() = _statPromptChars / 4
    val statReplyTokens: Long get() = _statReplyChars / 4

    /** 单日用量（本机累计，用于趋势图）。 */
    data class DailyUsage(val date: String, val promptChars: Long, val replyChars: Long) {
        /** 展示用短日期：MM-dd */
        val label: String get() = if (date.length >= 10) date.substring(5) else date
        val tokens: Long get() = (promptChars + replyChars) / 4
    }

    private var _statDaily by mutableStateOf(loadDailyUsage())

    /** 最近 10 天（含今天），按日期升序；空表示还没有任何记录。 */
    val statDaily: List<DailyUsage> get() = _statDaily.takeLast(10)

    // ---------------- 【1.4.2】按来源拆分：官方默认 API / 自定义 API ----------------
    /**
     * 单日、单来源用量。
     *
     * 为什么要拆：官方默认走中转站（动态日钥），自定义走用户自己填的网址/Key，
     * 两者的额度与计费方式完全不同，混在一个总数里看不出「到底是谁在消耗」。
     */
    data class ApiDay(
        val date: String,
        val calls: Long,
        val promptChars: Long,
        val replyChars: Long,
    ) {
        val label: String get() = if (date.length >= 10) date.substring(5) else date
        val tokens: Long get() = (promptChars + replyChars) / 4
    }

    /** 一个来源的汇总（累计 + 最近 7 天）。 */
    data class ApiStat(
        val calls: Long,
        val promptChars: Long,
        val replyChars: Long,
        val daily: List<ApiDay>,
    ) {
        val promptTokens: Long get() = promptChars / 4
        val replyTokens: Long get() = replyChars / 4
        val totalTokens: Long get() = (promptChars + replyChars) / 4
    }

    private var _offCalls by mutableStateOf(prefs.getLong(KEY_STAT_OFF_CALLS, 0L))
    private var _offPrompt by mutableStateOf(prefs.getLong(KEY_STAT_OFF_PROMPT, 0L))
    private var _offReply by mutableStateOf(prefs.getLong(KEY_STAT_OFF_REPLY, 0L))
    private var _cusCalls by mutableStateOf(prefs.getLong(KEY_STAT_CUS_CALLS, 0L))
    private var _cusPrompt by mutableStateOf(prefs.getLong(KEY_STAT_CUS_PROMPT, 0L))
    private var _cusReply by mutableStateOf(prefs.getLong(KEY_STAT_CUS_REPLY, 0L))
    private var _offDaily by mutableStateOf(loadApiDaily(KEY_STAT_DAILY_OFF))
    private var _cusDaily by mutableStateOf(loadApiDaily(KEY_STAT_DAILY_CUS))

    init {
        seedSplitUsage()
    }

    /**
     * 老版本只存了「合计」，没有来源维度。升级后第一次进来时把老数据整段归入官方桶，
     * 否则用户会看到两个 0 —— 那比不拆更容易被当成 bug。
     */
    private fun seedSplitUsage() {
        if (prefs.getBoolean(KEY_STAT_SPLIT_SEEDED, false)) return
        val hasSplit = prefs.contains(KEY_STAT_OFF_CALLS) || prefs.contains(KEY_STAT_CUS_CALLS)
        if (!hasSplit && _statCalls > 0L) {
            _offCalls = _statCalls
            _offPrompt = _statPromptChars
            _offReply = _statReplyChars
            prefs.edit()
                .putLong(KEY_STAT_OFF_CALLS, _offCalls)
                .putLong(KEY_STAT_OFF_PROMPT, _offPrompt)
                .putLong(KEY_STAT_OFF_REPLY, _offReply)
                .apply()
        }
        prefs.edit().putBoolean(KEY_STAT_SPLIT_SEEDED, true).apply()
    }

    private fun loadApiDaily(key: String): List<ApiDay> {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 4) return@mapNotNull null
            if (p[0].length < 10) return@mapNotNull null
            ApiDay(
                date = p[0],
                calls = p[1].toLongOrNull() ?: 0L,
                promptChars = p[2].toLongOrNull() ?: 0L,
                replyChars = p[3].toLongOrNull() ?: 0L,
            )
        }.sortedBy { it.date }
    }

    private fun saveApiDaily(key: String, list: List<ApiDay>) {
        prefs.edit()
            .putString(
                key,
                list.joinToString("\n") { "${it.date}|${it.calls}|${it.promptChars}|${it.replyChars}" },
            )
            .apply()
    }

    private fun recordApiUsage(official: Boolean, promptChars: Int, replyChars: Int) {
        val key = todayKey()
        val base = if (official) _offDaily else _cusDaily
        val next = base.toMutableList()
        val idx = next.indexOfFirst { it.date == key }
        if (idx >= 0) {
            val o = next[idx]
            next[idx] = o.copy(
                calls = o.calls + 1,
                promptChars = o.promptChars + promptChars,
                replyChars = o.replyChars + replyChars,
            )
        } else {
            next.add(ApiDay(key, 1L, promptChars.toLong(), replyChars.toLong()))
        }
        val trimmed = next.sortedBy { it.date }.takeLast(30)
        if (official) {
            _offDaily = trimmed
            saveApiDaily(KEY_STAT_DAILY_OFF, trimmed)
        } else {
            _cusDaily = trimmed
            saveApiDaily(KEY_STAT_DAILY_CUS, trimmed)
        }
    }

    /** 取某个来源的统计；official = true 表示官方默认（中转站）。 */
    fun apiStat(official: Boolean): ApiStat = if (official) {
        // daily 返回全量（最多 30 天），由界面按「最近 10 天」对齐补零，
        // 这样柱图根数固定，不会因为某些天没记录就少一根。
        ApiStat(_offCalls, _offPrompt, _offReply, _offDaily)
    } else {
        ApiStat(_cusCalls, _cusPrompt, _cusReply, _cusDaily)
    }

    private fun loadDailyUsage(): List<DailyUsage> {
        val raw = prefs.getString(KEY_STAT_DAILY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            val d = parts[0]
            if (d.length < 10) return@mapNotNull null
            DailyUsage(
                date = d,
                promptChars = parts[1].toLongOrNull() ?: 0L,
                replyChars = parts[2].toLongOrNull() ?: 0L,
            )
        }.sortedBy { it.date }
    }

    /** 当天键：yyyy-MM-dd（本地时区）。 */
    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** 把一次调用的用量累加到当天，并只保留最近 30 天。 */
    private fun recordDaily(promptChars: Int, replyChars: Int) {
        val key = todayKey()
        val next = _statDaily.toMutableList()
        val idx = next.indexOfFirst { it.date == key }
        if (idx >= 0) {
            val old = next[idx]
            next[idx] = old.copy(
                promptChars = old.promptChars + promptChars,
                replyChars = old.replyChars + replyChars,
            )
        } else {
            next.add(DailyUsage(key, promptChars.toLong(), replyChars.toLong()))
        }
        _statDaily = next.sortedBy { it.date }.takeLast(30)
        prefs.edit()
            .putString(
                KEY_STAT_DAILY,
                _statDaily.joinToString("\n") { "${it.date}|${it.promptChars}|${it.replyChars}" },
            )
            .apply()
    }

    /**
     * 记录一次调用。
     *
     * [official] 由调用方按「这次请求真正打到哪个地址」传入（见 AIChatService.lastOfficial），
     * 不要用「用户当前开关」猜——否则切开关时会把历史算到错的桶里。
     */
    fun addUsage(promptChars: Int, replyChars: Int, official: Boolean = true) {
        val p = promptChars.coerceAtLeast(0)
        val r = replyChars.coerceAtLeast(0)
        _statCalls += 1
        _statPromptChars += p.toLong()
        _statReplyChars += r.toLong()
        recordDaily(p, r)
        if (official) {
            _offCalls += 1
            _offPrompt += p.toLong()
            _offReply += r.toLong()
        } else {
            _cusCalls += 1
            _cusPrompt += p.toLong()
            _cusReply += r.toLong()
        }
        recordApiUsage(official, p, r)
        prefs.edit()
            .putLong(KEY_STAT_CALLS, _statCalls)
            .putLong(KEY_STAT_PROMPT, _statPromptChars)
            .putLong(KEY_STAT_REPLY, _statReplyChars)
            .putLong(KEY_STAT_OFF_CALLS, _offCalls)
            .putLong(KEY_STAT_OFF_PROMPT, _offPrompt)
            .putLong(KEY_STAT_OFF_REPLY, _offReply)
            .putLong(KEY_STAT_CUS_CALLS, _cusCalls)
            .putLong(KEY_STAT_CUS_PROMPT, _cusPrompt)
            .putLong(KEY_STAT_CUS_REPLY, _cusReply)
            .apply()
    }

    fun resetUsage() {
        _statCalls = 0L
        _statPromptChars = 0L
        _statReplyChars = 0L
        _statDaily = emptyList()
        _offCalls = 0L
        _offPrompt = 0L
        _offReply = 0L
        _cusCalls = 0L
        _cusPrompt = 0L
        _cusReply = 0L
        _offDaily = emptyList()
        _cusDaily = emptyList()
        prefs.edit()
            .putLong(KEY_STAT_CALLS, 0L)
            .putLong(KEY_STAT_PROMPT, 0L)
            .putLong(KEY_STAT_REPLY, 0L)
            .putLong(KEY_STAT_OFF_CALLS, 0L)
            .putLong(KEY_STAT_OFF_PROMPT, 0L)
            .putLong(KEY_STAT_OFF_REPLY, 0L)
            .putLong(KEY_STAT_CUS_CALLS, 0L)
            .putLong(KEY_STAT_CUS_PROMPT, 0L)
            .putLong(KEY_STAT_CUS_REPLY, 0L)
            .remove(KEY_STAT_DAILY)
            .remove(KEY_STAT_DAILY_OFF)
            .remove(KEY_STAT_DAILY_CUS)
            .apply()
    }

    /**
     * 【1.4.0 新增】提示词预设（纯本地）。
     *
     * 存储格式：每条 "名称\u0001内容"。之所以存成字符串集合而不是 JSON：
     * 无需额外依赖、写入原子，且 SharedPreferences 的 StringSet 已在别处使用。
     */
    private var _promptPresets by mutableStateOf(
        prefs.getStringSet(KEY_PROMPT_PRESETS, emptySet())?.toSet() ?: emptySet()
    )

    /** 已保存的预设，按名称排序；内容只读。 */
    val promptPresets: List<Pair<String, String>>
        get() = _promptPresets.map { raw ->
            val i = raw.indexOf(PRESET_SEP)
            if (i <= 0) raw to "" else raw.substring(0, i) to raw.substring(i + 1)
        }.sortedBy { it.first }

    /** 新增/覆盖同名预设。名称为空或内容为空时返回 false（不做无意义写入）。 */
    fun savePromptPreset(name: String, content: String): Boolean {
        val n = name.trim()
        val c = content.trim()
        if (n.isEmpty() || c.isEmpty()) return false
        val next = _promptPresets
            .filterNot { it.substringBefore(PRESET_SEP) == n }
            .toSet() + (n + PRESET_SEP + c)
        _promptPresets = next
        prefs.edit().putStringSet(KEY_PROMPT_PRESETS, next).apply()
        return true
    }

    fun removePromptPreset(name: String) {
        val next = _promptPresets.filterNot { it.substringBefore(PRESET_SEP) == name }.toSet()
        _promptPresets = next
        prefs.edit().putStringSet(KEY_PROMPT_PRESETS, next).apply()
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
        // 【1.4.1】审查 AI 属「其他界面」：模型名必须与实际路由一致
        // （自定义密钥 → 全局自定义模型；否则 → 对话页选的 generalModel），
        // 否则会出现「模型名发给另一个端点」的 400/404。
        _reviewModelName?.takeIf { it.isNotBlank() } ?: generalModel

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
    private const val KEY_USE_CUSTOM_KEY = "ai_use_custom_key"
    private const val KEY_GENERAL_MODEL = "ai_general_model"
    private const val KEY_CLOUD_ENDPOINT = "ai_cloud_endpoint"
    private const val KEY_CLOUD_API_KEY = "ai_cloud_api_key"
    private const val KEY_CLOUD_MODEL = "ai_cloud_model"
    private const val KEY_CLOUD_TEMP = "ai_cloud_temperature"
    private const val KEY_CHAT_HISTORY_LIMIT = "ai_chat_history_limit"
    private const val KEY_CHAT_SYSTEM_PROMPT = "ai_chat_system_prompt"
    private const val KEY_CHAT_STREAM = "ai_chat_stream_enabled"
    private const val KEY_CHAT_RETRY = "ai_chat_retry_count"
    private const val KEY_CHAT_TIMEOUT = "ai_chat_timeout_sec"
    private const val KEY_PROMPT_PRESETS = "ai_prompt_presets"
    private const val KEY_REASONING_ON = "ai_reasoning_enabled"
    private const val KEY_REASONING_EFFORT = "ai_reasoning_effort"
    private const val KEY_THINKING_GUIDE = "ai_thinking_guide"
    private const val KEY_MAX_MODE = "ai_max_mode"
    private const val KEY_WAIFU_SPLIT = "ai_waifu_split"
    private const val KEY_TTS_AUTO_READ = "ai_tts_auto_read"
    private const val KEY_TTS_RATE = "ai_tts_rate"
    private const val KEY_STAT_CALLS = "ai_stat_calls"
    private const val KEY_STAT_OFF_CALLS = "ai_stat_off_calls"
    private const val KEY_STAT_OFF_PROMPT = "ai_stat_off_prompt"
    private const val KEY_STAT_OFF_REPLY = "ai_stat_off_reply"
    private const val KEY_STAT_CUS_CALLS = "ai_stat_cus_calls"
    private const val KEY_STAT_CUS_PROMPT = "ai_stat_cus_prompt"
    private const val KEY_STAT_CUS_REPLY = "ai_stat_cus_reply"
    private const val KEY_STAT_DAILY_OFF = "ai_stat_daily_off"
    private const val KEY_STAT_DAILY_CUS = "ai_stat_daily_cus"
    private const val KEY_STAT_SPLIT_SEEDED = "ai_stat_split_seeded"
    private const val KEY_STAT_PROMPT = "ai_stat_prompt_chars"
    private const val KEY_STAT_REPLY = "ai_stat_reply_chars"
    private const val KEY_STAT_DAILY = "ai_stat_daily"
    /** 预设条目内部分隔符（名称 + \u0001 + 内容）。用不可见控制符，避免与用户文本冲突。 */
    private const val PRESET_SEP = "\u0001"
    /** GitHub 访问令牌（可选）：配置后模块开发 AI 的 GH/FIND 工具额度 60 → 5000 次/小时。 */
    private const val KEY_GH_TOKEN = "ai_gh_token"
    private const val KEY_REVIEW_MODEL = "ai_review_model"
    private const val KEY_TRACE_TIMEOUT_SECONDS = "ai_trace_timeout_seconds"
    private const val KEY_OFFICIAL_MODEL = "ai_official_model"
    // ==================== 【1.4.2】上下文长度 ====================
    private const val KEY_CHAT_SYSTEM_CHARS = "ai_chat_system_chars"
    private const val KEY_CHAT_HISTORY_ITEM_CHARS = "ai_chat_history_item_chars"
    private const val KEY_CHAT_PROMPT_CHARS = "ai_chat_prompt_chars"
    private const val KEY_SHOW_REASONING = "ai_show_reasoning"
    // ==================== 【1.4.3】识图（OCR / 视觉模型） ====================
    private const val KEY_VISION_ENABLED = "ai_vision_enabled"
    private const val KEY_VISION_USE_OFFICIAL = "ai_vision_use_official"
    private const val KEY_VISION_PROVIDER = "ai_vision_provider"
    private const val KEY_VISION_BASE_URL = "ai_vision_base_url"
    private const val KEY_VISION_API_KEY = "ai_vision_api_key"
    private const val KEY_VISION_MODEL = "ai_vision_model"
    // ==================== 【1.4.2】小工具开关（已废弃，保留常量兼容旧偏好） ====================
    private const val KEY_TOOL_FILE = "ai_tool_file"
    private const val KEY_TOOL_RUN = "ai_tool_run"
    private const val KEY_TOOL_SEARCH = "ai_tool_search"
    private const val KEY_TOOL_GH = "ai_tool_gh"
    private const val KEY_TOOL_FIND = "ai_tool_find"


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

    // ============ 官方默认 AI（经中转站，设备序列号密钥） ============
    /**
     * 官方默认服务的固定配置（OpenAI 兼容接口）。
     *
     * 【v1.4.0 链路切换】不再直连 NVIDIA，改走中转站（Cloudflare Worker）：
     * - 聊天端点 = 中转站地址 + 隐藏入口前缀（ENTRY_PREFIX） + /v1/chat/completions；
     * - 鉴权 = 中转站「当日动态密钥」（日钥，每日轮换、任一时刻 2 把可用），
     *   运行期由 frb.axeron.manager.ai.axkey.AxKeyProvider 用构建期注入的访问
     *   密码（AXKEY_PASS）换取，不在包内固化；
     * - 模型可选（快速 / 慢速 / 小模型三档，见 officialModelGroups），默认 gpt-oss-20b。
     *
     * 【历史】切换前直连 NIM：原模型 `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`
     * 在 NIM 上持续返回 503 `ResourceExhausted: Worker local total request limit
     * reached (49/16)`，实测（2026-10-03）无法使用；后换为实测稳定的
     * `openai/gpt-oss-20b`（开源 20B，不传 max_tokens 的默认请求下 content 即为
     * 最终答案，finish_reason=stop）。
     *
     * 【源码脱敏版】所有官方服务地址一律用占位符，源码树里不携带任何真实域名；
     * 自行部署后把下面常量里的 `Your link` 换成你自己的地址即可。
     */
    const val AI_URL_PLACEHOLDER = "Your link"
    /** 判断一个地址是否是可用的 http(s) 地址；占位符 / 空串一律当作「未配置」。 */
    fun isUsableUrl(u: String?): Boolean =
        u != null && (u.startsWith("http://") || u.startsWith("https://"))
    const val OFFICIAL_AI_ENDPOINT = AI_URL_PLACEHOLDER
    /** 设备注册：校验结果、序列号与参考值一致时，服务端才下发设备密钥。 */
    const val OFFICIAL_AI_DEVICE_URL = AI_URL_PLACEHOLDER
    /** 查询当前设备的本月额度与剩余 tokens。 */
    const val OFFICIAL_AI_DEVICE_QUOTA_URL = AI_URL_PLACEHOLDER
    /** 旧日钥端点仅保留给旧版本兼容；服务端默认已关闭。 */
    const val OFFICIAL_AI_AXKEY_URL = AI_URL_PLACEHOLDER

    /** 官方模型默认值（未选择时使用；也是「小模型」档的模型）。 */
    const val OFFICIAL_AI_MODEL = "openai/gpt-oss-20b"
    const val OFFICIAL_AI_PROVIDER = "英伟达 (NVIDIA)"

    // ---------- 官方模型选择（快速 / 慢速 / 小模型 三档） ----------
    /** 官方模型选项：value = 请求时发送给中转站的模型名；displayName = 界面显示名。 */
    data class OfficialModelOption(val value: String, val displayName: String)

    /** 官方模型分组（标题 + 选项列表）。 */
    data class OfficialModelGroup(val title: String, val options: List<OfficialModelOption>)

    /**
     * 官方模型分组数据（供「官方模型」选择弹窗使用）。
     *
     * 分档依据（实测）：快速档在中转站 / NVIDIA 免费层首响较快；慢速档为推理型 / 大装载
     * 模型（kimi-k3 每次约 190~230s 装载实例）；小模型档为轻量 20B 模型（默认使用）。
     *
     * 注意：`kimi-k3` 必须保持这个「短名」形态——中转站的别名归一化
     * （MODEL_ALIASES：kimi-k3 -> moonshotai/kimi-k3）与慢档优化链
     * （SLOW_HEDGE_MODELS 等）按短名匹配。
     */
    val officialModelGroups: List<OfficialModelGroup> = listOf(
        OfficialModelGroup(
            title = "快速模型",
            options = listOf(
                OfficialModelOption("z-ai/glm-5.3-flash", "GLM-5.3 Flash"),
                OfficialModelOption("nvidia/nemotron-3-ultra-550b-a55b", "Nemotron-3 Ultra"),
                // 中转站第二上游（x-relay-upstream: amd，2026-10-23 实测可用）。
                // 2026-10-24 按要求从「慢速模型」移到「快速模型」：只此一条，不要重复添加。
                OfficialModelOption("DeepSeek-V4-Flash", "DeepSeek-V4-Flash"),
            ),
        ),
        OfficialModelGroup(
            title = "慢速模型",
            options = listOf(
                OfficialModelOption("deepseek-ai/deepseek-v4.1-flash", "DeepSeek-V4.1 Flash"),
                // Kimi K3 已于 2026-10-15 上游 EOL，中转站目录里已无 kimi/moonshot，
                // 老客户端请求会被别名改写到 DeepSeek-V4-Flash，因此不再作为可选项。
            ),
        ),
        OfficialModelGroup(
            title = "小模型",
            options = listOf(
                OfficialModelOption("openai/gpt-oss-20b", "GPT-OSS-20B"),
            ),
        ),
    )

    private var _officialModelName by mutableStateOf(
        prefs.getString(KEY_OFFICIAL_MODEL, OFFICIAL_AI_MODEL) ?: OFFICIAL_AI_MODEL
    )

    /** 当前官方模型（用户在「官方模型」弹窗中选择；默认 [OFFICIAL_AI_MODEL]）。 */
    val officialModelName: String get() = _officialModelName

    fun setOfficialModelName(v: String) {
        _officialModelName = v
        prefs.edit().putString(KEY_OFFICIAL_MODEL, v).apply()
    }

    /** 把模型 ID 映射为用户可读的显示名（找不到则原样返回）。 */
    fun officialModelDisplayName(model: String = officialModelName): String =
        officialModelGroups.asSequence()
            .flatMap { it.options.asSequence() }
            .firstOrNull { it.value == model }
            ?.displayName ?: modelDisplayName(model)

    /**
     * 官方默认 AI 的 API Key（直连 NIM 时代的静态密钥）。
     *
     * 【v1.4.0 起不再参与官方请求】官方链路已改走中转站 + 动态日钥（见 [AXKEY_PASS]），
     * 本常量保留仅为历史兼容（构建注入机制不变，CI 侧可继续携带或移除）。
     *
     * 【不再硬编码】密钥由构建期注入（见 manager/build.gradle.kts 的
     * `officialAiKeyBlob()` -> BuildConfig.OFFICIAL_AI_KEY_BLOB）：
     *   - 源码与 git 仓库内没有明文；
     *   - APK 的 dex 里只有 XOR+十六进制混淆后的字节，`strings` 无法直接提取；
     *   - 未注入（未配置 CI Secret/Variable 且本地未写 local.properties）时为空串。
     */
    val OFFICIAL_AI_API_KEY: String by lazy { decodeOfficialAiKey(BuildConfig.OFFICIAL_AI_KEY_BLOB) }

    /** 官方默认 AI（直连时代）密钥是否已注入（历史兼容保留，不参与运行时判定）。 */
    val officialAiKeyAvailable: Boolean get() = OFFICIAL_AI_API_KEY.isNotBlank()

    /**
     * 官方默认 AI（v1.4.0 起）的「访问密码」（AXKEY_PASS）。
     *
     * 与 OFFICIAL_AI_API_KEY 的区别：这是中转站的「换钥密码」，不是上游 API Key。
     * 运行期由 frb.axeron.manager.ai.axkey.AxKeyProvider 用它向中转站换取
     * 「当日动态密钥」（日钥），日钥才用于聊天请求的鉴权。
     * 同样由构建期注入 + XOR/hex 混淆（见 manager/build.gradle.kts 的
     * `axkeyPassBlob()` -> BuildConfig.AXKEY_PASS_BLOB）。
     */
    val AXKEY_PASS: String by lazy { decodeAxkeyPass(BuildConfig.AXKEY_PASS_BLOB) }

    /** 官方默认 AI（中转站）是否可用；设备注册不再依赖包内访问密码。 */
    val officialAxkeyAvailable: Boolean get() = isUsableUrl(OFFICIAL_AI_DEVICE_URL)

    /** 与 manager/build.gradle.kts:OFFICIAL_AI_KEY_SEED 严格对应（改一处必须同步改另一处）。 */
    private const val OFFICIAL_AI_KEY_SEED = "frb.axeron.manager|AxManagerD/axkey/v1"

    /** 与 manager/build.gradle.kts 的 axkeyPassBlob() seed 严格对应（改一处必须同步改另一处）。 */
    private const val AXKEY_PASS_SEED = "frb.axeron.manager|AxManagerD/axkey/pass/v1"

    // ============ 「非模块功能」默认 AI（NVIDIA 直连，内置密钥） ============
    /**
     * AI 用途分流。
     *
     * - [MODULE_DEV]：「制作模块」里的聊天框 → 走中转站（动态日钥）。
     * - [GENERAL]：其余所有 AI 功能（拦截分析、审查、恢复脚本、终端 AI、云端对话等）
     *   → 默认直连 NVIDIA（内置密钥 + openai/gpt-oss-20b）。
     *
     * 为什么分流：中转站日钥按天轮换，且受 CF 边缘节点与上游并发影响；
     * 把它限定在单一入口内，避免一次中转站抖动导致全应用 AI 功能不可用。
     */
    enum class AiPurpose { MODULE_DEV, GENERAL }

    /** 内置 NVIDIA 直连配置（GENERAL 的默认路由）。 */
    const val BUILTIN_AI_ENDPOINT = AI_URL_PLACEHOLDER
    const val BUILTIN_AI_MODEL = "openai/gpt-oss-20b"
    const val BUILTIN_AI_PROVIDER = "英伟达 (NVIDIA)"

    /**
     * 【1.4.9】「其他界面」的默认模型。
     *
     * 只影响 [generalModel] 的缺省值（拦截分析 / 审查 / 恢复脚本 / 终端 AI / 云端对话等），
     * 「制作模块」（AI 辅助）仍用 [officialModelName]，不受影响。
     * 名字太长，界面需要简化显示，见 [GENERAL_AI_MODEL_LABEL] / [modelDisplayName]。
     */
    const val GENERAL_AI_MODEL = "nvidia/nemotron-3.5-content-safety"
    const val GENERAL_AI_MODEL_LABEL = "Nemotron内容安全"

    /** 模型 ID → 简短显示名（只映射已知的长 ID，其余原样返回）。 */
    fun modelDisplayName(model: String): String = when (model) {
        GENERAL_AI_MODEL -> GENERAL_AI_MODEL_LABEL
        BUILTIN_AI_MODEL -> "GPT-OSS-20B"
        else -> model
    }

    /** 与 manager/build.gradle.kts:builtinAiKeyBlob() 的 seed 严格对应。 */
    private const val BUILTIN_AI_KEY_SEED = "frb.axeron.manager|AxManagerD/ai/builtin/v1"

    /**
     * 【源码脱敏版】原实现这里留了一段「XOR+hex 混淆后的内置密钥」作为兜底。
     *
     * 那段密文是可以被直接解出来的（seed 就在上方常量里），等于把可白嫖的密钥随源码发布，
     * 因此在公开源码里**已整段删除**，兜底值固定为空串：
     *   - 想用内置直连，请在构建期通过 local.properties / 环境变量自行注入；
     *   - 未注入时 [builtinAiAvailable] 为 false，界面会提示「未配置 AI」。
     */
    private const val BUILTIN_AI_KEY_BLOB_FALLBACK = ""

    /** 内置 NVIDIA 密钥（构建期 blob 优先，否则解码密文兜底）。 */
    val BUILTIN_AI_API_KEY: String by lazy {
        val injected = decodeXorHex(BuildConfig.BUILTIN_AI_KEY_BLOB, BUILTIN_AI_KEY_SEED)
        if (injected.isNotBlank()) injected
        else decodeXorHex(BUILTIN_AI_KEY_BLOB_FALLBACK, BUILTIN_AI_KEY_SEED)
    }

    /** 内置直连是否可用。 */
    val builtinAiAvailable: Boolean get() = BUILTIN_AI_API_KEY.isNotBlank()

    /** 用户是否填了完整自定义配置（网址 + Key）；有则 GENERAL 优先用户配置。 */
    val hasCustomCloudConfig: Boolean
        get() = !cloudEndpoint.isNullOrBlank() && !cloudApiKey.isNullOrBlank()

    /**
     * 【1.4.3】当前「对话」真正生效的路由标签（供聊天界面状态条显示）。
     *
     * 修复：此前聊天页把标签写死成「官方 · 模型名」，用户关掉官方默认 AI、改用
     * 自定义 API 后，界面仍显示官方模型，与实际请求路由不一致（实际已走自定义）。
     * 这里与 AIChatService.resolveCloudConfig 的三级路由保持同一口径。
     */
    fun chatRouteLabel(purpose: AiPurpose = AiPurpose.GENERAL): String {
        if (useCustomKey && hasCustomCloudConfig) {
            val provider = cloudProvider?.takeIf { it.isNotBlank() } ?: "自定义"
            val model = cloudModelName?.takeIf { it.isNotBlank() } ?: generalModel
            return "$provider · $model"
        }
        if (useOfficialAi) {
            val model = if (purpose == AiPurpose.MODULE_DEV) officialModelName else generalModel
            return "官方 · " + officialModelDisplayName(model)
        }
        return "内置 · " + generalModel
    }

    /**
     * 当前对话是否会打到官方中转站。
     *
     * 只有这一种情况需要在发送前换取「当日动态密钥」；走自定义 API 时不应再连中转站
     * （旧实现无条件 connect，会在纯自定义环境下把消息拦成「未连接到官方 AI」）。
     */
    val usesOfficialChatRoute: Boolean
        get() = !(useCustomKey && hasCustomCloudConfig) && useOfficialAi

    /**
     * 【1.4.5】识图的「有效官方路由」。
     *
     * 用户在识图面板里只能这样选：官方默认 AI 开启 → 出现 nemotron 辅助开关；
     * 官方默认 AI 关闭 → 面板只给自定义 API。因此「存档里选了官方」在官方 AI 关闭时
     * 必须按自定义处理，否则会出现「自定义 API 已填好，工具却被判成未配置」的死锁。
     * 存档值本身不动：重新打开官方默认 AI 后，nemotron 选择自动恢复。
     */
    val visionUseOfficialEffective: Boolean
        get() = visionUseOfficial && useOfficialAi

    /**
     * 【1.4.3】识图是否可用（决定 VISION 工具是否存在、是否允许调用）。
     *
     * 口径：
     *  - 识图总开关关闭 → 不可用；
     *  - 有效路由为官方（[visionUseOfficialEffective]）→ 走中转站日钥，需口令可用；
     *  - 有效路由为自定义 → Base URL / Key / 模型名三项齐全才可用。
     * 两边都不满足时，工具清单里不出现 VISION，避免模型发起必然失败的调用。
     */
    fun visionAvailable(): Boolean {
        if (!visionEnabled) return false
        return if (visionUseOfficialEffective) {
            officialAxkeyAvailable
        } else {
            // 自定义路由：Base URL / Key / 模型名三者齐全才算「配置好了」。
            // 模型名留空时没有可用的默认视觉模型（官方 VL 只在官方中转站有效），
            // 因此视为未配置，工具不挂出来，避免无效调用。
            visionBaseUrl.isNotBlank() && visionApiKey.isNotBlank() && visionModel.isNotBlank()
        }
    }

    /**
     * 一条可直接用于发请求的 AI 路由（endpoint / apiKey / model / provider）。
     *
     * 供「自己拼 HTTP 请求」的调用方复用（如 AIEngineManager.analyzeViaCloud），
     * 避免各处重复写一套「自定义还是内置」的判断而产生口径不一致。
     */
    data class AiRoute(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val provider: String,
    )

    /**
     * GENERAL 用途的路由解析：
     * 1) 用户填了网址 + Key → 用用户配置（模型/提供商留空则补内置默认值）；
     * 2) 否则 → 内置 NVIDIA 直连（密钥为混淆串解码所得，不对外展示）。
     */
    fun generalRoute(): AiRoute {
        // 【1.4.1 总开关口径】只有「使用自定义密钥」总开关打开、且网址+Key 齐全，
        // 才用用户配置（全局生效）。官方链路的 Key 是当日动态日钥，必须异步换取，
        // 不能在这里同步解析 —— 需要官方链路的调用方请用 AIChatService.resolveRoute()。
        if (useCustomKey && hasCustomCloudConfig) {
            return AiRoute(
                endpoint = cloudEndpoint.orEmpty(),
                apiKey = cloudApiKey.orEmpty(),
                model = cloudModelName?.takeIf { it.isNotBlank() } ?: generalModel,
                provider = cloudProvider?.takeIf { it.isNotBlank() } ?: BUILTIN_AI_PROVIDER,
            )
        }
        return AiRoute(
            endpoint = BUILTIN_AI_ENDPOINT,
            apiKey = BUILTIN_AI_API_KEY,
            model = generalModel,
            provider = BUILTIN_AI_PROVIDER,
        )
    }

    /**
     * 通用解码：十六进制 -> XOR（掩码按 seed 循环） -> 明文；失败返回空串
     * （当作未配置，避免把垃圾串当密钥发出去）。
     *
     * 与 manager/build.gradle.kts 的 blob 编码严格对应：
     * XOR（掩码按 seed 循环）-> 小写十六进制。改一处必须同步改另一处。
     * 不使用 java.util.Base64：十六进制自解码无任何依赖，也避开 Base64 的 API 级别限制。
     */
    private fun decodeXorHex(blob: String, seed: String): String {
        val hex = blob.trim()
        if (hex.isEmpty() || hex.length % 2 != 0) return ""
        return try {
            val cipher = ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val mask = seed.toByteArray(Charsets.UTF_8)
            if (mask.isEmpty() || cipher.isEmpty()) return ""
            val plain = ByteArray(cipher.size) { i ->
                (cipher[i].toInt() xor mask[i % mask.size].toInt()).toByte()
            }
            String(plain, Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }

    /** 解码构建期注入的官方 AI 密钥 blob（小写十六进制）；失败返回空串（当作未配置）。 */
    private fun decodeOfficialAiKey(blob: String): String = decodeXorHex(blob, OFFICIAL_AI_KEY_SEED)

    /** 解码构建期注入的中转站访问密码 blob（小写十六进制）；失败返回空串（当作未配置）。 */
    private fun decodeAxkeyPass(blob: String): String = decodeXorHex(blob, AXKEY_PASS_SEED)
}