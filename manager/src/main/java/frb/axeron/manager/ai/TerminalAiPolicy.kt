package frb.axeron.manager.ai

/**
 * 终端 AI 助手「提问范围」策略。
 *
 * 目的：把终端内的 AI 助手限制在「Shell / 命令 / 系统排障 / 本应用功能」范围内，
 * 阻止被当成通用聊天机器人使用（闲聊、写作、翻译、编程作业等一律拒绝）。
 *
 * 设计要点：
 * - 纯本地、无网络依赖：先做关键词判定，判定"明显越界"的直接短路拒绝，
 *   省一次网络请求，也让免费 AI 额度不被闲聊消耗。
 * - 语义兜底：命中不确定时交给 AI，由 [scopeDeclaration] 注入范围约束，
 *   让模型自己按规则拒绝越界提问。
 *
 * 本文件不修改任何既有公共方法，属于新增隔离模块。
 */
object TerminalAiPolicy {

    /** 允许的主题关键词（命中即放行，不再做本地拦截） */
    private val ALLOW_KEYWORDS = listOf(
        // —— Shell / 命令 ——
        "命令", "指令", "shell", "sh ", "bash", "终端", "console", "命令行",
        "参数", "选项", "flag", "语法", "管道", "pipe", "重定向", "redirect",
        "脚本", "script", "执行", "运行", "报错", "错误", "失败", "异常", "日志",
        "exit code", "退出码", "权限", "permission", "denied", "被拒",
        "root", "su ", "sudo", "adb", "fastboot", "shizuku", "dhizuku", "axeron",
        // —— 系统 / 设备 ——
        "android", "安卓", "系统", "firmware", "固件", "kernel", "内核", "rom",
        "包名", "package", "应用", "app ", "apk", "install", "安装", "卸载",
        "文件", "目录", "路径", "path", "file", "folder", "存储", "sdcard",
        "进程", "process", "pid", "内存", "memory", "cpu", "电池", "battery",
        "网络", "network", "wifi", "ip ", "端口", "port", "dns", "代理",
        "性能", "卡顿", "优化", "加速", "省电", "散热", "温控", "调频", "governor",
        "属性", "prop", "getprop", "setting", "settings get", "dumpsys", "pm ",
        "logcat", "dmesg", "top", "ps ", "grep", "find", "ls ", "cat ", "sed ",
        "awk", "chmod", "chown", "mount", "selinux", "分区", "刷机", "备份",
        // —— 本应用功能 ——
        "模块", "插件", "module", "plugin", "hook", "注入", "框架", "xposed",
        "axmanager", "本应用", "这个应用", "危险代码", "风险", "安全", "拦截",
        "白名单", "分析", "ai 引擎", "设置", "开关", "功能", "怎么用", "如何使用",
    )

    /** 明确越界的关键词（闲聊 / 创作 / 无关领域）——命中直接拒绝，不消耗额度 */
    private val DENY_KEYWORDS = listOf(
        // 闲聊
        "讲个笑话", "讲笑话", "唱首歌", "写首诗", "写首词", "聊聊天", "陪我聊",
        "你叫什么", "你是谁", "你有女朋友", "你喜欢", "讲个故事", "哄我",
        // 创作 / 内容生产
        "写作文", "写小说", "写文案", "写邮件", "写报告", "写总结", "写论文",
        "写情书", "演讲稿", "朋友圈文案", "起个名", "取个名", "起名字",
        // 翻译 / 语言
        "翻译成", "帮我翻译", "translate",
        // 通用知识 / 作业
        "数学题", "解题", "算一下", "物理题", "化学题", "历史问题", "考你",
        "讲一下三国", "百科",
        // 编程作业（与终端无关的代码生成）
        "写个爬虫", "写个网站", "写个游戏", "帮我写代码", "代码作业", "刷题",
        "leetcode",
        // 生活 / 娱乐
        "推荐电影", "推荐游戏", "点外卖", "菜谱", "做饭", "旅游攻略",
        "股票", "基金", "彩票", "算命", "星座",
    )

    /** 判定结果 */
    enum class Verdict {
        /** 明确允许（命中终端/系统主题） */
        ALLOW,

        /** 明确拒绝（闲聊/无关领域，本地直接拦截，不请求网络） */
        DENY,

        /** 不确定（交给 AI，由范围声明约束其自行拒绝） */
        UNSURE,
    }

    /**
     * 本地快速判定提问是否在允许范围内。
     *
     * 规则优先级：先判拒绝（寒暄式提问常夹带无关词），再判允许，最后 UNSURE。
     * 注意："你是谁/你叫什么" 这类身份提问会命中 DENY，但若同时包含终端主题词
     * （例如"你是谁，能不能帮我看看这个命令"），则改判 ALLOW，避免误伤。
     */
    fun judge(question: String): Verdict {
        val q = question.trim().lowercase()
        if (q.isEmpty()) return Verdict.DENY

        val hitsAllow = ALLOW_KEYWORDS.any { q.contains(it) }
        val hitsDeny = DENY_KEYWORDS.any { q.contains(it) }

        // 同时命中：以是否包含终端主题词为准（更长的具体主题优先）
        if (hitsDeny && !hitsAllow) return Verdict.DENY
        if (hitsAllow) return Verdict.ALLOW
        return Verdict.UNSURE
    }

    /** 本地拒绝时返回给用户的标准提示 */
    fun denyMessage(): String =
        "我只能回答本应用相关的问题（Shell 命令、系统排障、模块安全分析等）。\n" +
            "闲聊、写作、翻译、作业等无关内容不在我的能力范围内。"

    /**
     * 终端 AI 的「范围约束声明」，会拼到 system prompt 里。
     *
     * 这是与通用 ChatScreen 的核心区别：终端助手有明确的职责边界。
     */
    fun scopeDeclaration(): String = buildString {
        appendLine("【职责范围（必须严格遵守）】")
        appendLine("你是本应用终端（QuickShell）内置的助手，只服务于以下四类问题：")
        appendLine("  1. Shell 命令：解释命令含义、参数、语法；把自然语言需求转成可执行命令；")
        appendLine("  2. 报错排障：解释命令/脚本的报错原因，并给出具体修复方案；")
        appendLine("  3. 系统诊断：解读设备/系统相关信息（进程、内存、存储、网络、属性等）；")
        appendLine("  4. 本应用功能：模块与插件机制、安全分析、危险拦截、各项设置怎么用。")
        appendLine()
        appendLine("【必须拒绝的提问】")
        appendLine("  - 闲聊、寒暄、角色扮演、情感陪伴；")
        appendLine("  - 写作类：作文、小说、文案、邮件、报告、论文、起名等；")
        appendLine("  - 翻译、通用百科知识、学科作业、与终端无关的编程题；")
        appendLine("  - 生活娱乐类：影视/游戏推荐、菜谱、股票、星座等。")
        appendLine("遇到上述越界提问，直接回复一句：")
        appendLine("「我只能回答本应用相关的问题（Shell 命令、系统排障、模块安全分析等）。」")
        appendLine("然后停止，不要展开、不要顺着话题继续回答。")
        appendLine()
        appendLine("【回答风格】")
        appendLine("  - 简洁、直接、开发者导向；能给出可执行命令的优先给命令。")
        appendLine("  - 涉及命令时使用 Markdown 代码块，并说明是否有风险。")
        appendLine("  - 不确定的地方明确说不确定，不要编造。")
    }.trimEnd()

    /**
     * 终端 AI 的完整 system prompt = 范围约束 + 环境声明。
     *
     * 复用既有 [AIEnvironment.systemDeclaration]（不修改它），在其前面叠加范围约束，
     * 保证两条约束同时生效。
     */
    fun systemPrompt(): String =
        scopeDeclaration() + "\n\n" + AIEnvironment.systemDeclaration()

    /** 终端内预置的快捷提问（对应"只能问哪些问题"的引导） */
    val QUICK_QUESTIONS: List<Pair<String, String>> = listOf(
        "解释这条命令" to "请逐项解释下面这条命令的含义、每个参数的作用，以及是否有风险：\n",
        "报错怎么修" to "我执行命令后出现了下面的报错，请分析原因并给出修复方案：\n",
        "需求转命令" to "请把下面的需求转换成可直接执行的 Shell 命令（只给命令，不要多余解释）：\n",
        "这条命令安全吗" to "请判断下面这条命令是否安全，会修改哪些数据，有什么风险：\n",
        "怎么查系统信息" to "我想查看设备的系统信息，请给出对应的命令：\n",
        "本应用怎么用" to "请说明本应用的这个功能应该怎么用：\n",
    )
}
