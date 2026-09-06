package frb.axeron.manager.ai

import android.os.Build

/**
 * AI 环境上下文（环境文档 + 最高权限声明）。
 *
 * 目的：让云端 AI 在每次对话时都明确知道——
 *   1. 自己是谁、运行在什么环境里
 *   2. 自己的核心任务是什么
 *   3. 自己拥有哪些权限 / 能读取什么
 * 从而避免"答非所问"（例如把"模块会优化什么"答成"危险操作是什么"）。
 *
 * 该声明会被拼接到每次云端对话的 system prompt 前，作为全局身份注入。
 */
object AIEnvironment {

    /**
     * 判断一段脚本内容是否为「加密/混淆内容」，若是则【不应把脚本原文发给 AI】。
     *
     * 特征：
     * - 包含 base64 / openssl / enc（加密调用链）；
     * - 大量不可打印控制字符（二进制/加密壳）；
     * - 以 base64 密文长串为主（无 shell 结构）。
     *
     * 说明：加密脚本的原文对 AI 是乱码，发了也解析不出真实行为，反而浪费 tokens
     * 且可能误导 AI；此时应只把「运行时真实执行指令流」（strace/sh-x 抓到的）交给 AI。
     */
    fun isEncryptedScript(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val head = text.take(512)
        // 二进制/加密壳：大量控制字符
        val controlCount = head.count { it.code < 9 || (it.code in 14..31) }
        if (controlCount > 20 && !head.startsWith("#!")) return true
        // 加密调用链
        if (Regex("""base64\s+-d|openssl\s+enc|openssl\s+aes|\bdecrypt\b|\bxxd\s+-r""", RegexOption.IGNORE_CASE).containsMatchIn(head)) return true
        // 纯 base64 密文（长串、无 shell 关键字、无换行结构）
        if (head.length > 200 &&
            !text.contains("\n") &&
            Regex("""^(?:[A-Za-z0-9+/=]{100,})$""").containsMatchIn(head.trim())
        ) return true
        return false
    }

    /**
     * 生成统一的"环境 + 身份 + 权限"系统声明。
     *
     * 说明：
     * - currentPluginName / currentCmd 在拦截场景下由调用方传入，用于让 AI 聚焦当前上下文。
     * - 未传入时（例如普通 ChatScreen 对话）声明保持通用身份。
     */
    fun systemDeclaration(
        currentPluginName: String? = null,
        currentCmd: String? = null,
    ): String {
        val context = StringBuilder()

        // —— 身份与环境 ——
        context.appendLine("你是「Axeron 模块管理器」内置的 AI 安全分析助手。")
        context.appendLine("你运行在用户的 Android 设备上（系统版本：Android ${Build.VERSION.RELEASE}，SDK ${Build.VERSION.SDK_INT}）。")

        // —— 任务 ——
        context.appendLine("你的核心任务是：基于「当前模块实际要执行的脚本/命令内容」，")
        context.appendLine("准确回答用户关于该模块的功能、会做什么、会优化什么、有什么风险等问题。")
        context.appendLine("严禁凭空编造：只能依据下面给你的脚本与命令内容分析，不要泛泛而谈或答非所问。")

        // —— 最高权限声明 ——
        context.appendLine("你拥有本应用赋予的「最高分析权限」：")
        context.appendLine("  - 可以查看当前模块的完整拦截 payload（模块名、完整命令、命中规则列表、风险等级）。")
        context.appendLine("  - 可以读取当前模块的 action.sh 全量脚本来分析其真实行为。")
        context.appendLine("  - 可以查看执行环境信息（插件路径、沙箱目录、PATH 等）。")

        // —— 当前聚焦上下文 ——
        if (!currentPluginName.isNullOrBlank()) {
            context.appendLine("【当前模块】$currentPluginName")
        }
        if (!currentCmd.isNullOrBlank()) {
            context.appendLine("【当前待执行命令/脚本】见下方「模块执行内容」区块。")
        }

        return context.toString().trimEnd()
    }

    /**
     * 供拦截界面把完整 payload 打包成一段可读的上下文文本，交给 AI。
     * 这样 AI 能"看到"它拥有最高权限后可触达的全部信息。
     */
    fun payloadContext(payload: AIEngineManager.AnalysisPayload?): String {
        if (payload == null) return "(无拦截上下文)"
        val ctx = payload.ctx
        val result = payload.result
        val sb = StringBuilder()
        sb.appendLine("模块名：${ctx?.pluginName ?: "未知"}")
        sb.appendLine("插件目录 ID：${ctx?.pluginDirId ?: "未知"}")
        sb.appendLine("风险等级：${result?.risk ?: "SAFE"}")
        val rules = result?.matchedRules ?: emptyList()
        if (rules.isNotEmpty()) {
            sb.appendLine("命中规则列表：")
            rules.forEach { r ->
                sb.appendLine("  - ${r.name}：${r.matchedText}")
            }
        } else {
            sb.appendLine("命中规则：无")
        }

        // 模块真实解析结果（安装/运行时由 ModuleInspector 探测得到）
        val inspection = payload.inspection
        if (inspection != null) {
            sb.appendLine()
            sb.append(inspection.toPromptBlock())
        } else {
            sb.appendLine()
            sb.appendLine("待执行命令：").appendLine(payload.cmd ?: "")
        }

        // 运行时真实执行指令流（由 RuntimeCommandTracer 抓取，喂给 AI 作为第一手证据）
        val runtimeTrace = payload.runtimeTrace
            ?: AIEngineManager.lastRuntimeTrace
        android.util.Log.i("AIEngine", "payloadContext runtimeTrace 非空=${!runtimeTrace.isNullOrBlank()} 长度=${runtimeTrace?.length ?: 0}")
        if (!runtimeTrace.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine(runtimeTrace)
        }

        // 脚本原文作为「解析结果可能不完整时的参考」一并交给 AI 兜底。
        // 关键：若脚本是加密/混淆内容（base64/openssl/二进制壳），则【不发送】脚本原文，
        // 只发送上面已拼入的「运行时真实执行指令流」（strace/sh-x 抓到的解密后真实命令）。
        // 加密脚本原文对 AI 是乱码，发了反而浪费 tokens 且可能误导 AI 判断。
        val scriptText = payload.scriptText ?: AIEngineManager.lastScriptText
        if (!scriptText.isNullOrBlank() && !isEncryptedScript(scriptText)) {
            sb.appendLine()
            sb.appendLine("【脚本原文（解析结果可能有误，此原文供参考）】")
            sb.appendLine(scriptText.take(3000))
        } else if (!scriptText.isNullOrBlank() && isEncryptedScript(scriptText)) {
            sb.appendLine()
            sb.appendLine("【脚本已加密/混淆，无法读取明文，请仅依据上方「运行时真实执行指令流」分析其真实行为】")
        }

        val full = sb.toString().trimEnd()
        android.util.Log.i("AIEngine", "payloadContext 最终长度=${full.length} 尾部100=${full.takeLast(100)}")
        return full
    }
}
