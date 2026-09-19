package frb.axeron.manager.features.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.manager.R
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import frb.axeron.manager.features.runtime.model.RuntimeModuleState
import frb.axeron.manager.features.runtime.model.RuntimeModuleStatus
import frb.axeron.manager.features.runtime.model.RuntimeModuleStatusDefaults
import frb.axeron.manager.features.runtime.model.RuntimeTriggerType
import frb.axeron.manager.features.runtime.process.FeedWriter
import frb.axeron.manager.features.runtime.process.RuntimeProcessManager
import frb.axeron.manager.features.runtime.registry.RuntimeModuleEntry
import frb.axeron.manager.features.runtime.registry.RuntimeModuleDetector
import frb.axeron.manager.features.runtime.registry.RuntimeModuleLoader
import frb.axeron.manager.features.runtime.registry.RuntimeModuleRegistry
import frb.axeron.manager.features.runtime.sensor.RuntimeSensors
import frb.axeron.server.PluginInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时模块常驻服务（对应设计文档 §5.2）。
 *
 * 职责：
 *  - 激活后扫描运行时模块；
 *  - 串行执行 onactivate.sh，按 runModel 决定是否拉起 entry.sh；
 *  - 周期性（≤10s）存活探测，崩溃自动重启（指数退避 1s→2s→4s）；
 *  - 采集数据原子写入 feed.json；
 *  - 取消激活时串行执行 onstop.sh + 杀进程组。
 *
 * 设计约束（务必遵守）：
 *  - App 只持有 pid，不持有 Process 对象；
 *  - 生命周期串行，避免并发写文件；
 *  - 探测间隔 ≤10s（phantom 限制兜底）。
 */
class RuntimeModuleService : Service() {

    companion object {
        private const val TAG = "RuntimeModuleService"
        private const val CHANNEL_ID = "axmanager_runtime"
        private const val NOTIFICATION_ID = 0x7A12
        private const val ACTION_START = "frb.axeron.manager.action.RUNTIME_START"
        private const val ACTION_STOP = "frb.axeron.manager.action.RUNTIME_STOP"
        private const val ACTION_RESCAN = "frb.axeron.manager.action.RUNTIME_RESCAN"

        /** 运行时模块的独立安装目录名（与 AxeronApiConstant 保持一致）。 */
        private const val RUNTIME_PLUGIN_FOLDER = "runtime_plugins"

        /** 存活探测间隔的默认值：模块未在 module.prop 声明 aliveCheckIntervalMs 时使用。 */
        private const val ALIVE_CHECK_INTERVAL_MS =
            RuntimeModuleStatusDefaults.ALIVE_CHECK_INTERVAL_MS

        /** 重启退避序列。
         *
         *  原为 [1s,2s,4s] 仅 3 档，开机早期 server 未就绪的窗口极易把重试次数耗尽，
         *  模块被永久熔断为 FAILED（必须手动点重试）。
         *  现放宽为 7 档（最长 60s），让模块有足够窗口自愈。 */
        private val RETRY_DELAYS_MS =
            longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L)

        /** 判定进程死亡前的二次确认间隔（避免瞬时 IO 异常导致的抖动重启）。 */
        private const val RECHECK_DELAY_MS = 800L

        /**
         * 全局状态快照，供 UI 订阅。
         *
         * 这里放在 service companion，UI 侧通过 RuntimeModuleService.statuses 读取。
         */
        var statuses by mutableStateOf<List<RuntimeModuleStatus>>(emptyList())
            private set

        private var instance: RuntimeModuleService? = null

        /** 服务是否在运行。 */
        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            val intent = Intent(context, RuntimeModuleService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RuntimeModuleService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
        /**
         * 请求重新扫描运行时模块目录。
         *
         * 场景：刚安装完运行时模块，需要让新模块立刻出现在列表中。
         * 若服务尚未运行则先拉起（start 会顺带扫描）。
         */
        fun rescan(context: Context) {
            val inst = instance
            if (inst == null) {
                start(context)
                return
            }
            val intent = Intent(context, RuntimeModuleService::class.java).apply { action = ACTION_RESCAN }
            context.startService(intent)
        }

        /** 由 UI 触发的「启用/停用某模块」。 */
        fun requestToggle(moduleId: String, enable: Boolean) {
            instance?.toggleModule(moduleId, enable)
        }

        /** 由 UI 触发的「重试」。 */
        fun requestRetry(moduleId: String) {
            instance?.retryModule(moduleId)
        }

        /**
         * 由 UI 触发的「执行动作」（等价于 shell 模块的「运行」入口）。
         *
         * 与 shell 模块语义一致：在模块自身目录下执行 action.sh。
         * 区别是运行时模块位于 runtime_plugins/，必须用模块自己的目录，
         * 不能走 ExecutePluginActionScreen 那条 PARENT_PLUGIN 路径。
         *
         * 若服务未运行，则直接静态执行一次（纯 shell，不依赖进程表）。
         *
         * @param onFinished 执行结束回调，在主线程调用。
         */
        fun requestAction(
            context: Context,
            moduleId: String,
            onFinished: ((ok: Boolean, body: String) -> Unit)? = null,
        ) {
            val inst = instance
            if (inst == null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val r = runCatching { runActionStatic(moduleId) }
                    val ok = r.getOrDefault(false)
                    val body = if (ok) "action.sh 已执行" else "action.sh 执行失败（模块未找到或服务异常）"
                    withContext(Dispatchers.Main) { onFinished?.invoke(ok, body) }
                }
                return
            }
            inst.runAction(moduleId, onFinished)
        }

        /**
         * 静态执行动作：不依赖服务实例，按 id 定位 runtime_plugins/<id> 后跑 action.sh。
         *
         * 用于服务未启动的场景（例如刚打开界面还没拉起服务就点了运行）。
         */
        private suspend fun runActionStatic(moduleId: String): Boolean {
            val entry = resolveEntryStatic(moduleId) ?: return false
            return RuntimeProcessManager().runScript(
                dir = entry,
                script = "action.sh",
                timeoutMs = RuntimeProcessManager.TIMEOUT_QUICK_CMD_MS,
                env = mapOf("AX_MODULE_DIR" to entry.absolutePath),
            ).isSuccess()
        }

        /**
         * 按 id 静态定位模块目录。
         *
         * 优先 runtime_plugins/<id>，回退 plugins/<id>（兼容旧装法）。
         */
        private suspend fun resolveEntryStatic(moduleId: String): File? {
            val root = runCatching { Axeron.getAxeronInfo().isRoot() }.getOrDefault(false)
            val axeronRoot = PathHelper.getWorkingPath(root, AxeronApiConstant.folder.PARENT)
            val runtimeDir = File(File(axeronRoot, RUNTIME_PLUGIN_FOLDER), moduleId)
            if (RuntimeModuleDetector.dirExists(runtimeDir.absolutePath)) return runtimeDir
            val legacyDir = File(
                PathHelper.getWorkingPath(root, AxeronApiConstant.folder.PARENT_PLUGIN),
                moduleId,
            )
            if (RuntimeModuleDetector.dirExists(legacyDir.absolutePath)) return legacyDir
            return null
        }

        /**
         * 由 UI 触发的「卸载」。
         *
         * 与 shell 模块卸载语义一致：先停进程，再执行模块自己的卸载脚本，
         * 最后写 remove 标记并重扫。
         *
         * 关键：即使服务没在跑，也必须先把模块的 onstop.sh / uninstall.sh 跑完，
         * 否则模块来不及清理它写进系统的东西（sysfs 节点、临时文件等）就被删了。
         */
        fun requestUninstall(context: Context, moduleId: String) {
            val inst = instance
            if (inst == null) {
                // 服务没跑：没有常驻进程要杀，但卸载脚本仍然必须执行。
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { runUninstallScriptsStatic(moduleId) }
                    runCatching { uninstallStatic(moduleId) }
                    rescan(context)
                }
                return
            }
            inst.uninstallModule(context, moduleId)
        }

        /**
         * 静态执行模块自带的卸载钩子（不依赖服务实例）。
         *
         * 按顺序跑 onstop.sh（通知模块停止）→ uninstall.sh（模块自己的清理），
         * 与 [uninstallModule] 的顺序保持一致，只是少了 killGroup（无进程表）。
         */
        private suspend fun runUninstallScriptsStatic(moduleId: String) {
            val dir = resolveEntryStatic(moduleId) ?: return
            val mgr = RuntimeProcessManager()
            RuntimeDiagnostics.record("UNINSTALL", "静态卸载钩子 dir=${dir.absolutePath}")
            mgr.runScript(
                dir = dir,
                script = "onstop.sh",
                timeoutMs = RuntimeProcessManager.TIMEOUT_ON_STOP_MS,
                env = mapOf(
                    "AX_REASON" to "uninstall",
                    "AX_MODULE_DIR" to dir.absolutePath,
                ),
            )
            mgr.runScript(
                dir = dir,
                script = "uninstall.sh",
                timeoutMs = RuntimeProcessManager.TIMEOUT_ON_STOP_MS,
                env = mapOf(
                    "AX_REASON" to "uninstall",
                    "AX_MODULE_DIR" to dir.absolutePath,
                ),
            )
        }

        /**
         * 静态卸载：写 remove / update_remove 标记文件。
         *
         * 运行时模块装在 axeron/runtime_plugins/<dirId>，与 shell 模块的
         * axeron/plugins/<dirId> 不是同一目录，因此不能直接复用
         * AxeronPluginService.uninstallPlugin（它只处理 PLUGINDIR）。
         * 这里用 shell 命令在两个候选目录都打上标记，保证旧装法也能卸掉。
         */
        suspend fun uninstallStatic(moduleId: String): Boolean {
            val root = runCatching { Axeron.getAxeronInfo().isRoot() }.getOrDefault(false)
            val axeronRoot = PathHelper.getWorkingPath(root, AxeronApiConstant.folder.PARENT)
            val runtimeDir = File(File(axeronRoot, RUNTIME_PLUGIN_FOLDER), moduleId)
            val legacyDir = File(
                PathHelper.getWorkingPath(root, AxeronApiConstant.folder.PARENT_PLUGIN),
                moduleId,
            )
            val updDir = PathHelper.getWorkingPath(root, AxeronApiConstant.folder.PARENT_PLUGIN_UPDATE)
            val script = buildString {
                append("touch '").append(runtimeDir.absolutePath).append("/remove' 2>/dev/null; ")
                append("touch '").append(legacyDir.absolutePath).append("/remove' 2>/dev/null; ")
                append("mkdir -p '").append(updDir.absolutePath).append("/").append(moduleId).append("' 2>/dev/null; ")
                append("touch '").append(updDir.absolutePath).append("/").append(moduleId)
                    .append("/update_remove' 2>/dev/null; ")
                append("echo DONE")
            }
            return runCatching {
                AxeronPluginService.execWithIO(
                    cmd = script,
                    useBusybox = true,
                    standAlone = false,
                    hideStderr = false,
                ).code == 0
            }.getOrDefault(false)
        }
    }

    /** 运行时模块的独立安装目录名（与 AxeronApiConstant 保持一致）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val manager = RuntimeProcessManager()

    /** 模块目录与 pid 的运行时表。 */
    private val pidTable = ConcurrentHashMap<String, Int>()

    /** 每个模块的重试计数。 */
    private val retryCount = ConcurrentHashMap<String, Int>()

    /** 每个模块的采集循环 Job。 */
    private val sensorJobs = ConcurrentHashMap<String, Job>()

    /** 每个模块的探测循环 Job。 */
    private val watchJobs = ConcurrentHashMap<String, Job>()

    private var mainLoop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))
    }

    override fun onDestroy() {
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(statuses.count { it.state == RuntimeModuleState.RUNNING }))
                startAll()
            }
            ACTION_RESCAN -> {
                startAll()
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------
    // 启动 / 停止 全局
    // ------------------------------------------------------------------

    /** 扫描并启动所有健康的运行时模块（串行）。 */
    private fun startAll() {
        RuntimeDiagnostics.record("SVC", "startAll 进入 mainLoop=${mainLoop} active=${mainLoop?.isActive}")
        if (mainLoop?.isActive == true) {
            RuntimeDiagnostics.record("SVC", "startAll 被已有循环挡住，直接返回")
            return
        }
        mainLoop = scope.launch {
            RuntimeDiagnostics.record("SVC", "startAll 协程体开始执行")
            val entries = try {
                loadEntries()
            } catch (e: Throwable) {
                RuntimeDiagnostics.record("SVC", "加载运行时模块失败：${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            RuntimeDiagnostics.record("SVC", "发现运行时模块 ${entries.size} 个：${entries.joinToString { it.id }}")

            // 先建立状态快照；已在运行的模块保留其运行时状态（重扫时不打断）。
            val prev = statuses.associateBy { it.id }
            statuses = entries.map { entry ->
                val old = prev[entry.id]
                if (old != null && old.state.isLive) {
                    // 保留运行态，但刷新标记文件推导出来的静态字段
                    // （enabled / hasWebUi / hasAction / 探测间隔）
                    old.copy(
                        enabled = entry.isEnabled,
                        hasWebUi = entry.hasWebUi,
                        hasAction = entry.hasAction,
                        aliveCheckIntervalMs = entry.aliveCheckIntervalMs,
                    )
                } else {
                    entry.toInitialStatus()
                }
            }

            for (entry in entries) {
                // 以 disable 标记文件为准，而不是我们自己补出来的 plugin.enabled。
                if (!entry.isEnabled) {
                    RuntimeDiagnostics.record("SVC", "跳过 ${entry.id}：已禁用（disable 标记存在）")
                    updateState(entry.id) { it.copy(state = RuntimeModuleState.DISABLED) }
                    continue
                }
                if (!entry.isValid) {
                    updateState(entry.id) { s ->
                        s.copy(
                            state = RuntimeModuleState.FAILED,
                            lastError = entry.issues.joinToString("; "),
                        )
                    }
                    continue
                }
                // 已在运行的模块不重复启动。
                if (pidTable.containsKey(entry.id)) {
                    RuntimeDiagnostics.record("SVC", "跳过 ${entry.id}：已在运行 pid=${pidTable[entry.id]}")
                    continue
                }
                launchModule(entry)
                delay(200) // 串行，错开启动
            }
            updateNotification()
            // 扫描循环结束，置空以便后续 rescan 能再次进入。
            mainLoop = null
        }
    }

    /** 停止所有模块并清理。 */
    private fun stopAll() {
        mainLoop?.cancel()
        mainLoop = null
        for ((id, job) in watchJobs) {
            job.cancel()
        }
        watchJobs.clear()
        for ((id, job) in sensorJobs) {
            job.cancel()
        }
        sensorJobs.clear()

        // 串行收尾
        CoroutineScope(Dispatchers.IO).launch {
            for (id in pidTable.keys.toList()) {
                val pid = pidTable.remove(id) ?: continue
                val entry = lastEntries[id]
                if (entry != null) {
                    manager.runScript(
                        dir = entry.dir,
                        script = "onstop.sh",
                        timeoutMs = RuntimeProcessManager.TIMEOUT_ON_STOP_MS,
                        env = mapOf("AX_REASON" to "deactivate"),
                    )
                }
                manager.killGroup(pid)
            }
            statuses = statuses.map { it.copy(state = RuntimeModuleState.IDLE) }
        }
    }

    /** 缓存的最近一次扫描结果，用于停止时找目录。 */
    private val lastEntries = ConcurrentHashMap<String, RuntimeModuleEntry>()

    private suspend fun loadEntries(): List<RuntimeModuleEntry> {
        // 每轮扫描前清空路径探测缓存，保证结果反映最新落盘状态。
        runCatching { RuntimeModuleDetector.clearProbeCache() }
        // 来源 A：独立目录 axeron/runtime_plugins/ 直接扫描（主来源）。
        // 纯文件 IO，不依赖 binder，必须始终可用。
        Log.i(TAG, "loadEntries: 开始扫描来源 A（独立目录）")
        RuntimeDiagnostics.record("LOAD", "开始扫描来源 A（独立目录）")
        val fromDir = loadEntriesFromRuntimeDir()
        RuntimeDiagnostics.record("LOAD", "来源 A 扫到 ${fromDir.size} 个：${fromDir.joinToString { it.id }}")
        Log.i(TAG, "loadEntries: 来源 A 扫到 ${fromDir.size} 个")

        // 来源 B：Axeron 插件列表里声明了 AxmanagerdID=runtime 的（兼容旧装法）。
        // 注意：Axeron.getPlugins() 是 binder 调用，服务端异常时可能阻塞整个扫描，
        // 导致连来源 A 的结果都无法返回（表现为「装上了但列表不显示」）。
        // 因此这里用超时兜底：限定时间内拿不到来源 B 就直接放弃，绝不影响来源 A。
        val fromPlugins = withTimeoutOrNull(2_000L) {
            try {
                val plugins = Axeron.getPlugins()
                runCatching { RuntimeModuleLoader.loadAll(plugins) }
                    .getOrElse {
                        Log.e(TAG, "从插件列表加载运行时模块失败", it)
                        emptyList()
                    }
            } catch (e: Exception) {
                Log.w(TAG, "来源 B（插件列表）不可用，跳过", e)
                emptyList()
            }
        } ?: emptyList<RuntimeModuleEntry>()

        // 以 id 去重，A 优先。
        val merged = LinkedHashMap<String, RuntimeModuleEntry>()
        for (e in fromDir) merged[e.id] = e
        for (e in fromPlugins) merged.putIfAbsent(e.id, e)
        val entries = merged.values.toList()
        lastEntries.clear()
        for (e in entries) lastEntries[e.id] = e
        return entries
    }

    /**
     * 直接在独立目录 axeron/runtime_plugins/ 下同步扫描运行时模块。
     *
     * 与 loadEntries() 的区别：不依赖 Axeron 的插件列表与 enabled 标记，
     * 因此刚安装但尚未被 Axeron 收录 / 尚未启用的模块也能被识别。
     */
    private suspend fun loadEntriesFromRuntimeDir(): List<RuntimeModuleEntry> {
        // scanRuntimeDirs 现在走 AxeronFileService（binder）枚举 shell 数据目录，
        // 仍是阻塞调用，故用超时兜底；超时/异常均不影响后续降级。
        val dirs = withContext(Dispatchers.IO) {
            runCatching { RuntimeModuleRegistry.scanRuntimeDirs() }
                .getOrElse {
                    RuntimeDiagnostics.record("SCAN", "扫描目录失败：${it.javaClass.simpleName}: ${it.message}")
                    Log.e(TAG, "扫描运行时模块目录失败", it)
                    emptyList()
                }
        }
        RuntimeDiagnostics.record("SCAN", "扫描到候选目录 ${dirs.size} 个：${dirs.joinToString { it.name }}")
        return dirs.mapNotNull { dir ->
            val prop = RuntimeModuleDetector.parsePropFile(File(dir, "module.prop"))
            val id = prop["id"].orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val dirId = dir.name
            // 本地字段补出最小 PluginInfo，用于 RuntimeModuleEntry 的身份展示。
            val plugin = PluginInfo(
                JSONObject().apply {
                    put("prop", JSONObject().apply {
                        put("id", id)
                        put("name", prop["name"] ?: id)
                        put("author", prop["author"] ?: "")
                        put("version", prop["version"] ?: "")
                        put("description", prop["description"] ?: "")
                    })
                    put("dir_id", dirId)
                    // 以 disable 标记文件推导真实启用态，不要硬编码 true，
                    // 否则「禁用后重进界面」会被判定为启用并重新拉起。
                    put("enabled", !RuntimeModuleDetector.isDisabled(dir))
                }.toString()
            )
            RuntimeModuleLoader.load(plugin, dir)
        }
    }

    // ------------------------------------------------------------------
    // 单模块生命周期
    // ------------------------------------------------------------------

    /** 启用并拉起某模块。 */
    private fun launchModule(entry: RuntimeModuleEntry) {
        RuntimeDiagnostics.record("LAUNCH", "launchModule(${entry.id}) needsEntry=${entry.manifest?.needsEntryScript}")
        scope.launch {
            updateState(entry.id) { it.copy(state = RuntimeModuleState.STARTING) }

            // 1. onactivate.sh（10s 超时）
            val act = manager.runScript(
                dir = entry.dir,
                script = "onactivate.sh",
                timeoutMs = RuntimeProcessManager.TIMEOUT_ON_ACTIVATE_MS,
                env = mapOf("AX_MODULE_DIR" to entry.dir.absolutePath),
            )
            RuntimeDiagnostics.record("LAUNCH", "onactivate.sh(${entry.id}) code=${act.code} stderr=${act.stderr.take(120)}")
            if (!act.isSuccess()) {
                Log.w(TAG, "onactivate.sh 失败(${entry.id}): ${act.stderr}")
            }

            // 2. 按 runModel 决定
            if (entry.manifest?.needsEntryScript == true) {
                val pid = manager.startDaemon(entry.dir, "entry.sh")
                RuntimeDiagnostics.record("LAUNCH", "startDaemon(${entry.id}) -> pid=$pid")
                if (pid <= 0) {
                    onModuleDied(entry, "entry.sh 启动失败，未拿到 pid")
                    return@launch
                }
                pidTable[entry.id] = pid
                retryCount[entry.id] = 0
                updateState(entry.id) { it.copy(state = RuntimeModuleState.RUNNING, pid = pid, restartCount = 0) }
                startWatchdog(entry)
            } else {
                // ONESHOT：跑完即结束
                val one = manager.runScript(
                    dir = entry.dir,
                    script = "entry.sh",
                    timeoutMs = RuntimeProcessManager.TIMEOUT_ON_ACTIVATE_MS,
                )
                updateState(entry.id) {
                    it.copy(
                        state = if (one.isSuccess()) RuntimeModuleState.IDLE else RuntimeModuleState.FAILED,
                        lastError = if (one.isSuccess()) "" else one.stderr,
                    )
                }
            }

            // 3. 采集循环（只对自己声明的采集器）
            startSensors(entry)
            updateNotification()
        }
    }

    /**
     * 存活探测 + 自动重启。
     *
     * 这是 phantom 进程限制的必需兜底（§5.1.1）。
     *
     * 探测间隔由模块在 module.prop 里用 aliveCheckIntervalMs 声明；
     * 未声明时使用默认值 8000ms（见 RuntimeModuleStatusDefaults）。
     */
    private fun startWatchdog(entry: RuntimeModuleEntry) {
        watchJobs[entry.id]?.cancel()
        val intervalMs = entry.aliveCheckIntervalMs
        RuntimeDiagnostics.record("LAUNCH", "startWatchdog(${entry.id}) interval=${intervalMs}ms")
        watchJobs[entry.id] = scope.launch {
            while (isActive) {
                delay(intervalMs)
                val pid = pidTable[entry.id] ?: continue

                // 【防误判 1】先判 server/binder 是否存活。
                // 若 server 已挂，则 isAlive 的底层 execWithIO 会全部失败并返回
                // 「DEAD」假象，导致所有模块被同时判定为「已退出」→ 一次性全屏爆红。
                // 因此 binder 不通时只负责把 server 拉回来，绝不动模块状态。
                if (!Axeron.pingBinder()) {
                    RuntimeDiagnostics.record(
                        "WATCHDOG",
                        "binder 不可用，重启 server（不判 ${entry.id} 死亡）",
                    )
                    runCatching { Axeron.newProcess(frb.axeron.api.core.Starter.internalCommand) }
                        .onFailure { Log.w(TAG, "重启 server 失败", it) }
                    continue
                }

                if (!manager.isAlive(pid, entry.dir)) {
                    // 【防误判 2】探测结果可能因瞬时 IO 异常而误报，
                    // 二次确认后才允许判定死亡，避免抖动型重启。
                    delay(RECHECK_DELAY_MS)
                    if (Axeron.pingBinder() && !manager.isAlive(pid, entry.dir)) {
                        onModuleDied(entry, "进程已退出（pid=$pid）")
                    }
                }
            }
        }
    }

    /** 进程死亡 → 指数退避重启，超上限熔断。 */
    private suspend fun onModuleDied(entry: RuntimeModuleEntry, reason: String) {
        RuntimeDiagnostics.record("LAUNCH", "onModuleDied(${entry.id}) reason=${reason}")
        val attempt = (retryCount[entry.id] ?: 0) + 1
        retryCount[entry.id] = attempt

        if (attempt > RETRY_DELAYS_MS.size) {
            Log.w(TAG, "模块 ${entry.id} 重试超限，熔断。原因：$reason")
            updateState(entry.id) {
                it.copy(state = RuntimeModuleState.FAILED, lastError = "重试超限：$reason", restartCount = attempt - 1)
            }
            watchJobs.remove(entry.id)?.cancel()
            pidTable.remove(entry.id)
            updateNotification()
            return
        }

        val delayMs = RETRY_DELAYS_MS[attempt - 1]
        Log.w(TAG, "模块 ${entry.id} 第 $attempt 次重启，${delayMs}ms 后。原因：$reason")
        updateState(entry.id) {
            it.copy(state = RuntimeModuleState.RETRYING, lastError = reason, restartCount = attempt)
        }
        delay(delayMs)

        val pid = manager.startDaemon(entry.dir, "entry.sh")
        if (pid > 0) {
            pidTable[entry.id] = pid
            updateState(entry.id) { it.copy(state = RuntimeModuleState.RUNNING, pid = pid, lastError = "") }
        } else {
            // 继续下一轮（loop 由 watchdog 驱动，这里主动再调一次）
            onModuleDied(entry, "重启失败，未拿到 pid")
        }
        updateNotification()
    }

    /** UI 手动重试。 */
    private fun retryModule(moduleId: String) {
        val entry = lastEntries[moduleId] ?: return
        retryCount[moduleId] = 0
        updateState(moduleId) { it.copy(state = RuntimeModuleState.STARTING, lastError = "") }
        launchModule(entry)
    }

    /**
     * UI 手动执行动作（等价于 shell 模块的「运行」入口）。
     *
     * 在模块自身目录下执行 action.sh。与 shell 模块的区别：
     * 这里用模块自己的目录（runtime_plugins/<dirId>），
     * 不走 ExecutePluginActionScreen 的 PARENT_PLUGIN 路径。
     *
     * 刻意不直接写 UI 的「输出」页：features 层不应反向依赖 ui 层。
     * 执行结果通过 [onFinished] 回调交回调用方处理。
     */
    private fun runAction(
        moduleId: String,
        onFinished: ((ok: Boolean, body: String) -> Unit)? = null,
    ) {
        val entry = lastEntries[moduleId] ?: return
        scope.launch {
            RuntimeDiagnostics.record("ACTION", "runAction($moduleId) dir=${entry.dir.absolutePath}")
            val result = manager.runScript(
                dir = entry.dir,
                script = "action.sh",
                timeoutMs = RuntimeProcessManager.TIMEOUT_QUICK_CMD_MS,
                env = mapOf("AX_MODULE_DIR" to entry.dir.absolutePath),
            )
            val body = buildString {
                append("退出码 : ").append(result.code).append('\n')
                if (result.stdout.isNotBlank()) {
                    append("--- stdout ---\n").append(result.stdout.trimEnd()).append('\n')
                }
                if (result.stderr.isNotBlank()) {
                    append("--- stderr ---\n").append(result.stderr.trimEnd()).append('\n')
                }
            }
            RuntimeDiagnostics.record("ACTION", "runAction($moduleId) code=${result.code}")
            withContext(Dispatchers.Main) {
                onFinished?.invoke(result.isSuccess(), body)
            }
        }
    }

    /**
     * UI 手动卸载（参考 shell 模块的卸载实现）。
     *
     * 步骤：
     *   1. 先按「停用」走一遍：onstop.sh + uninstall.sh + killGroup + 清 feed；
     *   2. 写 remove / update_remove 标记；
     *   3. 从内存表移除并重扫目录，卡片随即消失。
     */
    private fun uninstallModule(context: Context, moduleId: String) {
        scope.launch {
            val entry = lastEntries[moduleId]
            if (entry != null) {
                updateState(moduleId) { it.copy(state = RuntimeModuleState.STOPPING) }
                sensorJobs.remove(moduleId)?.cancel()
                watchJobs.remove(moduleId)?.cancel()
                pidTable.remove(moduleId)?.let { pid -> manager.killGroup(pid) }
                FeedWriter.clear(entry.dir)
                manager.runScript(
                    dir = entry.dir,
                    script = "onstop.sh",
                    timeoutMs = RuntimeProcessManager.TIMEOUT_ON_STOP_MS,
                    env = mapOf("AX_REASON" to "uninstall"),
                )
                // 模块可选的卸载钩子（不存在时 runScript 会返回失败，无需额外判断）
                manager.runScript(
                    dir = entry.dir,
                    script = "uninstall.sh",
                    timeoutMs = RuntimeProcessManager.TIMEOUT_ON_STOP_MS,
                    env = mapOf("AX_REASON" to "uninstall"),
                )
            }

            val ok = uninstallStatic(moduleId)
            RuntimeDiagnostics.record("LAUNCH", "uninstall($moduleId) ok=$ok")
            Log.i(TAG, "卸载模块 $moduleId -> $ok")

            lastEntries.remove(moduleId)
            retryCount.remove(moduleId)
            sensorJobs.remove(moduleId)?.cancel()
            watchJobs.remove(moduleId)?.cancel()
            pidTable.remove(moduleId)
            // statuses 是 private set，只能通过内部写入函数更新；这里直接过滤掉该模块
            removeStatus(moduleId)
            updateNotification()
            // 重扫目录，确保 Axeron 侧也同步（若模块已消失，卡片不会再出现）
            startAll()
        }
    }

    /** UI 手动启停。 */
    private fun toggleModule(moduleId: String, enable: Boolean) {
        val entry = lastEntries[moduleId] ?: return
        if (enable) {
            // 启用：删掉 disable 标记，让下次扫描/重启也能保持启用。
            scope.launch {
                writeDisableMarker(entry.dir, false)
                retryCount[moduleId] = 0
                updateState(moduleId) { it.copy(enabled = true) }
                launchModule(entry)
            }
        } else {
            scope.launch {
                updateState(moduleId) { it.copy(state = RuntimeModuleState.STOPPING) }
                sensorJobs.remove(moduleId)?.cancel()
                watchJobs.remove(moduleId)?.cancel()
                val pid = pidTable.remove(moduleId)
                FeedWriter.clear(entry.dir)
                manager.runScript(
                    dir = entry.dir,
                    script = "onstop.sh",
                    timeoutMs = RuntimeProcessManager.TIMEOUT_ON_STOP_MS,
                    env = mapOf("AX_REASON" to "disable"),
                )
                if (pid != null) manager.killGroup(pid)
                // 关键：写 disable 标记。
                // 仅杀进程不够 —— startAll() 会在下次进界面时重新拉起模块，
                // 表现为「禁用后放后台再打开又变成启用且仍在运行」。
                writeDisableMarker(entry.dir, true)
                updateState(moduleId) {
                    it.copy(
                        state = RuntimeModuleState.DISABLED,
                        pid = 0,
                        enabled = false,
                    )
                }
                updateNotification()
            }
        }
    }

    /**
     * 写 / 删模块目录内的 disable 标记文件。
     *
     * 语义与 Axeron 服务端一致（Service.kt: enabled = "disable" !in dirFiles）。
     * 模块目录在 shell 数据目录下，App 进程无写权限，必须走 shell。
     *
     * @param disabled true 写标记（禁用），false 删标记（启用）
     */
    private suspend fun writeDisableMarker(dir: File, disabled: Boolean) {
        val marker = File(dir, "disable").absolutePath
        val cmd = if (disabled) {
            "touch '$marker' 2>/dev/null; echo DONE"
        } else {
            "rm -f '$marker' 2>/dev/null; echo DONE"
        }
        runCatching {
            AxeronPluginService.execWithIO(
                cmd = cmd,
                useBusybox = true,
                standAlone = false,
                hideStderr = false,
            )
        }
        RuntimeDiagnostics.record("SVC", "writeDisableMarker(${dir.name}) disabled=$disabled")
    }

    // ------------------------------------------------------------------
    // 采集循环
    // ------------------------------------------------------------------

    private fun startSensors(entry: RuntimeModuleEntry) {
        val decls = entry.manifest?.sensors ?: return
        if (decls.isEmpty()) return

        // 取最小间隔作为统一采集周期
        val interval = decls.map { it.intervalMs }.filter { it > 0 }.minOrNull() ?: 30_000L
        val feedDir = File(entry.dir, "feed")

        sensorJobs[entry.id]?.cancel()
        sensorJobs[entry.id] = scope.launch {
            while (isActive) {
                val json = JSONObject()
                json.put("ts", System.currentTimeMillis())
                for (d in decls) {
                    if (d.type.isBlank()) continue
                    // 高频任务降频保护：低于 5s 的提升到 5s（§5.1.1）
                    json.put(d.type.lowercase(), RuntimeSensors.sample(d.type))
                }
                FeedWriter.writeAtomically(feedDir, json.toString())

                // 触发器的 INTERVAL 分支：本轮采集后统一求值
                evaluateTriggers(entry, json)

                delay(interval.coerceAtLeast(5_000L))
            }
        }
    }

    /**
     * 触发器求值（首版只实现 INTERVAL / THRESHOLD）。
     */
    private suspend fun evaluateTriggers(entry: RuntimeModuleEntry, feed: JSONObject) {
        val triggers = entry.manifest?.triggers ?: return
        for (t in triggers) {
            when (t.type) {
                RuntimeTriggerType.THRESHOLD -> {
                    val sensor = feed.optJSONObject(t.sensor.lowercase()) ?: continue
                    val v = sensor.optDouble(t.field, Double.NaN)
                    if (v.isNaN()) continue
                    val hit = when (t.op) {
                        "gt" -> v > t.value
                        "lt" -> v < t.value
                        "eq" -> kotlin.math.abs(v - t.value) < 1e-6
                        else -> false
                    }
                    if (hit) {
                        Log.i(TAG, "阈值触发 ${entry.id}: ${t.sensor}.${t.field} ${t.op} ${t.value}")
                        // 触发动作：调用模块自己的 ontrigger.sh（若存在）
                        if (RuntimeModuleDetector.fileExists(entry.dir, "ontrigger.sh")) {
                            manager.runScript(
                                dir = entry.dir,
                                script = "ontrigger.sh",
                                timeoutMs = RuntimeProcessManager.TIMEOUT_QUICK_CMD_MS,
                                env = mapOf(
                                    "AX_TRIGGER_SENSOR" to t.sensor,
                                    "AX_TRIGGER_FIELD" to t.field,
                                    "AX_TRIGGER_VALUE" to v.toString(),
                                ),
                            )
                        }
                    }
                }
                RuntimeTriggerType.INTERVAL -> {
                    // INTERVAL 由采集循环自身的节奏驱动，这里不额外处理
                }
                else -> {
                    // EVENT / CRON 为二期，静默跳过
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 状态与通知
    // ------------------------------------------------------------------

    private fun updateState(id: String, transform: (RuntimeModuleStatus) -> RuntimeModuleStatus) {
        val current = statuses
        val idx = current.indexOfFirst { it.id == id }
        val next = if (idx >= 0) {
            current.toMutableList().also { it[idx] = transform(it[idx]) }
        } else {
            return
        }
        statuses = next.map { if (it.id == id) it.copy(updatedAt = System.currentTimeMillis()) else it }
    }

    /** 从状态快照中移除某模块（卸载用）。 */
    private fun removeStatus(id: String) {
        statuses = statuses.filterNot { it.id == id }
    }

    private fun updateNotification() {
        val running = statuses.count { it.state == RuntimeModuleState.RUNNING }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(running)) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "运行时模块",
                NotificationManager.IMPORTANCE_LOW,
            )
            ch.description = "运行时模块运行状态"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(runningCount: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, frb.axeron.manager.ui.AxActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_axeron)
            .setContentTitle("运行时模块")
            .setContentText(if (runningCount > 0) "$runningCount 个模块正在运行" else "无运行中的模块")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}