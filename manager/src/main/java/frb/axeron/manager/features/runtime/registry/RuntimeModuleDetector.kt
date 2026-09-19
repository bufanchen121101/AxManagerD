package frb.axeron.manager.features.runtime.registry

import frb.axeron.api.Axeron
import frb.axeron.manager.features.runtime.RuntimeDiagnostics
import frb.axeron.manager.features.runtime.model.RuntimeManifest
import frb.axeron.manager.features.runtime.model.RuntimeModuleStatusDefaults
import frb.axeron.manager.features.runtime.model.RuntimeSensorType
import frb.axeron.manager.features.runtime.model.RuntimeTriggerType
import frb.axeron.manager.features.runtime.model.SensorDecl
import frb.axeron.manager.features.runtime.model.TriggerDecl
import frb.axeron.server.PluginInfo
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

/**
 * 运行时模块识别器。
 *
 * 识别规则（唯一依据）：
 * 1. module.prop 存在且能解析出 id；
 * 2. module.prop 里 AxmanagerdID 字段的值等于 runtime。
 *
 * 识别不依赖 PluginInfo 的固定字段，因为 AxmanagerdID 不在 ModuleProp 声明里。
 */
object RuntimeModuleDetector {

    /** module.prop 里用于识别的键名。 */
    const val KEY_MANAGER_ID = "AxmanagerdID"

    /** 识别值。 */
    const val VALUE_RUNTIME = "runtime"

    /** 版本要求键名。 */
    const val KEY_MIN_MANAGER_CODE = "minManagerCode"

    private val gson = Gson()

    /**
     * 解析 module.prop 为键值 map（忽略注释行与空行）。
     */
    fun parsePropFile(file: File): Map<String, String> {
        val text = readFileText(file.absolutePath) ?: return emptyMap()
        val map = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }

    /**
     * 读取文本文件内容。
     *
     * 优先走 AxeronFileService（binder，以 shell 身份读，能访问 shell 数据目录）；
     * binder 不可用时回退 java.io.File（仅适用于 App 有权访问的路径）。
     */
    internal fun readFileText(path: String): String? {
        // 模块文件位于 shell 私有目录，App 进程读不到，必须用 shell 权限读。
        return try {
            val r = kotlinx.coroutines.runBlocking {
                frb.axeron.api.AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", "cat '" + path + "'"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = 3_000L,
                )
            }
            if (r.exitCode != 0 || r.stdout.isEmpty()) null else r.stdout
        } catch (e: Throwable) {
            RuntimeDiagnostics.record("REG", "readFileText 失败 " + path + "：" + e)
            null
        }
    }

    /**
     * 判断单个模块目录是否为运行时模块。
     */
    fun isRuntimeModule(pluginDir: File): Boolean {
        val prop = parsePropFile(File(pluginDir, "module.prop"))
        if (prop.isEmpty()) return false
        if (prop["id"].isNullOrBlank()) return false
        return prop[KEY_MANAGER_ID].equals(VALUE_RUNTIME, ignoreCase = true)
    }

    /**
     * 读取模块声明的最低管理器版本要求，缺省为 0。
     */
    fun readMinManagerCode(pluginDir: File): Int {
        val prop = parsePropFile(File(pluginDir, "module.prop"))
        return prop[KEY_MIN_MANAGER_CODE]?.toIntOrNull() ?: 0
    }

    /**
     * 解析 runtime.json。文件不存在或格式非法时返回 null。
     */
    fun parseManifest(pluginDir: File): RuntimeManifest? {
        val file = File(pluginDir, "runtime.json")
        val text = readFileText(file.absolutePath) ?: return null
        return try {
            val root = gson.fromJson(text, JsonObject::class.java) ?: return null
            val schemaVersion = root.get("schemaVersion")?.asInt ?: 1
            val runModel = root.get("runModel")?.asString?.uppercase()
                ?: RuntimeManifest.RUN_MODEL_ONESHOT

            val sensors = mutableListOf<SensorDecl>()
            root.getAsJsonArray("sensors")?.forEach { el ->
                val obj = el.asJsonObject
                val type = obj.get("type")?.asString?.uppercase() ?: return@forEach
                if (type !in RuntimeSensorType.ALL) return@forEach
                val interval = obj.get("intervalMs")?.asLong ?: 0L
                @Suppress("UNCHECKED_CAST")
                val params = if (obj.has("params") && obj.get("params").isJsonObject) {
                    gson.fromJson(obj.get("params"), Map::class.java) as Map<String, Any?>
                } else emptyMap()
                sensors.add(SensorDecl(type = type, intervalMs = interval, params = params))
            }

            val triggers = mutableListOf<TriggerDecl>()
            root.getAsJsonArray("triggers")?.forEach { el ->
                val obj = el.asJsonObject
                val type = obj.get("type")?.asString?.uppercase() ?: return@forEach
                if (type !in RuntimeTriggerType.IMPLEMENTED && type !in RuntimeTriggerType.DECLARED_ONLY) {
                    return@forEach
                }
                triggers.add(
                    TriggerDecl(
                        type = type,
                        intervalMs = obj.get("intervalMs")?.asLong ?: 0L,
                        sensor = obj.get("sensor")?.asString?.uppercase() ?: "",
                        field = obj.get("field")?.asString ?: "",
                        op = normalizeOp(obj.get("op")?.asString ?: ""),
                        value = obj.get("value")?.asDouble ?: 0.0,
                        cooldownMs = obj.get("cooldownMs")?.asLong ?: 0L,
                        event = obj.get("event")?.asString ?: "",
                        expr = obj.get("expr")?.asString ?: "",
                    )
                )
            }

            val permissions = mutableListOf<String>()
            root.getAsJsonArray("permissions")?.forEach { el ->
                el.asString?.let { permissions.add(it) }
            }

            RuntimeManifest(
                schemaVersion = schemaVersion,
                runModel = runModel,
                sensors = sensors,
                triggers = triggers,
                permissions = permissions,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 归一化比较运算符：允许符号写法。 */
    private fun normalizeOp(raw: String): String = when (raw.trim()) {
        ">", "gt", "GT" -> "gt"
        "<", "lt", "LT" -> "lt"
        "=", "==", "eq", "EQ" -> "eq"
        else -> raw.trim()
    }
    /**
     * 路径探测缓存（同一轮扫描内避免重复起 shell 进程）。
     */
    private val pathProbeCache = HashMap<String, Boolean>()

    /**
     * 用 shell 权限探测路径类型（不依赖 App 进程的文件权限，也不依赖 AxeronFileService）。
     *
     * 背景：模块目录位于 /data/user_de/0/com.android.shell/axeron/runtime_plugins 下，
     * 是 com.android.shell 的私有目录。App 进程（u0_a413）连 stat 都做不到，
     * File.exists()/isDirectory() 恒 false，AxeronFileService 也不一定可用。
     * 本项目的既有做法（见 EnablePluginScreen / ExecutePluginAction）是用
     * AxeronPluginService 以 shell 身份跑 test 命令——已验证可靠。
     *
     * @param testFlag shell test 标志：-f（普通文件）/ -d（目录）/ -x（可执行）
     */
    private fun probePath(path: String, testFlag: String): Boolean {
        val key = testFlag + "|" + path
        synchronized(pathProbeCache) { pathProbeCache[key]?.let { return it } }
        val result = try {
            val r = kotlinx.coroutines.runBlocking {
                frb.axeron.api.AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", "test " + testFlag + " '" + path + "' && echo YES"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = 3_000L,
                )
            }
            r.stdout.contains("YES")
        } catch (e: Throwable) {
            RuntimeDiagnostics.record("REG", "probePath 失败 [" + testFlag + " " + path + "]：" + e)
            false
        }
        synchronized(pathProbeCache) { pathProbeCache[key] = result }
        RuntimeDiagnostics.record("REG", "probePath " + testFlag + " " + path + " -> " + result)
        return result
    }

    /** 清空探测缓存（每轮全量扫描前调用）。 */
    internal fun clearProbeCache() {
        synchronized(pathProbeCache) { pathProbeCache.clear() }
    }

    /**
     * 目录是否存在（shell 权限 test -d）。
     */
    internal fun dirExists(path: String): Boolean = probePath(path, "-d")

    /**
     * 文件是否存在（shell 权限 test -f）。
     *
     * 模块目录位于 shell 数据目录下，App 进程 File.exists() 恒 false，
     * 所有「必需文件检查」都必须走本方法，否则会误报「缺少 entry.sh」。
     */
    internal fun fileExists(path: String): Boolean = probePath(path, "-f")

    /** 判断 dir 目录下的子文件是否存在。 */
    internal fun fileExists(dir: File, name: String): Boolean =
        fileExists(File(dir, name).absolutePath)

    // ------------------------------------------------------------------
    // 标记文件判定
    //
    // 语义与 Axeron 服务端一致（api/server-shared/.../Service.kt:404-412）：
    //   remove  -> 已卸载
    //   disable -> 已禁用（enabled = disable 文件不存在）
    //   webroot/index.html -> 有 WebUI
    //
    // 运行时模块走的是「纯文件扫描」路径（不经服务端的 getPluginByDir），
    // 因此这三个判定必须在这里自己实现，否则会出现
    // 「卸载后卡片复活」「禁用后自动重启」「WebUI 入口不显示」。
    // ------------------------------------------------------------------

    /** 目录内是否存在指定的标记文件。 */
    private fun markerExists(dir: File, name: String): Boolean =
        fileExists(File(dir, name).absolutePath)

    /** 模块是否已被标记卸载（目录内存在 remove 文件）。 */
    fun isRemoved(dir: File): Boolean = markerExists(dir, "remove")

    /** 模块是否已被标记禁用（目录内存在 disable 文件）。 */
    fun isDisabled(dir: File): Boolean = markerExists(dir, "disable")

    /**
     * 模块是否声明了 WebUI。
     *
     * 与服务端判定保持一致：需要 webroot 目录下存在 index.html。
     */
    fun hasWebUi(dir: File): Boolean =
        fileExists(File(File(dir, "webroot"), "index.html").absolutePath)

    /**
     * 模块是否声明了动作脚本（目录内存在 action.sh）。
     *
     * 与服务端判定保持一致（Service.kt: action = "action.sh" in dirFiles）。
     * 有 action.sh 才在卡片上显示「运行」入口。
     */
    fun hasAction(dir: File): Boolean = markerExists(dir, "action.sh")

    /**
     * 读取模块声明的存活探测间隔（毫秒）。
     *
     * 键名 aliveCheckIntervalMs，写在 module.prop 里；未声明或非法时返回 null，
     * 由调用方回落到 [RuntimeModuleStatusDefaults.ALIVE_CHECK_INTERVAL_MS]。
     *
     * 结果会按 [RuntimeModuleStatusDefaults.MIN_ALIVE_CHECK_INTERVAL_MS] /
     * [RuntimeModuleStatusDefaults.MAX_ALIVE_CHECK_INTERVAL_MS] 夹紧，
     * 防止模块声明过小（打爆 CPU）或过大（超出 phantom 限制导致漏检）。
     */
    fun readAliveCheckIntervalMs(dir: File): Long? {
        val prop = parsePropFile(File(dir, "module.prop"))
        val raw = prop[RuntimeModuleStatusDefaults.KEY_ALIVE_CHECK_INTERVAL] ?: return null
        val value = raw.trim().toLongOrNull() ?: return null
        if (value <= 0L) return null
        return value.coerceIn(
            RuntimeModuleStatusDefaults.MIN_ALIVE_CHECK_INTERVAL_MS,
            RuntimeModuleStatusDefaults.MAX_ALIVE_CHECK_INTERVAL_MS,
        )
    }

}

/**
 * 运行时模块注册表。
 *
 * 职责：从 Axeron 的插件列表里筛出运行时模块，并提供目录解析。
 * P0 阶段只做「识别 + 扫描」，不做进程管理。
 */
object RuntimeModuleRegistry {

    /**
     * 当前管理器的版本代号。模块的 minManagerCode 不得高于此值。
     * 与 AxeronApiConstant 的版本策略保持一致，v1.2.0 提升为 8。
     */
    const val MANAGER_CODE_CURRENT = 8

    /**
     * 运行时模块的独立安装目录（相对 axeron/）：
     * 与普通插件 plugins/ 完全隔离，由 install_plugin 的第 4 参数决定。
     */
    const val RUNTIME_PLUGIN_FOLDER = "runtime_plugins"

    /**
     * 解析某个运行时模块的目录。
     *
     * 优先查独立目录 axeron/runtime_plugins/<dirId>；
     * 回退查 axeron/plugins/<dirId>（兼容旧版把运行时模块装进 plugins/ 的情况）。
     */
    fun resolvePluginDir(plugin: PluginInfo): File? {
        val dirId = plugin.dirId.ifBlank { plugin.prop.id }
        if (dirId.isBlank()) return null
        val root = Axeron.getAxeronInfo().isRoot()
        val axeronRoot = PathHelper.getWorkingPath(root, AxeronApiConstant.folder.PARENT)
        val runtimeDir = File(File(axeronRoot, RUNTIME_PLUGIN_FOLDER), dirId)
        if (RuntimeModuleDetector.dirExists(runtimeDir.absolutePath)) return runtimeDir
        val legacyParent = PathHelper.getWorkingPath(root, AxeronApiConstant.folder.PARENT_PLUGIN)
        val legacyDir = File(legacyParent, dirId)
        return if (RuntimeModuleDetector.dirExists(legacyDir.absolutePath)) legacyDir else null
    }

    /**
     * 直接扫描运行时模块独立目录（不依赖 Axeron 插件列表）。
     *
     * 这样即使模块没被 Axeron 的插件扫描收录，也仍能被识别到。
     *
     * 注意：这里刻意不调用 Axeron.getAxeronInfo() / Axeron.getPlugins() 等 binder，
     * 因为运行时模块的落盘位置固定位于 shell 数据目录（install_plugin 脚本写入
     * /data/user_de/0/com.android.shell/axeron/runtime_plugins/）。binder 服务端异常时，
     * 本方法必须仍能纯文件扫描，否则「装上了但列表不显示」。
     */
    fun scanRuntimeDirs(): List<File> {
        val axeronRoot = PathHelper.getWorkingPath(false, AxeronApiConstant.folder.PARENT)
        val dir = File(axeronRoot, RUNTIME_PLUGIN_FOLDER)
        // 关键：模块目录位于 shell 数据目录下（0711 权限），App 进程无法直接 stat。
        // 因此必须走 AxeronFileService（binder，以 shell 身份访问）来枚举。
        val names = listRuntimeDirNames(dir.absolutePath)
        RuntimeDiagnostics.record(
            "REG",
            "scanRuntimeDirs(binder) dir=${dir.absolutePath} 子目录 ${names.size} 个：${names.joinToString()}"
        )
        val rt = names.mapNotNull { name ->
            val child = File(dir, name)
            // 已打 remove 标记的模块视为已卸载，必须排除，
            // 否则卸载后紧接着的重扫会把卡片重新加回来（卸载看似无效）。
            if (RuntimeModuleDetector.isRemoved(child)) {
                RuntimeDiagnostics.record("REG", "跳过 ${name}：已标记 remove（视为已卸载）")
                return@mapNotNull null
            }
            val ok = RuntimeModuleDetector.isRuntimeModule(child)
            RuntimeDiagnostics.record(
                "REG",
                "判定 ${name}: isRuntimeModule=$ok prop=${RuntimeModuleDetector.parsePropFile(File(child, "module.prop"))}"
            )
            if (ok) child else null
        }
        return rt
    }

    /**
     * 通过 AxeronFileService（binder）列出目录下的一级子目录名。
     *
     * 失败时返回空列表，绝不抛出。
     */
    private fun listRuntimeDirNames(dirPath: String): List<String> {
        return try {
            val r = kotlinx.coroutines.runBlocking {
                frb.axeron.api.AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", "ls -1 '" + dirPath + "'"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = 3_000L,
                )
            }
            if (r.exitCode != 0) {
                RuntimeDiagnostics.record("REG", "ls 失败 " + dirPath + "：" + r.stderr.trim())
                return emptyList()
            }
            r.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { name -> RuntimeModuleDetector.dirExists(dirPath + "/" + name) }
                .toList()
        } catch (e: Throwable) {
            RuntimeDiagnostics.record("REG", "ls 异常 " + dirPath + "：" + e)
            emptyList()
        }
    }


    /**
     * 从插件列表中筛出运行时模块，并返回目录映射。
     * 返回 Pair(PluginInfo, File)。
     */
    fun pickRuntimeModules(plugins: List<PluginInfo>): List<Pair<PluginInfo, File>> {
        val result = ArrayList<Pair<PluginInfo, File>>()
        for (p in plugins) {
            if (p.remove) continue
            val dir = resolvePluginDir(p) ?: continue
            if (RuntimeModuleDetector.isRuntimeModule(dir)) {
                result.add(p to dir)
            }
        }
        return result
    }
}