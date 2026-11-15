package frb.axeron.manager.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rosan.dhizuku.api.Dhizuku
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronCommandSession
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.utils.AnsiFilter
import frb.axeron.axerish.R
import frb.axeron.manager.owner.DeviceOwnerPrivilege
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickShellViewModel(application: Application) : AndroidViewModel(application) {

    var session: AxeronCommandSession = AxeronCommandSession()
    private var savedCommand: TextFieldValue? = null

    // 【v1.4.1】终端合并：不再区分 Shizuku / Dhizuku 两个模式，
    // Dhizuku 侧统一走 axeron-dpm 的 Device Owner 通道（见 [runCommand]）。

    init {
        session.setProcessListener(object : AxeronCommandSession.ProcessListener {
            override fun onProcessCreated(pid: Int, command: String) {
                Log.i("QuickShellViewModel", "onProcessCreated: $pid")
                if (command.lines().size > 1) {
                    append(OutputType.TYPE_COMMAND, "[command]")
                    append(OutputType.TYPE_COMMAND, command.trim())
                } else append(OutputType.TYPE_COMMAND, "[command] ${command.trim()}")
                append(OutputType.TYPE_START, "[start] pid=$pid")
                savedCommand = TextFieldValue(text = command, selection = TextRange(command.length))
                commandText = TextFieldValue("") // clear input
                execMode = "Inputs"
                isRunning = true
            }

            override fun onProcessRunning(input: String) {
                Log.i("QuickShellViewModel", "onProcessRunning: $input")
                val tagInput = if (input.lines().size > 1) "[input]\n" else "[input] "
                append(OutputType.TYPE_STDIN, tagInput + input.trim())
                commandText = TextFieldValue("")
            }

            override fun onProcessFinished(exitCode: Int, lastOutput: String) {
                Log.i("QuickShellViewModel", "onProcessFinished: $exitCode")
                if (!AnsiFilter.isScreenControl(lastOutput)) {
                    append(OutputType.TYPE_EXIT, "[exit] code=$exitCode")
                    append(OutputType.TYPE_SPACE, "")
                }
                execMode = "Commands"
                if (savedCommand != null) {
                    commandText = savedCommand!!
                    savedCommand = null
                }
                isRunning = false
            }
        })

        session.setResultListener(object : AxeronCommandSession.ResultListener {
            override fun output(output: CharSequence?) {
                output?.let {
                    append(OutputType.TYPE_STDOUT, it.toString())
                }
            }

            override fun onError(error: CharSequence?) {
                error?.let {
                    append(OutputType.TYPE_STDERR, it.toString())
                }
            }

        })
    }

    private val prefs = AxeronSettings.getPreferences()

    enum class OutputType(val labelId: Int) {
        TYPE_COMMAND(R.string.type_command),
        TYPE_START(R.string.type_start),
        TYPE_STDIN(R.string.type_stdin),
        TYPE_STDOUT(R.string.type_stdout),
        TYPE_STDERR(R.string.type_stderr),
        TYPE_THROW(R.string.type_throw),
        TYPE_SPACE(R.string.type_space),
        TYPE_EXIT(R.string.type_exit)
    }

    enum class KeyEventType(val labelId: Int) {
        VOLUME_UP(R.string.volume_up),
        VOLUME_DOWN(R.string.volume_down)
    }

    data class Output(val type: OutputType, var output: String, var completed: Boolean = false)

    var isShellRestrictionEnabled: Boolean by mutableStateOf(
        prefs.getBoolean("shell_restriction", true)
    )
        private set

    fun setShellRestriction(enable: Boolean) {
        isShellRestrictionEnabled = enable
        prefs.edit {
            putBoolean("shell_restriction", enable)
        }
    }

    var isCompatModeEnabled: Boolean by mutableStateOf(
        prefs.getBoolean("shell_compat_mode", true)
    )
        private set

    fun setCompatMode(enable: Boolean) {
        isCompatModeEnabled = enable
        prefs.edit {
            putBoolean("shell_compat_mode", enable)
        }
    }

    // 【v1.4.1】模式标记已移除：合并后的终端自动选择执行通道（见 runCommand）。
    // 保留一个只读判断，供 UI 显示当前实际通道（不是用户可切换的模式）。
    val isDhizukuMode: Boolean
        get() = !Axeron.pingBinder() && isDhizukuGranted()


    private val _output = MutableSharedFlow<Output>(extraBufferCapacity = 64)
    val output: SharedFlow<Output> = _output

    var isRunning by mutableStateOf(false)
        private set

    var commandText by mutableStateOf(TextFieldValue(""))
        private set

    var clear by mutableStateOf(false)
        private set

    var execMode by mutableStateOf("Commands")
        private set

    fun setCommand(text: TextFieldValue) {
        commandText = text
    }

    /**
     * 只读快照：最近一次执行的命令全文（供终端 AI 助手作为上下文）。
     *
     * 与 [savedCommand] 的区别：savedCommand 会被 onProcessFinished 还原清空，
     * 此处保存一份独立副本，不受执行流程影响。新增隔离方法，不改动既有逻辑。
     */
    private var lastCommandSnapshot: String = ""

    fun snapshotLastCommand(): String = lastCommandSnapshot

    fun clear() {
        //make a toggle state
        clear = !clear
    }

    fun stop() {
        // 【v1.4.1】合并后只剩 Axeron shell 一条常驻通道；
        // DPM 通道是一次性同步调用（无交互式进程），没有可中断的会话。
        runCatching { if (Axeron.pingBinder()) session.killSession() }
        isRunning = false
        execMode = "Commands"
    }

    /**
     * 拦截「不可执行的指令」，返回拦截原因（null 表示放行）。
     *
     * 规则：
     * 1. 空命令（含纯空白）→ 拦截
     * 2. 危险命令（rm -rf /、reboot、format、mkfs、dd 写块设备等）→ 拦截
     * 3. 通道是否可用 → 由 [runCommand] 统一判断并给出可操作提示
     */
    private fun blockReason(cmd: String): String? {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return "Command is empty."

        if (isShellRestrictionEnabled) {
            val lower = trimmed.lowercase()
            // 危险命令黑名单
            val dangerous = listOf(
                "rm -rf /", "rm -fr /", "rm -r /", "mkfs", "dd if=",
                "reboot", "shutdown", "format", "fdisk", "parted",
                "mke2fs", "mkswap", "wipe"
            )
            val isDangerous = dangerous.any { lower.contains(it) }
            // 额外：rm 递归删除根目录/关键路径
            val rmRoot = Regex("rm\\s+(-[a-zA-Z]*r[a-zA-Z]*\\s+)+/(\\s|$)").containsMatchIn(lower) ||
                    Regex("rm\\s+(-[a-zA-Z]*r[a-zA-Z]*\\s+)+/system(\\s|/)").containsMatchIn(lower)
            if (isDangerous || rmRoot) return "This command is dangerous and has been blocked."
        }

        // 【v1.4.1】Dhizuku 授权判断移到 [runCommand]：权限不足时应提示
        // 「两种通道都不可用」，而不是在这里按旧模式拦截。

        return null
    }

    private fun isDhizukuGranted(): Boolean = runCatching {
        Dhizuku.init(getApplication()) && Dhizuku.isPermissionGranted()
    }.getOrDefault(false)

    /**
     * 【v1.4.1】终端统一执行入口（合并原「Shizuku 终端 / Dhizuku 终端」两个模式）。
     *
     * 通道选择（无需用户切换）：
     *  1. Axeron 服务在运行（shell, uid 2000）→ 直接执行任意命令；
     *  2. 否则若已授权 Dhizuku → 走 axeron-dpm 同一条 Device Owner 通道
     *     （[DeviceOwnerPrivilege.execute]，支持 suspend / hide / force-stop 等 DPM 命令）；
     *  3. 两者都不可用 → 明确提示如何激活。
     */
    fun runCommand() {
        val cmd = commandText.text.ifBlank { return }
            .replace(Regex("[^\\p{Print}\n]"), "") // sanitize

        blockReason(cmd)?.let { reason ->
            append(OutputType.TYPE_THROW, "[blocked] $reason")
            return
        }

        lastCommandSnapshot = cmd // 供终端 AI 助手读取上下文

        if (Axeron.pingBinder()) {
            session.runCommand(cmd, isCompatModeEnabled)
            return
        }
        if (isDhizukuGranted()) {
            runViaDpm(cmd)
            return
        }
        append(
            OutputType.TYPE_THROW,
            "[blocked] AxManagerD 未激活，且 Dhizuku 未授权，无法执行命令。"
        )
    }

    /**
     * 通过 axeron-dpm 的 Device Owner 通道执行一条命令（等价于脚本 `axeron-dpm <cmd>`）。
     *
     * 注意：该通道只支持 [DeviceOwnerPrivilege] 白名单内的命令，不是任意 shell。
     * 与旧实现（`Dhizuku.newProcess`）相比，这条路不依赖 Dhizuku 的远程进程，
     * 在部分机型上更稳定。
     */
    private fun runViaDpm(cmd: String) {
        append(OutputType.TYPE_COMMAND, "[command] $cmd")
        append(OutputType.TYPE_START, "[start] dpm-bridge")
        commandText = TextFieldValue("")
        execMode = "Inputs"
        isRunning = true

        viewModelScope.launch(Dispatchers.IO) {
            // 允许用户直接输入 `axeron-dpm xxx`，与脚本用法保持一致。
            val tokens = cmd.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val args = if (tokens.firstOrNull()?.equals("axeron-dpm", true) == true) {
                tokens.drop(1)
            } else {
                tokens
            }
            val result = runCatching {
                DeviceOwnerPrivilege.execute(getApplication(), args)
            }.getOrElse { t ->
                4 to "Error: ${t.message ?: t.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) {
                if (result.second.isNotBlank()) {
                    append(OutputType.TYPE_STDOUT, result.second)
                }
                append(OutputType.TYPE_EXIT, "[exit] code=${result.first}")
                append(OutputType.TYPE_SPACE, "")
                execMode = "Commands"
                isRunning = false
            }
        }
    }

    private fun append(type: OutputType, output: String) {
        viewModelScope.launch {
            _output.emit(Output(type, output))
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 【v1.4.1】合并后只有 Axeron 常驻会话需要清理；
        // DPM 通道是一次性同步调用，没有可销毁的会话。
        runCatching { if (Axeron.pingBinder()) session.killSession() }
    }
}


