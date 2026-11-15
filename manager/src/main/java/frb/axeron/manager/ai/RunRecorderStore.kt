package frb.axeron.manager.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 运行记录仪数据层（只读）。
 *
 * ## 数据来源
 * 与「卸载回滚」共用同一份落盘日志，路径规则完全由 [UninstallRollback] 决定：
 * `filesDir/rollback/{dirId}/{safe(模块名)}.axd_log`
 * - 首行是 meta JSON：`{"meta":{"id","name","version","author"}}`
 * - 其后每行是一条被捕获的指令：`{"type":"execve","ts":<ms>,"cmd":"<指令>"}`
 *
 * ## 为什么单独一个对象
 * 展示功能不该反向污染回滚链路：[UninstallRollback] 负责「写 + 清理」，
 * 本对象只负责「枚举 + 解析」，**不写、不改、不删**任何文件。
 *
 * ## 关键：分组标题取 meta 的 name
 * [UninstallRollback.logFileName] 已把 `/ : \ 空白` 替换成下划线，
 * 文件名无法反推出模块原名，所以模块名必须从日志首行的 meta 里读。
 */
object RunRecorderStore {
    private const val TAG = "RunRecorderStore"

    /** 日志后缀，与 [UninstallRollback.logFileName] 保持一致。 */
    private const val LOG_SUFFIX = ".axd_log"

    /**
     * 一个模块的一次抓取记录（对应磁盘上的一个 .axd_log 文件）。
     *
     * @param dirId 模块目录名（= 模块 id）
     * @param moduleName 日志 meta 里的模块原名（可能为空）
     * @param moduleId 日志 meta 里的模块 id
     * @param logFile 日志文件本体
     * @param commandCount 捕获到的指令条数（只统计带 cmd 字段的记录行）
     * @param updatedAt 日志最后写入时间（= 落盘时刻）
     */
    data class Record(
        val dirId: String,
        val moduleName: String,
        val moduleId: String,
        val version: String,
        val author: String,
        val logFile: File,
        val commandCount: Int,
        val updatedAt: Long,
    ) {
        /** 展示用名称：优先 meta 的 name，其次 meta 的 id，最后回退目录名。 */
        val displayName: String
            get() = moduleName.ifBlank { moduleId.ifBlank { dirId } }
    }

    /**
     * 枚举全部运行记录（按落盘时间倒序）。
     *
     * 只遍历 `filesDir/rollback/{dirId}/` 一层，不递归；
     * 目录或文件读失败一律跳过，绝不抛异常打断整页展示。
     */
    suspend fun listRecords(context: Context): List<Record> = withContext(Dispatchers.IO) {
        val out = ArrayList<Record>()
        try {
            val root = File(context.filesDir, "rollback")
            if (!root.isDirectory) return@withContext emptyList<Record>()
            val dirs = root.listFiles() ?: return@withContext emptyList<Record>()
            for (dir in dirs) {
                if (!dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    if (!f.isFile) continue
                    if (!f.name.endsWith(LOG_SUFFIX)) continue
                    val rec = parseRecord(f, dir.name) ?: continue
                    out.add(rec)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "listRecords 失败", t)
        }
        out.sortByDescending { it.updatedAt }
        out
    }

    /**
     * 按「模块名」聚合后的一组运行记录。
     *
     * 正常情况下一个模块只有一份日志（每次抓取覆盖同名文件）；
     * 模块改过名时同一 dirId 下会留下多份，这里按模块名合并成一组展示，
     * 保证列表口径是「模块名」而不是「文件」。
     */
    data class Group(
        val name: String,
        val records: List<Record>,
    ) {
        /** 组内全部日志的指令条数合计。 */
        val commandCount: Int
            get() = records.sumOf { it.commandCount }

        /** 组内最近一次落盘时间。 */
        val updatedAt: Long
            get() = records.maxOfOrNull { it.updatedAt } ?: 0L
    }

    /**
     * 枚举全部运行记录并按**模块名**分组（组之间按最近一次落盘时间倒序）。
     */
    suspend fun listGroups(context: Context): List<Group> =
        listRecords(context)
            .groupBy { it.displayName }
            .map { (name, list) -> Group(name = name, records = list.sortedByDescending { it.updatedAt }) }
            .sortedByDescending { it.updatedAt }

    /**
     * 读取一组记录里全部日志文件的指令（同一模块名多份日志时按落盘时间顺序拼接）。
     */
    suspend fun readGroupCommands(group: Group): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        for (record in group.records.sortedBy { it.updatedAt }) {
            out.addAll(readCommands(record.logFile))
        }
        out
    }

    /**
     * 读取某个日志文件捕获到的全部指令（原样、按出现顺序、不做白名单过滤）。
     * 解析逻辑直接复用 [UninstallRollback.parseCapturedCommands]，保证与卸载回滚界面看到的一致。
     */
    suspend fun readCommands(logFile: File): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!logFile.isFile) return@runCatching emptyList<String>()
            UninstallRollback.parseCapturedCommands(logFile.readText())
        }.getOrDefault(emptyList())
    }

    /** 解析单个日志文件：首行 meta + 统计指令行数。 */
    private fun parseRecord(file: File, dirId: String): Record? = runCatching {
        var name = ""
        var id = ""
        var version = ""
        var author = ""
        var count = 0
        file.bufferedReader().use { br ->
            val head = br.readLine()
            if (head != null) {
                val meta = runCatching { JSONObject(head).optJSONObject("meta") }.getOrNull()
                if (meta != null) {
                    name = meta.optString("name", "")
                    id = meta.optString("id", "")
                    version = meta.optString("version", "")
                    author = meta.optString("author", "")
                }
            }
            while (true) {
                val line = br.readLine() ?: break
                val t = line.trim()
                if (t.isEmpty()) continue
                // 只统计真正带 cmd 字段的记录行，避免异常行把条数算多
                if (t.contains("\"cmd\"")) count++
            }
        }
        Record(
            dirId = dirId,
            moduleName = name,
            moduleId = id,
            version = version,
            author = author,
            logFile = file,
            commandCount = count,
            updatedAt = file.lastModified(),
        )
    }.getOrNull()
}
