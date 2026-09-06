package frb.axeron.manager.ai

import android.content.Context
import android.util.Log
import frb.axeron.server.ModuleProp
import frb.axeron.server.PluginInfo
import frb.axeron.api.AxeronPluginService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * v1.1.0 卸载回滚（AI 驱动恢复指令生成）。
 *
 * 职责：
 * 1. 把拦截器捕获的真实执行指令「持久化」成带元信息头的 JSON Lines 日志文件，
 *    存放到 App 私有目录 filesDir/rollback/{name}.axd_log（不随模块卸载而丢失）。
 * 2. 卸载时读取日志 + module.prop 元信息，构造 AI Prompt。
 * 3. 调用云端 AI（复用 AIChatService）生成恢复脚本。
 * 4. 高危指令过滤（复用 RuleEngine，HIGH 风险脚本整条/整块剔除，绝不执行）。
 * 5. 恢复脚本执行成功后销毁日志（调用 destroyByDirId）。
 *
 * 流程（用户确认）：先卸载 → 再执行恢复脚本。
 */
object UninstallRollback {
    private const val TAG = "UninstallRollback"

    /** 日志/缓存根目录：App 私有目录，避免随模块卸载丢失 */
    private fun rootDir(context: Context): File =
        File(context.filesDir, "rollback").apply { if (!exists()) mkdirs() }

    /**
     * 日志文件名：读取 module.prop 的 name，替换特殊字符（/ : \ 和空白）为下划线；
     * 若 name 为空则回退为 dirId。
     */
    fun logFileName(prop: ModuleProp, dirId: String): String {
        val raw = prop.name.ifBlank { dirId }
        val safe = raw.replace(Regex("""[/:\\\s]+"""), "_")
        return "${safe.ifBlank { dirId }}.axd_log"
    }

    /** 日志文件：统一放在 rootDir/{dirId}/ 目录下，便于按 dirId 整体清理。 */
    fun logFile(context: Context, prop: ModuleProp, dirId: String): File =
        File(cacheDirFor(context, dirId), logFileName(prop, dirId))

    fun cacheDirFor(context: Context, dirId: String): File {
        val d = File(rootDir(context), dirId)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * 把捕获到的指令流落盘为 JSON Lines（首行为元信息 JSON）。
     * 线程安全：整块先写临时文件再原子 rename。
     */
    suspend fun persistLog(context: Context, plugin: PluginInfo, commands: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (commands.isEmpty()) return@withContext false
                val prop = plugin.prop
                val meta = JSONObject()
                    .put("meta", JSONObject()
                        .put("id", prop.id)
                        .put("name", prop.name)
                        .put("version", prop.version)
                        .put("author", prop.author))
                val sb = StringBuilder()
                sb.append(meta.toString()).append('\n')
                val baseTs = System.currentTimeMillis()
                commands.forEachIndexed { i, cmd ->
                    val line = JSONObject()
                        .put("type", "execve")
                        .put("ts", baseTs + i)
                        .put("cmd", cmd)
                    sb.append(line.toString()).append('\n')
                }
                val target = logFile(context, prop, plugin.dirId)
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeText(sb.toString())
                if (!tmp.renameTo(target)) {
                    target.writeText(sb.toString())
                    tmp.delete()
                }
                Log.i(TAG, "persistLog 写入 ${commands.size} 条 -> ${target.absolutePath}")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "persistLog 失败", t)
                false
            }
        }

    /** 读取日志内容（返回纯文本，供 prompt 拼接）。 */
    suspend fun readLog(context: Context, prop: ModuleProp, dirId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = logFile(context, prop, dirId)
                if (f.exists()) f.readText() else null
            }.getOrNull()
        }

    /**
     * 解析 axd_log（JSON Lines）里的「cmd」字段，提取拦截到的真实指令原文。
     * 首行是 meta JSON，其余每行是一条 execve/指令记录。
     * 返回按出现顺序的指令列表（未经任何额外过滤，与落盘时 snapshot() 一致）。
     */
    fun parseCapturedCommands(logContent: String): List<String> {
        val result = mutableListOf<String>()
        logContent.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            runCatching {
                val obj = JSONObject(t)
                val cmd = obj.optString("cmd", "").trim()
                if (cmd.isNotEmpty()) result.add(cmd)
            }
        }
        return result
    }

    /** 生成「展示用」的完整拦截指令清单文本（不做白名单过滤，保留所有拦截到的指令），
     * 供恢复界面展示给用户核对拦截是否正确。
     */
    fun buildCapturedCommandList(logContent: String): String {
        val commands = parseCapturedCommands(logContent)
        if (commands.isEmpty()) return "（未捕获到任何指令）"
        val sb = StringBuilder()
        sb.appendLine("【拦截到的原始指令（共 ${commands.size} 条）】")
        commands.forEachIndexed { i, cmd ->
            sb.appendLine("${i + 1}. $cmd")
        }
        return sb.toString()
    }

    fun buildCleanCommandList(logContent: String): String {
        val commands = parseCapturedCommands(logContent)
        if (commands.isEmpty()) return "（未捕获到任何指令）"

        // 可逆核心操作的关键词（白名单式提取）
        val reversiblePrefixes = listOf(
            "pm ", "cmd ", "settings ", "setprop ", "am ", "axeron-dpm",
            "mount ", "chmod ", "chown ", "resetprop", "wm ", "content "
        )
        val clean = commands.filter { cmd ->
            val c = cmd.trim()
            reversiblePrefixes.any { c.startsWith(it) }
        }
        if (clean.isEmpty()) return "（拦截到的指令中无可逆的核心系统操作）"

        val sb = StringBuilder()
        sb.appendLine("【拦截到的可逆核心指令（共 ${clean.size} 条，请逐条逆向）】")
        clean.forEachIndexed { i, cmd ->
            sb.appendLine("${i + 1}. $cmd")
        }
        return sb.toString()
    }

    /** 按 dirId 清理该模块产生的日志 + 缓存（恢复执行成功后调用，无需完整 prop）。 */
    suspend fun destroyByDirId(context: Context, dirId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = cacheDirFor(context, dirId)
                if (dir.exists()) dir.deleteRecursively()
                true
            }.getOrDefault(false)
        }

    /** 系统提示词（附件 2.3 精简版）。 */
    fun buildSystemPrompt(languageInstruction: String = ""): String =
        "你是一位严谨的 Android 系统运维专家，只根据给定的操作日志逐条生成对应的逆向恢复命令。" +
            "你只逆向「可以可靠还原」的操作；凡是无法可靠还原、或不确定原本状态的，一律跳过并标注「无法恢复」，绝不凭猜测编造。" +
            if (languageInstruction.isNotBlank()) " $languageInstruction" else ""

    /** 用户消息（附件 2.3 模板）。 */
    fun buildUserPrompt(prop: ModuleProp, logContent: String): String =
        buildString {
            appendLine("以下是一个名为【${prop.name}】的模块执行期间拦截到的系统操作指令清单。")
            appendLine("请生成一个 Shell 脚本，逐条撤销其中「可以可靠逆向」的操作。")
            appendLine()
            appendLine("【总原则】")
            appendLine("1. 只对「可逆的核心系统操作」生成逆向命令；拿不准、无法确定原本状态的，一律跳过并在末尾用 echo 标注「无法恢复」，禁止硬编。")
            appendLine("2. 下列操作视为【不可逆，直接忽略，不要生成任何逆向命令】：")
            appendLine("   - 删除文件/目录（rm、rmdir、rm -rf）—— 删掉的东西无法找回，不用管。")
            appendLine("   - 覆盖/移动/改名文件（cp 覆盖、mv、dd 写文件）—— 原内容已丢失，不用管。")
            appendLine("   - 创建目录/复制部署/解包（mkdir、cp、tar、unzip、base64、chmod 部署）—— 这些是模块安装脚手架，无需恢复。")
            appendLine("3. 相同的操作只生成一次；重复行跳过。")
            appendLine()
            appendLine("【逆向规则（本设备实测过的正确语法，必须严格遵守）】")
            appendLine("1. pm disable-user --user 0 <包名>   →  逆向为：pm enable <包名>")
            appendLine("   注意：pm enable 不支持 --user 参数，绝不要写 pm enable --user 0 或 pm enable-user。")
            appendLine("2. pm disable <包名>（不带 --user）  →  逆向为：pm enable <包名>")
            appendLine("3. settings put <namespace> <key> <value>  →  逆向为：settings delete <namespace> <key>")
            appendLine("   注意：无法得知该 key 的原始值，禁止瞎猜一个值写回去（例如统一写 1 是错的）。")
            appendLine("   一律用 settings delete 删除该 key，让系统回落到厂商默认值。")
            appendLine("4. setprop <key> <value>  →  若该 key 是系统原有属性且能推断默认值则 setprop 还原；否则跳过并在末尾标注「无法恢复」。")
            appendLine("5. am force-stop <包名...>  →  无需逆向（进程会按需重启），在末尾打印一行警告即可。")
            appendLine("6. am start / am startservice  →  逆向为 am force-stop 对应包名（仅当包名可确定，否则跳过）。")
            appendLine("7. mount ... / 挂载分区  →  若能确定原始挂载点可 umount 还原则生成；否则跳过标注「无法恢复」。")
            appendLine("8. 写内核节点 echo/printf ... > /sys/...（或 /proc、/dev）→ 无法获知原值，跳过并标注「无法恢复」。")
            appendLine()
            appendLine("【axeron-dpm 命令（本设备专用 Device Owner 命令，逆向映射如下，逐条对照）】")
            appendLine("1. axeron-dpm hide <pkg>          →  逆向为：axeron-dpm unhide <pkg>")
            appendLine("2. axeron-dpm unhide <pkg>        →  逆向为：axeron-dpm hide <pkg>")
            appendLine("3. axeron-dpm suspend <pkg...>    →  逆向为：axeron-dpm unsuspend <pkg...>")
            appendLine("4. axeron-dpm unsuspend <pkg...>  →  逆向为：axeron-dpm suspend <pkg...>")
            appendLine("5. axeron-dpm set-global <key> <val>  →  若无法获知原值，视为「无法恢复」并跳过（不要瞎猜值写回）。")
            appendLine("6. axeron-dpm set-secure <key> <val>  →  同上，「无法恢复」跳过。")
            appendLine("7. axeron-dpm set-anim <scale>    →  无法获知原值，「无法恢复」跳过。")
            appendLine("8. axeron-dpm cleardata <pkg>     →  数据已清除，不可逆，直接忽略不逆向。")
            appendLine("9. axeron-dpm grant/deny <pkg> <perm>  →  逆向为相反的 deny/grant（若能确定则生成，否则跳过）。")
            appendLine("10. axeron-dpm block-uninstall <pkg>   →  逆向为：axeron-dpm unblock-uninstall <pkg>")
            appendLine("11. axeron-dpm unblock-uninstall <pkg> →  逆向为：axeron-dpm block-uninstall <pkg>")
            appendLine("12. axeron-dpm force-stop <pkg> / reboot / locknow / uninstall  →  不可逆或无需恢复，跳过并标注。")
            appendLine()
            appendLine("【脚本硬性要求 —— 尽量简化为一条条独立的简单指令】")
            appendLine("1. 输出必须是【一行一条命令】的扁平结构，严禁使用数组、for/while 循环、case、函数定义、变量拼接。")
            appendLine("2. 每条逆向命令单独一行、直接写死包名/键名（不要用 \${PKGS[@]} 之类数组展开）。")
            appendLine("3. 不要为每条命令包 if 判断；命令失败了就让它失败，在命令后直接跟下一行即可（不要 set -e）。")
            appendLine("4. 输出【仅含脚本内容】，不含任何解释、不含 Markdown 代码块围栏（``` 等一律不要）。")
            appendLine("5. 无法恢复的操作在脚本末尾用 echo 逐条打印警告（一行一条）。")
            appendLine("6. 绝对禁止包含破坏性命令（rm -rf /、dd if=/dev/zero、mkfs、setenforce 0、写 /dev/block 等）。")
            appendLine("7. 严禁使用任何删除类命令去「逆向」—— 逆向阶段不是删除阶段。")
            appendLine()
            appendLine("拦截到的指令清单（供逐条逆向）：")
            append(buildCleanCommandList(logContent))
        }

    /** 调用云端 AI 生成恢复脚本。
     * 复用 AIChatService.chatOnce；未配置云端返回 null。
     */
    suspend fun generateRestoreScript(context: android.content.Context, prop: ModuleProp, logContent: String): String? =
        withContext(Dispatchers.IO) {
            if (!AIChatService.isCloudConfigured()) {
                Log.w(TAG, "未配置云端 AI，无法生成恢复脚本")
                return@withContext null
            }
            val langInstr = frb.axeron.manager.ui.util.LocaleHelper.languageInstruction(context)
            val system = buildSystemPrompt(langInstr)
            val user = buildUserPrompt(prop, logContent)
            withTimeoutOrNull(120_000L) {
                AIChatService.chatOnce(system = system, prompt = user)
            }
        }

    data class ValidationResult(
        val allowed: Boolean,
        val blockedLines: List<String>,
        val script: String,
        /** 被拦截的危险行原文（供「不拦截」入口按需放回执行） */
        val dangerousLines: List<String> = emptyList(),
        /** 危险行在原脚本中的行号（1 起，用于定位） */
        val dangerousLineNumbers: List<Int> = emptyList(),
    )

    /**
     * 检测 AI 生成脚本中的常见语法/语义硬伤（这些 AI 反复犯错，导致脚本无法执行）。
     * 返回该行的问题描述；无问题返回 null。
     */
    private fun detectSyntaxError(line: String): String? {
        // 1. 非法数组展开：引号开头但含未闭合的花括号/圆括号，如 "(PKGS[@]}"、"(PKGS[@]}"
        if (line.contains("(PKGS") || line.contains("(PKGS[")) {
            return "数组展开语法错误：应为 \${PKGS[@]} 而非 (PKGS[@]}"
        }
        // 2. pm enable 非法参数：pm enable 不支持 --user
        if (Regex("""pm\s+enable\s+--user""").containsMatchIn(line)) {
            return "pm enable 不支持 --user 参数，应为 pm enable <包名>"
        }
        // 3. pm enable-user 是不存在的命令
        if (Regex("""pm\s+enable-user""").containsMatchIn(line)) {
            return "不存在 pm enable-user 命令，应为 pm enable <包名>"
        }
        // 4. pm enable 变量未加 $：如 "pkg" 或 "(pkg}" 或 "0\"pkg\""
        if (Regex("""pm\s+enable[^\n]*["'(](pkg|PKGS)""").containsMatchIn(line) ||
            Regex("""pm\s+enable[^\n]*"(pkg|PKGS)""").containsMatchIn(line)) {
            return "变量引用错误：pm enable 的包名变量应为 \$pkg，而非 pkg"
        }
        // 5. settings put 缺值：settings put global <key> 后面没有 value（AI 漏写恢复值）
        //    正确的 settings put 是 3 个参数：settings put <namespace> <key> <value>
        //    若只有 2 个参数（namespace + key），说明漏了 value。
        if (Regex("""^settings\s+put\s+(global|system|secure)\s+\S+\s*$""").matches(line.trim())) {
            return "settings put 缺少值：无法确定原值时应改用 settings delete <namespace> <key>"
        }
        // 6. 硬编码恢复值为 1 的嫌疑（settings put ... = 1，AI 瞎猜云控开关默认值）
        if (Regex("""^settings\s+put\s+(global|system|secure)\s+\S+\s+1\s*$""").matches(line.trim())) {
            return "设置值硬编码为 1 可疑：云控开关原值未知，应改用 settings delete <namespace> <key> 回默认"
        }
        return null
    }

    /**
     * 高危指令过滤：把脚本按行拆分，逐行用 RuleEngine.analyze 判定；
     * HIGH 风险的行剔除（不执行），MEDIUM/LOW 保留但可由上层二次确认。
     * 额外做 AI 常见语法硬伤检测（detectSyntaxError），命中的行也剔除并提示。
     * 返回过滤后的脚本 + 被剔除的行，供 UI 展示。
     */
    suspend fun filterDangerous(script: String): ValidationResult = withContext(Dispatchers.IO) {
        val keep = StringBuilder()
        val blocked = mutableListOf<String>()
        val dangerous = mutableListOf<String>()
        val dangerousNums = mutableListOf<Int>()
        script.split('\n').forEachIndexed { idx, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) {
                keep.append(rawLine).append('\n')
                return@forEachIndexed
            }
            val syntaxProblem = detectSyntaxError(line)
            if (syntaxProblem != null) {
                blocked.add("[语法] $line  # $syntaxProblem")
                dangerous.add(rawLine)
                dangerousNums.add(idx + 1)
                return@forEachIndexed
            }
            val result = frb.axeron.api.ai.RuleEngine.analyze(line)
            if (result.risk == frb.axeron.api.ai.CommandAnalyzer.AnalyzeResult.Risk.HIGH) {
                blocked.add(line)
                dangerous.add(rawLine)
                dangerousNums.add(idx + 1)
            } else {
                keep.append(rawLine).append('\n')
            }
        }
        ValidationResult(
            allowed = true,
            blockedLines = blocked,
            script = keep.toString(),
            dangerousLines = dangerous,
            dangerousLineNumbers = dangerousNums,
        )
    }

    /**
     * 执行恢复脚本（复用内置终端 execWithIO）。
     * 每行加了 60s 整体超时；执行结果返回 exitCode 与输出。
     */
    suspend fun executeRestoreScript(script: String): frb.axeron.api.AxeronPluginService.ResultExec? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(60_000L) {
                AxeronPluginService.execWithIO(script, hideStderr = false)
            }
        }
}
