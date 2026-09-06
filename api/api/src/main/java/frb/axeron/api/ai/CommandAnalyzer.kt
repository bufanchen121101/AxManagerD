package frb.axeron.api.ai

/**
 * 命令分析接口（方案 A 核心回调）。
 *
 * api 库层无法直接弹 UI，因此通过这个接口把"执行前分析"的能力交给
 * manager 层实现。execWithIO / flashPlugin 在执行命令前会调用
 * [analyze]，拿到 [AnalyzeResult] 后决定放行还是拦截。
 */
interface CommandAnalyzer {
    suspend fun analyze(cmd: String, ctx: CommandContext): AnalyzeResult?

    data class CommandContext(
        val source: Source,
        val pluginDirId: String? = null,
        val pluginName: String? = null,
    ) {
        enum class Source {
            INSTALL,
            ACTION,
            INTERNAL,
        }
    }

    data class AnalyzeResult(
        val allow: Boolean,
        val risk: Risk,
        val matchedRules: List<MatchedRule> = emptyList(),
        val summary: String = "",
    ) {
        enum class Risk {
            SAFE,
            LOW,
            MEDIUM,
            HIGH,
        }

        data class MatchedRule(
            val name: String,
            val pattern: String,
            val matchedText: String,
            val risk: Risk,
        )
    }
}
