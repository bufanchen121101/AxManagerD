package frb.axeron.manager.features.runtime.registry

import frb.axeron.manager.features.overlay.OverlayPermissionStore
import java.io.File

/**
 * 运行时模块能力声明（module.prop 的 `capabilities=`）。
 *
 * 背景：第一期把 overlay 接口做成了「模块主动申请 -> App 弹窗 -> 用户授权」，
 * 但 App 无法预知模块是否真的需要写核心文件。第二期引入能力声明，
 * 让模块在 module.prop 里预先声明自己要用的能力，App 侧据此：
 *   1. 在授权页标注「该模块声明了核心文件修改能力」；
 *   2. 对未声明却调用 axoverlay 的模块给出提示；
 *   3. （后续）按能力做更细粒度的授权。
 *
 * 语法（写在 module.prop 里，逗号分隔，大小写不敏感）：
 *
 *   capabilities=overlay,notify
 *
 * 支持的能力：
 *   - `overlay`  —— 需要修改 App 核心文件（assets 覆盖层）
 *   - `notify`   —— 需要使用申请通知（模块主动申请时弹通知）
 *   - `exec`     —— 需要以 shell 身份执行命令
 *
 * 未知能力会被忽略（向前兼容：新模块声明新能力时旧管理器不报错）。
 */
object RuntimeModuleCapabilities {

    /** module.prop 里的键名。 */
    const val KEY_CAPABILITIES = "capabilities"

    /** 修改 App 核心文件（overlay 覆盖层）。 */
    const val OVERLAY = "overlay"

    /** 授权申请通知。 */
    const val NOTIFY = "notify"

    /** shell 命令执行。 */
    const val EXEC = "exec"

    /** 已知能力白名单。 */
    val ALL = setOf(OVERLAY, NOTIFY, EXEC)

    /**
     * 解析能力声明字符串。
     *
     * @return 归一化（小写）并去重后的已知能力集合；未声明返回空集合。
     */
    fun parse(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',', ';', '|')
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .filter { it in ALL }
            .toSet()
    }

    /**
     * 从已解析的 module.prop 键值 map 中读取能力集合。
     */
    fun fromProp(prop: Map<String, String>): Set<String> = parse(prop[KEY_CAPABILITIES])

    /**
     * 直接从模块目录读取能力集合（会起 shell 进程读 module.prop）。
     */
    fun fromDir(dir: File): Set<String> =
        fromProp(RuntimeModuleDetector.parsePropFile(File(dir, "module.prop")))

    /**
     * 能力 → 人类可读标签（用于授权页展示）。
     *
     * @return 标签列表；空表示该模块未声明任何能力。
     */
    fun labels(caps: Set<String>): List<String> = caps.mapNotNull {
        when (it) {
            OVERLAY -> "核心文件修改"
            NOTIFY -> "权限申请通知"
            EXEC -> "命令执行"
            else -> null
        }
    }

    /**
     * 模块是否声明了 overlay 能力。
     *
     * 注意：这是「自我声明」，不构成授权。真正的授权判定仍在
     * [OverlayPermissionStore.isGranted]。
     */
    fun declaresOverlay(caps: Set<String>): Boolean = OVERLAY in caps
}
