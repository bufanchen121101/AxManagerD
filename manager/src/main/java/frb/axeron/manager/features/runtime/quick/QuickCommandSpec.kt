package frb.axeron.manager.features.runtime.quick

/**
 * 一条快捷指令的声明。
 *
 * 全部只读：禁止写系统节点（写操作归模块自己的脚本）。
 */
data class QuickCommandSpec(
    /** 指令 id，如 cpu.load。 */
    val id: String,
    /** 分组：CPU / 内存 / 温度 / 电池 / 网络 / 存储 / 屏幕 / 进程。 */
    val group: String,
    /** 中文说明。 */
    val label: String,
    /** 实际执行的 shell（只读）。 */
    val cmd: String,
    /** 是否依赖 busybox。 */
    val useBusybox: Boolean = true,
)

/** 分组名常量。 */
object QuickGroup {
    const val CPU = "CPU"
    const val MEMORY = "内存"
    const val THERMAL = "温度"
    const val BATTERY = "电池"
    const val NETWORK = "网络"
    const val STORAGE = "存储"
    const val SCREEN = "屏幕"
    const val PROCESS = "进程"
}