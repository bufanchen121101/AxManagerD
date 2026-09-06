package frb.axeron.manager.ai

import frb.axeron.api.ai.CommandAnalyzer

/**
 * 受限问答（Phase 1 规则版）。
 *
 * 底层逻辑：只回答与本模块/本软件分析相关的问题；
 * 检测到无关话题时直接回复"无法回答"，减少 tokens 消费。
 *
 * Phase 2/3 接入云端/本地 AI 后，此处的关键词规则会作为"话题守卫"
 * 保留在最外层，先把无关问题挡掉，再交给真正的 AI 回答。
 */
object RestrictedQA {

    /** 与本模块分析相关的关键词 */
    private val RELATED_KEYWORDS = listOf(
        "模块", "危险", "风险", "修改", "更改", "改什么", "做什么",
        "功能", "作用", "命令", "卸载", "恢复", "删除", "安装",
        "权限", "数据", "文件", "会不会", "是否", "安全",
    )

    /** 明确无关话题关键词（命中即拒绝） */
    private val UNRELATED_KEYWORDS = listOf(
        "天气", "新闻", "笑话", "故事", "写诗", "写代码", "编程",
        "做饭", "菜谱", "翻译", "聊天", "你是谁", "你会什么",
    )

    fun answer(question: String, result: CommandAnalyzer.AnalyzeResult?): String {
        val q = question.trim()
        if (q.isEmpty()) return "请针对本模块提问。"

        // 1. 明确无关话题 → 拒绝
        if (UNRELATED_KEYWORDS.any { q.contains(it) }) {
            return "无法回答：请仅针对本模块的功能、修改内容与风险提问。"
        }

        // 2. 完全不含相关关键词 → 拒绝（防 tokens 滥用）
        if (RELATED_KEYWORDS.none { q.contains(it) }) {
            return "无法回答：请仅针对本模块的功能、修改内容与风险提问。"
        }

        // 3. 相关话题 → 基于分析结果生成简要回答
        return buildRelatedAnswer(q, result)
    }

    private fun buildRelatedAnswer(
        q: String,
        result: CommandAnalyzer.AnalyzeResult?,
    ): String {
        if (result == null) return "当前暂无可分析的信息。"

        val matched = result.matchedRules
        if (matched.isEmpty()) {
            return "根据规则引擎分析，未发现本模块包含危险操作。"
        }

        val sb = StringBuilder()
        sb.append("本模块检测到 ${matched.size} 项风险操作：\n")
        matched.forEach { rule ->
            sb.append("• ${rule.name}（${rule.matchedText}）\n")
        }
        sb.append("\n整体风险等级：${riskName(result.risk)}。")
        return sb.toString()
    }

    private fun riskName(risk: CommandAnalyzer.AnalyzeResult.Risk): String = when (risk) {
        CommandAnalyzer.AnalyzeResult.Risk.SAFE -> "安全"
        CommandAnalyzer.AnalyzeResult.Risk.LOW -> "低"
        CommandAnalyzer.AnalyzeResult.Risk.MEDIUM -> "中"
        CommandAnalyzer.AnalyzeResult.Risk.HIGH -> "高"
    }
}
