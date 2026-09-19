package frb.axeron.manager.features.runtime

import android.content.Context
import android.os.Build
import android.os.Environment
import frb.axeron.api.Axeron
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行时模块诊断收集器（仅为排查「装上了但列表不显示」而加，非业务逻辑）。
 *
 * 设计原则：诊断逻辑与业务链路完全隔离——业务代码只调用 [record]/[step] 等静态方法，
 * 收集器本身只往内存列表里追加字符串，任何异常都自行吞掉，绝不影响主流程。
 *
 * 导出后用手机文件管理器 / 外部工具读取外部存储 AxManagerDiag 目录下的 json 文件即可。
 */
object RuntimeDiagnostics {

    /** 单次导出目录（外部存储）。 */
    private const val DIR_NAME = "AxManagerDiag"

    /** 内存中的事件流水。 */
    private val events = ArrayList<String>()

    private val lock = Any()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 记录一条诊断事件（带时间戳）。任何异常都忽略。 */
    fun record(tag: String, message: String) {
        try {
            val line = "${timeFmt.format(Date())} [$tag] $message"
            synchronized(lock) {
                events.add(line)
                if (events.size > 2000) events.removeAt(0)
            }
        } catch (_: Throwable) {
        }
    }

    /** 记录一条「步骤」事件，通常配合耗时。 */
    fun step(name: String) = record("STEP", name)

    /** 清空历史。 */
    fun clear() {
        synchronized(lock) { events.clear() }
    }

    /**
     * 生成一份完整诊断快照 JSON。
     *
     * 内容包含：
     *  - 设备与路径环境（各种候选路径的存在性、可读性、内容列表）
     *  - module.prop 解析结果
     *  - 运行时模块扫描链路的关键中间值
     *  - 本次进程内记录的事件流水
     */
    fun snapshot(context: Context?): JSONObject {
        val root = JSONObject()
        root.put("generatedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("appVersion", appVersion(context))
        root.put("androidSdk", Build.VERSION.SDK_INT)
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")

        root.put("paths", collectPaths())
        root.put("axeronInfo", collectAxeronInfo())
        root.put("runtimeScan", collectRuntimeScan())
        root.put("events", JSONArray().also { arr ->
            synchronized(lock) { events.forEach { arr.put(it) } }
        })
        return root
    }

    /**
     * 导出诊断快照到外部存储，返回文件绝对路径；失败返回 null。
     *
     * 优先写 /sdcard/Download/AxManagerDiag/，失败则退回 app 外部私有目录。
     */
    fun export(context: Context): File? {
        return try {
            val json = snapshot(context).toString(2)
            val fileName = "axdiag_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"

            val candidates = ArrayList<File>()
            try {
                val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                candidates.add(File(pub, DIR_NAME))
            } catch (_: Throwable) {
            }
            try {
                context?.getExternalFilesDir(null)?.let { candidates.add(File(it, DIR_NAME)) }
            } catch (_: Throwable) {
            }
            candidates.add(File("/sdcard/Download/$DIR_NAME"))

            for (dir in candidates) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    if (!dir.isDirectory) continue
                    val f = File(dir, fileName)
                    f.writeText(json)
                    record("EXPORT", "已写出 ${f.absolutePath}")
                    return f
                } catch (e: Throwable) {
                    record("EXPORT", "写 ${dir.absolutePath} 失败：${e.javaClass.simpleName}: ${e.message}")
                }
            }
            null
        } catch (e: Throwable) {
            record("EXPORT", "导出异常：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // 各分项采集
    // ------------------------------------------------------------------

    private fun appVersion(context: Context?): String {
        return try {
            val pi = context?.packageManager?.getPackageInfo(context.packageName, 0)
            "${pi?.versionName}(${pi?.longVersionCode})"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    /** 采集各候选路径的存在性 / 权限 / 内容列表。 */
    private fun collectPaths(): JSONObject {
        val obj = JSONObject()

        // 固定候选：shell DE 与 root 两种（都不碰 binder 之外的调用）
        val shellParent = PathHelper.getWorkingPath(false, AxeronApiConstant.folder.PARENT)
        val shellPlugin = PathHelper.getWorkingPath(false, AxeronApiConstant.folder.PARENT_PLUGIN)
        val rootParent = try {
            PathHelper.getWorkingPath(true, AxeronApiConstant.folder.PARENT)
        } catch (_: Throwable) {
            null
        }

        obj.put("shellParent", describe(shellParent))
        obj.put("shellPlugin", describe(shellPlugin))
        obj.put("rootParent", rootParent?.let { describe(it) } ?: JSONObject.NULL)
        obj.put("runtimePluginFolderConst", "runtime_plugins")
        obj.put("runtimeDirUnderShell", describe(File(shellParent, "runtime_plugins")))

        // 采样一个模块目录的 module.prop 原文
        try {
            val sample = File(File(shellParent, "runtime_plugins"), SAMPLE_ID)
            obj.put("sampleDir", describe(sample))
            val prop = File(sample, "module.prop")
            obj.put("sampleModuleProp", if (prop.isFile) prop.readText() else "(缺失)")
        } catch (_: Throwable) {
        }
        return obj
    }

    /** 描述一个路径：存在性、可读、可写、子项列表。 */
    private fun describe(f: File?): JSONObject {
        val o = JSONObject()
        if (f == null) {
            o.put("path", JSONObject.NULL)
            return o
        }
        o.put("path", f.absolutePath)
        o.put("exists", f.exists())
        o.put("isDir", f.isDirectory)
        o.put("canRead", f.canRead())
        o.put("canWrite", f.canWrite())
        try {
            o.put("canonicalPath", f.canonicalPath)
        } catch (_: Throwable) {
        }
        if (f.isDirectory) {
            val arr = JSONArray()
            try {
                f.listFiles()?.take(50)?.forEach { child ->
                    arr.put(
                        JSONObject().apply {
                            put("name", child.name)
                            put("isDir", child.isDirectory)
                            put("canRead", child.canRead())
                        }
                    )
                }
            } catch (e: Throwable) {
                o.put("listError", "${e.javaClass.simpleName}: ${e.message}")
            }
            o.put("children", arr)
        }
        return o
    }

    /** 采集 Axeron binder 信息（每步单独 try，避免整体卡住）。 */
    private fun collectAxeronInfo(): JSONObject {
        val o = JSONObject()
        var info: JSONObject? = null
        try {
            val t0 = System.currentTimeMillis()
            val ai = Axeron.getAxeronInfo()
            o.put("getAxeronInfo.costMs", System.currentTimeMillis() - t0)
            info = try {
                ai?.let {
                    JSONObject().apply {
                        put("isRoot", it.isRoot())
                        put("raw", it.toString())
                    }
                }
            } catch (e: Throwable) {
                JSONObject().apply { put("error", "${e.javaClass.simpleName}: ${e.message}") }
            }
            o.put("axeronInfo", info ?: JSONObject.NULL)
        } catch (e: Throwable) {
            o.put("axeronInfoError", "${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val t0 = System.currentTimeMillis()
            val plugins = Axeron.getPlugins()
            o.put("getPlugins.costMs", System.currentTimeMillis() - t0)
            o.put("pluginCount", plugins.size)
            val arr = JSONArray()
            plugins.take(20).forEach { p ->
                arr.put(
                    JSONObject().apply {
                        put("id", p.prop.id)
                        put("dirId", p.dirId)
                        put("remove", p.remove)
                        put("enabled", p.enabled)
                    }
                )
            }
            o.put("plugins", arr)
        } catch (e: Throwable) {
            o.put("getPluginsError", "${e.javaClass.simpleName}: ${e.message}")
        }
        return o
    }

    /** 复跑一次运行时目录扫描并记录中间值。 */
    private fun collectRuntimeScan(): JSONObject {
        val o = JSONObject()
        try {
            val t0 = System.currentTimeMillis()
            val dirs = frb.axeron.manager.features.runtime.registry.RuntimeModuleRegistry.scanRuntimeDirs()
            o.put("scanRuntimeDirs.costMs", System.currentTimeMillis() - t0)
            o.put("dirCount", dirs.size)
            val arr = JSONArray()
            dirs.forEach { d ->
                arr.put(
                    JSONObject().apply {
                        put("name", d.name)
                        put("path", d.absolutePath)
                        put(
                            "isRuntimeModule",
                            frb.axeron.manager.features.runtime.registry.RuntimeModuleDetector.isRuntimeModule(d)
                        )
                        put(
                            "parsedId",
                            frb.axeron.manager.features.runtime.registry.RuntimeModuleDetector
                                .parsePropFile(File(d, "module.prop"))["id"]
                        )
                        put(
                            "minManagerCode",
                            frb.axeron.manager.features.runtime.registry.RuntimeModuleDetector
                                .readMinManagerCode(d)
                        )
                    }
                )
            }
            o.put("dirs", arr)
        } catch (e: Throwable) {
            o.put("scanError", "${e.javaClass.simpleName}: ${e.message}")
            o.put("stack", e.stackTraceToString())
        }
        return o
    }

    /** 默认采样模块目录名（测试样例）。 */
    private const val SAMPLE_ID = "com.demo.thermal-guard"
}
