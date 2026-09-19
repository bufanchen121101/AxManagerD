package frb.axeron.manager.features.runtime.model

/**
 * 运行时模块清单映射模型（对应模块根目录的 runtime.json）。
 *
 * 说明：本模型只描述「运行时逻辑声明」。模块的身份信息仍在 module.prop（见 ModuleProp）。
 * 识别运行时模块的唯一依据是 module.prop 里的 AxmanagerdID=runtime。
 */
data class RuntimeManifest(
    val schemaVersion: Int = 1,
    val runModel: String = RUN_MODEL_ONESHOT,
    val sensors: List<SensorDecl> = emptyList(),
    val triggers: List<TriggerDecl> = emptyList(),
    val permissions: List<String> = emptyList(),
) {
    companion object {
        const val RUN_MODEL_ONESHOT = "ONESHOT"
        const val RUN_MODEL_DAEMON = "DAEMON"
        const val RUN_MODEL_HYBRID = "HYBRID"

        const val SCHEMA_VERSION_CURRENT = 1
    }

    /** DAEMON / HYBRID 模式需要 entry.sh。 */
    val needsEntryScript: Boolean
        get() = runModel == RUN_MODEL_DAEMON || runModel == RUN_MODEL_HYBRID
}

/**
 * 采集声明。type 取值见 [RuntimeSensorType]。
 */
data class SensorDecl(
    val type: String = "",
    val intervalMs: Long = 0L,
    val params: Map<String, Any?> = emptyMap(),
)

/**
 * 触发器声明。type 取值见 [RuntimeTriggerType]。
 */
data class TriggerDecl(
    val type: String = "",
    /** INTERVAL 使用。 */
    val intervalMs: Long = 0L,
    /** THRESHOLD 使用：目标采集器类型。 */
    val sensor: String = "",
    /** THRESHOLD 使用：目标字段。 */
    val field: String = "",
    /** THRESHOLD 使用：比较运算符，取值 gt / lt / eq。 */
    val op: String = "",
    /** THRESHOLD 使用：阈值。 */
    val value: Double = 0.0,
    /** THRESHOLD 使用：冷却毫秒，防止连续触发。 */
    val cooldownMs: Long = 0L,
    /** EVENT 使用（二期）。 */
    val event: String = "",
    /** CRON 使用（二期）。 */
    val expr: String = "",
)

/**
 * 采集器类型白名单。
 */
object RuntimeSensorType {
    const val CPU = "CPU"
    const val MEMORY = "MEMORY"
    const val THERMAL = "THERMAL"
    const val BATTERY = "BATTERY"
    const val NETWORK = "NETWORK"
    const val STORAGE = "STORAGE"
    const val SCREEN = "SCREEN"
    const val PROCESS = "PROCESS"

    /** 首版支持的 8 种采集器（Q2 定案：全上）。 */
    val ALL = setOf(CPU, MEMORY, THERMAL, BATTERY, NETWORK, STORAGE, SCREEN, PROCESS)
}

/**
 * 触发器类型。首版只实现 INTERVAL / THRESHOLD（Q3 定案）。
 */
object RuntimeTriggerType {
    const val INTERVAL = "INTERVAL"
    const val THRESHOLD = "THRESHOLD"

    /** 二期。 */
    const val EVENT = "EVENT"
    const val CRON = "CRON"

    /** 首版实现范围。 */
    val IMPLEMENTED = setOf(INTERVAL, THRESHOLD)

    /** 声明里允许出现但暂不实现的。 */
    val DECLARED_ONLY = setOf(EVENT, CRON)
}
