package frb.axeron.manager.features.runtime.registry

import frb.axeron.manager.features.runtime.model.RuntimeManifest
import frb.axeron.manager.features.runtime.model.RuntimeModuleState
import frb.axeron.manager.features.runtime.model.RuntimeModuleStatus
import frb.axeron.manager.features.runtime.model.RuntimeModuleStatusDefaults
import frb.axeron.manager.features.runtime.model.RuntimeSensorType
import frb.axeron.manager.features.runtime.model.RuntimeTriggerType
import frb.axeron.server.PluginInfo
import java.io.File

/**
 * 一个已加载的运行时模块条目。
 */
data class RuntimeModuleEntry(
    /** Axeron 插件信息。 */
    val plugin: PluginInfo,
    /** 模块根目录。 */
    val dir: File,
    /** runtime.json 的解析结果，可能为 null（文件缺失或格式非法）。 */
    val manifest: RuntimeManifest?,
    /** 校验过程中发现的问题，空表示健康。 */
    val issues: List<String> = emptyList(),
) {
    val id: String get() = plugin.prop.id
    val name: String get() = plugin.prop.name
    val dirId: String get() = plugin.dirId.ifBlank { plugin.prop.id }

    /** 是否通过全部校验（可运行）。 */
    val isValid: Boolean get() = issues.isEmpty() && manifest != null

    /** 运行模型，缺省按一次性处理。 */
    val runModel: String get() = manifest?.runModel ?: RuntimeManifest.RUN_MODEL_ONESHOT

    /**
     * 模块是否处于启用状态。
     *
     * 以目录内的 disable 标记文件为准（与 Axeron 服务端判定一致），
     * 而不是 plugin.enabled —— 运行时模块走纯文件扫描，
     * plugin.enabled 是我们自己补出来的，必须由标记文件推导才准。
     */
    val isEnabled: Boolean get() = !RuntimeModuleDetector.isDisabled(dir)

    /** 模块是否声明了 WebUI（webroot/index.html 存在）。 */
    val hasWebUi: Boolean get() = RuntimeModuleDetector.hasWebUi(dir)

    /** 模块是否声明了动作脚本（action.sh 存在）。 */
    val hasAction: Boolean get() = RuntimeModuleDetector.hasAction(dir)

    /**
     * 存活探测间隔（毫秒）。
     *
     * 由模块在 module.prop 里用 aliveCheckIntervalMs 声明；
     * 未声明时回落到默认值 8000ms。
     */
    val aliveCheckIntervalMs: Long
        get() = RuntimeModuleDetector.readAliveCheckIntervalMs(dir)
            ?: RuntimeModuleStatusDefaults.ALIVE_CHECK_INTERVAL_MS

    /** 转为初始状态快照。 */
    fun toInitialStatus(): RuntimeModuleStatus = RuntimeModuleStatus(
        id = id,
        dirId = dirId,
        name = name,
        runModel = runModel,
        state = if (isEnabled) RuntimeModuleState.IDLE else RuntimeModuleState.DISABLED,
        updatedAt = System.currentTimeMillis(),
        lastError = issues.joinToString("; "),
        hasWebUi = hasWebUi,
        enabled = isEnabled,
        hasAction = hasAction,
        aliveCheckIntervalMs = aliveCheckIntervalMs,
    )
}

/**
 * 运行时模块加载器。
 *
 * 职责：扫描 + 解析 + 校验，产出 [RuntimeModuleEntry] 列表。
 * P0 阶段不涉及进程与采集，只保证「能正确识别出一个健康的运行时模块」。
 */
object RuntimeModuleLoader {

    /** 必需文件：任何运行模型都要有。 */
    private val REQUIRED_ALWAYS = listOf("module.prop", "runtime.json", "onactivate.sh", "onstop.sh")

    /** DAEMON / HYBRID 额外必需。 */
    private const val REQUIRED_ENTRY = "entry.sh"

    /**
     * 加载单个模块。
     */
    fun load(plugin: PluginInfo, dir: File): RuntimeModuleEntry {
        val issues = ArrayList<String>()

        // 模块目录在 shell 数据目录下，App 进程 File.isDirectory 恒 false，
        // 必须走 binder 版检查，否则会误判「模块目录不存在」。
        if (!RuntimeModuleDetector.dirExists(dir.absolutePath)) {
            issues.add("模块目录不存在")
            return RuntimeModuleEntry(plugin, dir, null, issues)
        }

        // 1. 必需文件检查
        for (name in REQUIRED_ALWAYS) {
            if (!RuntimeModuleDetector.fileExists(dir, name)) issues.add("缺少必需文件 $name")
        }

        // 2. runtime.json 解析
        val manifest = RuntimeModuleDetector.parseManifest(dir)
        if (manifest == null) {
            issues.add("runtime.json 缺失或格式非法")
        } else {
            // 3. schemaVersion 校验
            if (manifest.schemaVersion > RuntimeManifest.SCHEMA_VERSION_CURRENT) {
                issues.add(
                    "schemaVersion ${manifest.schemaVersion} 高于当前支持版本 " +
                            RuntimeManifest.SCHEMA_VERSION_CURRENT
                )
            }

            // 4. runModel 合法性
            if (manifest.runModel !in setOf(
                    RuntimeManifest.RUN_MODEL_ONESHOT,
                    RuntimeManifest.RUN_MODEL_DAEMON,
                    RuntimeManifest.RUN_MODEL_HYBRID,
                )
            ) {
                issues.add("runModel 取值非法：${manifest.runModel}")
            }

            // 5. DAEMON / HYBRID 必须要有 entry.sh
            if (manifest.needsEntryScript && !RuntimeModuleDetector.fileExists(dir, REQUIRED_ENTRY)) {
                issues.add("runModel 为 ${manifest.runModel} 但缺少 $REQUIRED_ENTRY")
            }

            // 6. 采集声明校验
            if (manifest.sensors.isEmpty()) {
                issues.add("未声明任何采集器")
            }
            val invalidSensors = manifest.sensors.filter { it.type !in RuntimeSensorType.ALL }
            if (invalidSensors.isNotEmpty()) {
                issues.add("存在未知采集器类型：" + invalidSensors.joinToString(", ") { it.type })
            }

            // 7. 触发器校验
            for (t in manifest.triggers) {
                if (t.type !in RuntimeTriggerType.IMPLEMENTED && t.type !in RuntimeTriggerType.DECLARED_ONLY) {
                    issues.add("存在未知触发器类型：${t.type}")
                }
                if (t.type == RuntimeTriggerType.THRESHOLD) {
                    if (t.sensor.isBlank() || t.field.isBlank()) {
                        issues.add("阈值触发器缺少 sensor 或 field")
                    }
                    if (t.op !in setOf("gt", "lt", "eq")) {
                        issues.add("阈值触发器比较运算符非法：${t.op}")
                    }
                }
            }
        }

        // 8. 管理器版本要求
        val minCode = RuntimeModuleDetector.readMinManagerCode(dir)
        if (minCode > RuntimeModuleRegistry.MANAGER_CODE_CURRENT) {
            issues.add("要求管理器版本 >= $minCode，当前为 ${RuntimeModuleRegistry.MANAGER_CODE_CURRENT}")
        }

        return RuntimeModuleEntry(plugin, dir, manifest, issues)
    }

    /**
     * 加载全部运行时模块。
     */
    fun loadAll(plugins: List<PluginInfo>): List<RuntimeModuleEntry> {
        return RuntimeModuleRegistry.pickRuntimeModules(plugins).map { (plugin, dir) ->
            load(plugin, dir)
        }
    }
}