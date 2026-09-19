package frb.axeron.manager.features.runtime.model

/**
 * 运行时模块的状态机。
 *
 * 生命周期绑死 AxManagerD 的激活状态：激活时启动，取消激活时停止。
 */
enum class RuntimeModuleState {
    /** 未启用（卡片开关关闭）。 */
    DISABLED,

    /** 已启用但尚未运行。 */
    IDLE,

    /** 正在启动。 */
    STARTING,

    /** 运行中。 */
    RUNNING,

    /** 正在停止。 */
    STOPPING,

    /** 启动失败或运行中崩溃，等待退避重试。 */
    RETRYING,

    /** 已被系统杀死（例如 phantom 进程限制），等待存活探测拉起。 */
    KILLED,

    /** 失败终止（重试次数耗尽）。 */
    FAILED,
    ;

    /** 是否处于需要存活探测的状态。 */
    val isLive: Boolean
        get() = this == RUNNING || this == STARTING || this == RETRYING || this == KILLED
}

/**
 * 运行时快照：某一时刻模块的对外可见状态，供 UI / 通知使用。
 */
data class RuntimeModuleStatus(
    /** 模块 id，对应 module.prop 的 id。 */
    val id: String = "",
    /** Axeron 的目录 id，对应 PluginInfo.dirId。 */
    val dirId: String = "",
    /** 显示名。 */
    val name: String = "",
    /** 运行模型：ONESHOT / DAEMON / HYBRID。 */
    val runModel: String = RuntimeManifest.RUN_MODEL_ONESHOT,
    /** 当前状态。 */
    val state: RuntimeModuleState = RuntimeModuleState.DISABLED,
    /** 常驻进程 pid，未知时为 -1。 */
    val pid: Int = -1,
    /** 已重启次数。 */
    val restartCount: Int = 0,
    /** 最近一次状态变化时间戳，毫秒。 */
    val updatedAt: Long = 0L,
    /** 最近一次错误信息，正常时为空。 */
    val lastError: String = "",
    /** 上一次采集时间戳，毫秒。 */
    val lastFeedAt: Long = 0L,
    /**
     * 模块是否声明了 WebUI（目录内存在 webroot/index.html）。
     *
     * 与 shell 模块的 PluginInfo.hasWebUi 判定一致，
     * 用于在卡片上显示 WebUI 入口图标。
     */
    val hasWebUi: Boolean = false,
    /**
     * 模块是否处于「启用」状态（目录内不存在 disable 标记文件）。
     *
     * 这是持久化的真实开关状态，卸载/禁用后由标记文件决定；
     * UI 的 Switch 必须绑这个字段，而不是绑运行状态，
     * 否则「禁用后回后台再进来」会被重启流程重新拉活。
     */
    val enabled: Boolean = true,
    /**
     * 模块是否声明了动作脚本（目录内存在 action.sh）。
     *
     * 与 shell 模块的 PluginInfo.hasActionScript 语义一致，
     * 用于在卡片上显示「运行」入口。
     */
    val hasAction: Boolean = false,
    /**
     * 存活探测间隔（毫秒）。
     *
     * 由模块在 module.prop 里用 aliveCheckIntervalMs 声明；
     * 未声明时取默认值 8000ms。
     */
    val aliveCheckIntervalMs: Long = RuntimeModuleStatusDefaults.ALIVE_CHECK_INTERVAL_MS,
) {
    val isRunning: Boolean
        get() = state == RuntimeModuleState.RUNNING
}

/**
 * 运行时模块状态相关的默认值。
 *
 * 这些值必须与 author 在 module.prop 里声明的键名保持一致，
 * 因此单独抽出来，避免散落在多处导致不一致。
 */
object RuntimeModuleStatusDefaults {

    /** 存活探测间隔的默认值（毫秒），未声明时使用。 */
    const val ALIVE_CHECK_INTERVAL_MS = 8_000L

    /** 存活探测间隔的下限，防止声明过小导致打爆 CPU。 */
    const val MIN_ALIVE_CHECK_INTERVAL_MS = 2_000L

    /** 存活探测间隔的上限（phantom 限制要求 ≤10s，这里放宽到 10s 硬顶）。 */
    const val MAX_ALIVE_CHECK_INTERVAL_MS = 10_000L

    /** module.prop 里声明存活探测间隔的键名。 */
    const val KEY_ALIVE_CHECK_INTERVAL = "aliveCheckIntervalMs"
}