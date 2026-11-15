package frb.axeron.manager.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 运行历史 · 冲突分析（只读）。
 *
 * ## 解决的问题
 * 多个模块常常改同一个目标：`settings put secure user_setup_complete 0` 与 `... 1`、
 * 两个模块往同一个文件写不同内容。谁最后写谁生效，用户看不到"互相覆盖"这件事。
 * 本对象把「运行记录仪」里落盘的每一条真实指令按**目标**归并，找出被 2 个以上模块写过的目标。
 *
 * ## 只做两类冲突
 * 1. **设置项**：`settings put/delete <ns> <key>`，按 `ns:key` 归并；
 *    对用户只显示**键名**（值放在条目里，详情页才展开）。
 * 2. **文件**：`> / >> / tee / dd / sed -i / touch` 这类写文件，按**路径**归并；
 *    对用户只显示**文件名**（完整路径放在详情页）。
 *
 * ## 只读
 * 不写、不改、不删任何日志；与 [RunRecorderStore] 同一口径（日志由 [UninstallRollback] 负责）。
 */
object RunConflictAnalyzer {

    /** 冲突类别。 */
    enum class Type {
        /** 系统设置项（settings put/delete）。 */
        SETTINGS,

        /** 文件写入。 */
        FILE,
    }

    /** 一条「某模块写过这个目标」的记录。 */
    data class Entry(
        /** 模块展示名（来自日志 meta）。 */
        val module: String,
        /** 指令原文。 */
        val command: String,
        /** 写入值（设置值 / 文件内容片段），可能为 null。 */
        val value: String?,
    )

    /**
     * 一处冲突。
     *
     * @param key 归一化后的目标键（`secure:user_setup_complete` / 绝对路径）
     * @param display 列表里显示的名字（键名 / 文件名，不含值）
     * @param entries 各模块对它的写入记录（顺序 = 日志落盘顺序）
     */
    data class Conflict(
        val type: Type,
        val key: String,
        val display: String,
        val entries: List<Entry>,
    ) {
        /** 涉及到的模块名（去重，保持出现顺序）。 */
        val modules: List<String>
            get() = entries.map { it.module }.distinct()
    }

    /**
     * 扫描全部运行记录，返回冲突列表（设置项在前、文件在后；同类按名字排序）。
     *
     * 任何一条日志读失败都只影响它自己，不打断整轮分析。
     */
    suspend fun analyze(context: Context): List<Conflict> {
        val groups = runCatching { RunRecorderStore.listGroups(context) }.getOrDefault(emptyList())
        return analyzeGroups(groups)
    }

    /**
     * 用已经加载好的分组做分析 —— 界面手里已有一份，避免再遍历一次目录。
     */
    suspend fun analyzeGroups(groups: List<RunRecorderStore.Group>): List<Conflict> =
        withContext(Dispatchers.IO) {
            val settings = LinkedHashMap<String, MutableList<Entry>>()
            val files = LinkedHashMap<String, MutableList<Entry>>()

            for (group in groups) {
            // 同一模块名可能有多份日志（改过名），按落盘时间顺序拼接，等于真实执行顺序。
            for (record in group.records.sortedBy { it.updatedAt }) {
                val commands = runCatching { RunRecorderStore.readCommands(record.logFile) }
                    .getOrDefault(emptyList())
                for (command in commands) {
                    val parsed = runCatching { CommandInspector.parse(command) }.getOrNull() ?: continue
                    when (parsed.kind) {
                        CommandInspector.Kind.SETTINGS -> {
                            // get / list 只是读取，不构成写入冲突
                            if (parsed.op != "put" && parsed.op != "delete") continue
                            val name = parsed.namespace
                            val target = parsed.target
                            if (name.isNullOrBlank() || target.isBlank()) continue
                            settings.getOrPut("$name:$target") { ArrayList() }
                                .add(Entry(group.name, command, parsed.value))
                        }

                        CommandInspector.Kind.FILE_WRITE,
                        CommandInspector.Kind.FILE_APPEND -> {
                            val path = parsed.path
                            if (path.isNullOrBlank()) continue
                            files.getOrPut(path) { ArrayList() }
                                .add(Entry(group.name, command, parsed.value))
                        }

                        else -> Unit
                    }
                }
            }
        }

        val out = ArrayList<Conflict>()
        settings.forEach { (key, list) ->
            // 同一个模块反复写同一个键不算冲突，必须是**不同模块**
            if (list.map { it.module }.distinct().size < 2) return@forEach
            out.add(
                Conflict(
                    type = Type.SETTINGS,
                    key = key,
                    // 对用户只显示键名，不显示命名空间前缀与值
                    display = key.substringAfter(':'),
                    entries = list,
                )
            )
        }
        files.forEach { (path, list) ->
            if (list.map { it.module }.distinct().size < 2) return@forEach
            out.add(
                Conflict(
                    type = Type.FILE,
                    key = path,
                    // 对用户只显示文件名
                    display = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
                    entries = list,
                )
            )
        }
        out.sortedWith(compareBy<Conflict> { it.type.ordinal }.thenBy { it.display })
    }
}
