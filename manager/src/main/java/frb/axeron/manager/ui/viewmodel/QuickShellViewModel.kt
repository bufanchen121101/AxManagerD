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
import com.rosan.dhizuku.api.DhizukuRemoteProcess
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronCommandSession
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.utils.AnsiFilter
import frb.axeron.axerish.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class QuickShellViewModel(application: Application) : AndroidViewModel(application) {

    var session: AxeronCommandSession = AxeronCommandSession()
    private var savedCommand: TextFieldValue? = null

    // Dhizuku terminal session (Device Owner privileged process)
    private var dhizukuSession: DhizukuProcessSession? = null

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

    // Dhizuku terminal mode
    var isDhizukuMode by mutableStateOf(false)
        private set

    // Toggle between Shell / Dhizuku terminals
    fun toggleTerminalMode() {
        isDhizukuMode = !isDhizukuMode
    }


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

    fun clear() {
        //make a toggle state
        clear = !clear
    }

    fun stop() {
        if (isDhizukuMode) {
            dhizukuSession?.destroy()
            dhizukuSession = null
            isRunning = false
            execMode = "Commands"
        } else {
            session.killSession()
        }
    }

    /**
     * 拦截「不可执行的指令」，返回拦截原因（null 表示放行）。
     *
     * 规则：
     * 1. 空命令（含纯空白）→ 拦截
     * 2. 危险命令（rm -rf /、reboot、format、mkfs、dd 写块设备等）→ 拦截
     * 3. 未授权 Dhizuku 时（Dhizuku 模式）→ 拦截并提示
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

        // Dhizuku 模式需要授权
        if (isDhizukuMode && !isDhizukuGranted()) {
            return "Dhizuku is not authorized. Please activate Dhizuku / Device Owner first."
        }

        return null
    }

    private fun isDhizukuGranted(): Boolean = runCatching {
        Dhizuku.init(getApplication()) && Dhizuku.isPermissionGranted()
    }.getOrDefault(false)

    fun runShell() {
        val cmd = commandText.text.ifBlank { return }
            .replace(Regex("[^\\p{Print}\n]"), "") // sanitize

        blockReason(cmd)?.let { reason ->
            append(OutputType.TYPE_THROW, "[blocked] $reason")
            return
        }

        session.runCommand(cmd, isCompatModeEnabled)
    }

    fun runDhizuku() {
        val cmd = commandText.text.ifBlank { return }
            .replace(Regex("[^\\p{Print}\n]"), "") // sanitize

        blockReason(cmd)?.let { reason ->
            append(OutputType.TYPE_THROW, "[blocked] $reason")
            return
        }

        runDhizukuInternal(cmd)
    }

    private fun runDhizukuInternal(cmd: String) {
        val session = DhizukuProcessSession(
            onCreated = { pid ->
                append(OutputType.TYPE_COMMAND, "[command] $cmd")
                append(OutputType.TYPE_START, "[start] pid=$pid")
                commandText = TextFieldValue("")
                execMode = "Inputs"
                isRunning = true
            },
            onOutput = { append(OutputType.TYPE_STDOUT, it) },
            onError = { append(OutputType.TYPE_STDERR, it) },
            onFinished = { exitCode ->
                append(OutputType.TYPE_EXIT, "[exit] code=$exitCode")
                append(OutputType.TYPE_SPACE, "")
                execMode = "Commands"
                isRunning = false
                dhizukuSession = null
            }
        )
        dhizukuSession = session
        session.start(cmd)
    }

    /**
     * 基于 Dhizuku.newProcess 的终端会话（Device Owner 特权进程）。
     * 线程模型参考 AxeronCommandSession：读 stdout/stderr、写 stdin、等待退出。
     */
    private inner class DhizukuProcessSession(
        private val onCreated: (pid: Int) -> Unit,
        private val onOutput: (String) -> Unit,
        private val onError: (String) -> Unit,
        private val onFinished: (exitCode: Int) -> Unit
    ) {
        private var process: DhizukuRemoteProcess? = null
        private var writer: BufferedWriter? = null

        fun start(cmd: String) {
            Thread {
                try {
                    val process = Dhizuku.newProcess(
                        arrayOf("sh", "-c", cmd),
                        null,
                        null
                    )
                    this.process = process
                    this.writer = BufferedWriter(OutputStreamWriter(process.outputStream))
                    val out = BufferedReader(InputStreamReader(process.inputStream))
                    val err = BufferedReader(InputStreamReader(process.errorStream))

                    // 触发 onCreated（pid 不可得，用 0 表示远程进程）
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onCreated(0)
                    }

                    val outThread = Thread {
                        try {
                            val buf = CharArray(8192)
                            while (true) {
                                val n = out.read(buf)
                                if (n == -1) break
                                val part = String(buf, 0, n)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onOutput(part)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    val errThread = Thread {
                        try {
                            val buf = CharArray(8192)
                            while (true) {
                                val n = err.read(buf)
                                if (n == -1) break
                                val part = String(buf, 0, n)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onError(part)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    outThread.start()
                    errThread.start()

                    val code = process.waitFor()
                    outThread.join()
                    errThread.join()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onFinished(code)
                    }
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onError("Dhizuku process failed: ${e.message}")
                        onFinished(-1)
                    }
                }
            }.start()
        }

        fun write(input: String) {
            try {
                writer?.let {
                    it.write(input)
                    it.newLine()
                    it.flush()
                }
            } catch (_: Exception) {
            }
        }

        fun destroy() {
            try {
                process?.destroy()
            } catch (_: Exception) {
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
        if (isDhizukuMode) {
            dhizukuSession?.destroy()
        } else if (Axeron.pingBinder()) {
            session.killSession()
        }
    }
}


