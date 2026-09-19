package frb.axeron.api

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.annotations.SerializedName
import frb.axeron.api.ai.CommandAnalyzer
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Engine.Companion.application
import frb.axeron.server.Environment
import frb.axeron.server.PluginInstaller
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CompletableFuture


object AxeronPluginService {
    const val TAG = "PluginService"

    /**
     * 命令分析器（方案 A 拦截挂钩）。
     *
     * 由 manager 层在启动时注入实现。execWithIO / flashPlugin 在执行命令前
     * 会调用它；返回 null 表示无需分析（白名单），返回 AnalyzeResult 则按
     * allow 字段决定放行或拦截。
     */
    @Volatile
    var commandAnalyzer: CommandAnalyzer? = null

    /**
     * 内部白名单：AxManager 自身部署/维护命令，不应被 AI 拦截，
     * 否则会导致 App 启动/初始化陷入死循环。
     */
    private val INTERNAL_WHITELIST = listOf(
        "ignite", "ensureScripts", "ensureLibrary", "unzip -p", "dos2unix",
        "busybox --install", "cp $BUSYBOX", "cp $RESETPROP", "find $AXERONBIN",
        "ax_reignite.dex", "libbusybox", "libresetprop",
    )

    private fun isInternalCommand(cmd: String): Boolean {
        return INTERNAL_WHITELIST.any { cmd.contains(it) }
    }

    val BUSYBOX: String
        get() = "${application.applicationInfo.nativeLibraryDir}/libbusybox.so"
    val RESETPROP: String
        get() = "${application.applicationInfo.nativeLibraryDir}/libresetprop.so"
    val BASEAPK: String
        get() = application.applicationInfo.sourceDir

    val ROOT_MODE
        get() = Axeron.getAxeronInfo().isRoot()

    val AXERONDIR: String
        get() = PathHelper.getWorkingPath(ROOT_MODE, AxeronApiConstant.folder.PARENT).absolutePath
    val AXERONBIN: String
        get() = PathHelper.getWorkingPath(
            ROOT_MODE,
            AxeronApiConstant.folder.PARENT_BINARY
        ).absolutePath
    val PLUGINDIR: String
        get() = PathHelper.getWorkingPath(
            ROOT_MODE,
            AxeronApiConstant.folder.PARENT_PLUGIN
        ).absolutePath
    val PLUGINUPDATEDIR: String
        get() = PathHelper.getWorkingPath(
            ROOT_MODE,
            AxeronApiConstant.folder.PARENT_PLUGIN_UPDATE
        ).absolutePath
    val PLUGINBACKUPDIR: String
        get() = PathHelper.getWorkingPath(
            ROOT_MODE,
            AxeronApiConstant.folder.PARENT_PLUGIN_BACKUP
        ).absolutePath

    private val axFS: AxeronFileService?
        get() = Axeron.newFileService()

    fun getUid(context: Context, packageName: String): Int? =
        try {
            context.packageManager
                .getApplicationInfo(packageName, 0)
                .uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun checkManageExternalStorageUid(
        context: Context,
        uid: Int,
        packageName: String
    ): Int {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        return appOps.unsafeCheckOpNoThrow(
            "android:manage_external_storage",
            uid,
            packageName
        )
    }

    fun allowManageExternalStorageUid(uid: Int): Int {
        return Axeron.newProcess(
            arrayOf(
                "sh",
                "-c",
                "cmd appops set --uid $uid MANAGE_EXTERNAL_STORAGE allow"
            )
        ).waitFor()
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    fun ensureManageExternalStorageAllowed(
        context: Context,
        packageNames: List<String> = listOf(
            "com.android.externalstorage",
            "com.android.providers.downloads",
            "com.google.android.storagemanager"
        ),
        onResult: (Boolean) -> Unit
    ) {
        Thread {
            var allAllowed = true

            packageNames.forEach { pkg ->
                val uid = getUid(context, pkg) ?: return@forEach

                val mode = checkManageExternalStorageUid(
                    context = context,
                    uid = uid,
                    packageName = pkg
                )

                if (mode != AppOpsManager.MODE_DEFAULT && mode != AppOpsManager.MODE_ALLOWED) {
                    val exitCode = allowManageExternalStorageUid(uid)
                    if (exitCode != 0) {
                        allAllowed = false
                        return@forEach
                    }

                    // re-check (wajib)
                    val recheck = checkManageExternalStorageUid(
                        context = context,
                        uid = uid,
                        packageName = pkg
                    )

                    if (recheck != AppOpsManager.MODE_ALLOWED) {
                        allAllowed = false
                    }
                }
            }

            // balik ke main thread
            Handler(Looper.getMainLooper()).post {
                onResult(allAllowed)
            }
        }.start()
    }


    data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
        constructor(result: ResultExec, showReboot: Boolean) : this(
            result.code,
            result.err,
            showReboot
        )

        constructor(result: ResultExec) : this(result, result.isSuccess())
    }

    suspend fun flashPlugin(
        installer: PluginInstaller,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit
    ): FlashResult {
        val fs = axFS ?: return FlashResult(-1, "Axeron service unavailable", false)
        val resolver = application.contentResolver
        with(resolver.openInputStream(installer.uri)) {
            val file =
                File(
                    PathHelper.getWorkingPath(ROOT_MODE, AxeronApiConstant.folder.PARENT_ZIP),
                    "module.zip"
                )

            val session = fs.getStreamSession(file.absolutePath, true, false)
                ?: return FlashResult(-1, "Failed to create stream session", false)
            val fos = session.outputStream

            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            while (this?.read(buffer).also {
                    bytesRead = it ?: -1
                } != -1) {
                fos.write(buffer, 0, bytesRead)
            }
            fos.flush()
            this?.close()
            // 读取「插件兼容选项」标志：开启后安装时跳过 axeronPlugin 校验，兼容 Magisk/KernelSU 模块
            val pluginCompat = application.getSharedPreferences("plugin_settings", Context.MODE_PRIVATE)
                .getBoolean("plugin_compat", false)
            val compatFlag = if (pluginCompat) "true" else "false"
            val backupFlag = if (installer.backupInstall) "true" else "false"
            val runtimeFlag = if (installer.runtimeModule) "true" else "false"
            val cmd =
                "ZIPFILE=${file.absolutePath}; . functions.sh; install_plugin ${installer.autoEnable} $compatFlag $backupFlag $runtimeFlag; exit 0"

            // ---- AI 拦截挂钩（方案 A，安装场景）----
            // 运行时模块（installer.runtimeModule=true）不做拦截分析，直接安装。
            // 需求：运行时模块安装不需要像 shell 模块那样先拦截分析。
            val analyzer = commandAnalyzer
            if (analyzer != null && !installer.runtimeModule) {
                val name = installer.uri.lastPathSegment ?: "unknown"
                val ctx = CommandAnalyzer.CommandContext(
                    source = CommandAnalyzer.CommandContext.Source.INSTALL,
                    pluginName = name,
                )
                val analysis = analyzer.analyze(cmd, ctx)
                if (analysis != null && !analysis.allow) {
                    Log.w(TAG, "Install blocked by AI analyzer: $name")
                    fs.delete(file.absolutePath)
                    return FlashResult(AxeronPluginService.ResultExec(127, "", "已拦截：${analysis.summary}"))
                }
            } else if (analyzer != null && installer.runtimeModule) {
                Log.i(TAG, "Skip AI analyzer for runtime module: ${installer.uri.lastPathSegment}")
            }
            // ---- 挂钩结束 ----

            val result = execWithIO(cmd, onStdout, onStderr, standAlone = true)

            Log.i(TAG, "install module ${installer.uri} result: $result")

            fs.delete(file.absolutePath)

            return FlashResult(result)
        }
    }

    data class ResultExec(
        @SerializedName("errno")
        val code: Int,
        @SerializedName("stdout")
        val out: String = "",
        @SerializedName("stderr")
        val err: String = ""
    ) {
        fun isSuccess(): Boolean {
            return code == 0
        }
    }

    suspend fun execWithIO(
        cmd: String,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
        useSetsid: Boolean = false,
        useBusybox: Boolean = true,
        standAlone: Boolean = false,
        hideStderr: Boolean = true
    ): ResultExec = runCatching {

        Log.d(TAG, "execWithIO: $cmd")

        // ---- AI 拦截挂钩（方案 A）----
        val analyzer = commandAnalyzer
        if (analyzer != null && !isInternalCommand(cmd)) {
            val ctx = CommandAnalyzer.CommandContext(
                source = CommandAnalyzer.CommandContext.Source.INTERNAL
            )
            val result = analyzer.analyze(cmd, ctx)
            if (result != null && !result.allow) {
                Log.w(TAG, "Blocked by AI analyzer: $cmd")
                return@runCatching ResultExec(
                    code = 127,
                    out = "",
                    err = "已拦截：${result.summary}"
                )
            }
        }
        // ---- 挂钩结束 ----

        val process = Axeron.newProcess(
            if (useSetsid) arrayOf(BUSYBOX, "setsid", "sh")
            else arrayOf(BUSYBOX, "sh"),
            Axeron.getEnvironment(),
            null
        )

        process.outputStream.use { os ->
            val cmdLine = when {
                useBusybox && !standAlone -> "$BUSYBOX sh -c \"$cmd\"\n"
                useBusybox && standAlone -> "$BUSYBOX sh -o standalone -c \"$cmd\"\n"
                else -> "sh -c \"$cmd\"\n"
            }
            os.write(cmdLine.toByteArray())
            os.flush()
        }

        val builderOut = StringBuilder()
        val builderErr = StringBuilder()

        coroutineScope {

            val jobStdout = async(Dispatchers.IO) {
                val buf = ByteArray(4096)
                val stream = process.inputStream

                while (true) {
                    val len = stream.read(buf)
                    if (len <= 0) break
                    val chunk = String(buf, 0, len)

                    synchronized(builderOut) { builderOut.append(chunk) }
                    onStdout(chunk)
                }
            }

            val jobStderr = async(Dispatchers.IO) {
                val buf = ByteArray(4096)
                val stream = process.errorStream

                while (true) {
                    val len = stream.read(buf)
                    if (len <= 0) break
                    val chunk = String(buf, 0, len)

                    synchronized(builderErr) { builderErr.append(chunk) }
                    onStderr(chunk)
                }
            }

            // Tunggu keduanya selesai
            jobStdout.await()
            jobStderr.await()
        }

        val exit = process.waitFor()
        process.destroy()

        ResultExec(
            code = exit,
            out = builderOut.toString(),
            err = if (!hideStderr) builderErr.toString() else ""
        )
    }.getOrElse { e ->
        if (e is kotlinx.coroutines.CancellationException || e.toString()
                .contains("CancellationException")
        ) throw e
        ResultExec(-1, err = e.toString())
    }

    fun execWithIOFuture(
        cmd: String,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
        useBusybox: Boolean = true,
        standAlone: Boolean = false,
        hideStderr: Boolean = true
    ): CompletableFuture<ResultExec> {

        val future = CompletableFuture<ResultExec>()

        CoroutineScope(Dispatchers.IO).launch {

            val result = runCatching {

                val process = Axeron.newProcess(
                    arrayOf("sh"),
                    Axeron.getEnvironment(),
                    null
                )

                // KIRIM COMMAND
                process.outputStream.use { os ->
                    val cmdLine = when {
                        useBusybox && !standAlone -> "$BUSYBOX sh -c \"$cmd\"\n"
                        useBusybox && standAlone -> "$BUSYBOX sh -o standalone -c \"$cmd\"\n"
                        else -> "sh -c \"$cmd\"\n"
                    }
                    os.write(cmdLine.toByteArray())
                    os.flush()
                }

                val builderOut = StringBuilder()
                val builderErr = StringBuilder()

                supervisorScope {

                    val jobOut = async(Dispatchers.IO) {
                        val buf = ByteArray(4096)
                        val s = process.inputStream

                        while (true) {
                            val len = s.read(buf)
                            if (len <= 0) break

                            val chunk = String(buf, 0, len)
                            synchronized(builderOut) { builderOut.append(chunk) }
                            onStdout(chunk)
                        }
                    }

                    val jobErr = async(Dispatchers.IO) {
                        val buf = ByteArray(4096)
                        val s = process.errorStream

                        while (true) {
                            val len = s.read(buf)
                            if (len <= 0) break

                            val chunk = String(buf, 0, len)
                            synchronized(builderErr) { builderErr.append(chunk) }
                            onStderr(chunk)
                        }
                    }

                    jobOut.await()
                    jobErr.await()
                }

                val exit = process.waitFor()
                process.destroy()

                ResultExec(
                    code = exit,
                    out = builderOut.toString(),
                    err = if (!hideStderr) builderErr.toString() else ""
                )
            }

            future.complete(
                result.getOrElse { e ->
                    ResultExec(-1, err = e.toString())
                }
            )
        }

        return future
    }


    fun togglePlugin(dirId: String, enable: Boolean, backup: Boolean = false): Boolean {
        val fs = axFS ?: return false
        val pluginDir = if (backup) PLUGINBACKUPDIR else PLUGINDIR
        val path = "$pluginDir/$dirId"
        val updatePath = "$PLUGINUPDATEDIR/$dirId"

        if (enable) {
            // hapus disable di plugin folder
            fs.delete("$path/disable")

            //buat file jika memang dari awal gak ada keduanya
            if (!fs.exists("$updatePath/update_disable") && !fs.exists("$updatePath/update_enable")) {
                return fs.createNewFile("$updatePath/update_enable")
            }

            // hapus update_disable jika ada
            fs.delete("$updatePath/update_disable")
            // buat update_enable kalau belum ada

        } else {
            fs.createNewFile("$path/disable")

            // kalau update_enable ada, hapus update_enable
            if (!fs.exists("$updatePath/update_enable") && !fs.exists("$updatePath/update_disable")) {
                return fs.createNewFile("$updatePath/update_disable")
            }

            fs.delete("$updatePath/update_enable")
        }

        // kalau semua file sudah sesuai kondisi, return true
        return true
    }


    fun uninstallPlugin(dirId: String, backup: Boolean = false): Boolean {
        val fs = axFS ?: return false
        val pluginDir = if (backup) PLUGINBACKUPDIR else PLUGINDIR
        val path = "$pluginDir/$dirId"
        val updatePath = "$PLUGINUPDATEDIR/$dirId"
        return fs.createNewFile("$path/remove") && fs.createNewFile("$updatePath/update_remove")
    }

    fun restorePlugin(dirId: String, backup: Boolean = false): Boolean {
        val fs = axFS ?: return false
        val pluginDir = if (backup) PLUGINBACKUPDIR else PLUGINDIR
        val path = "$pluginDir/$dirId"
        val updatePath = "$PLUGINUPDATEDIR/$dirId"
        // 删除 remove / update_remove 两个标记即"恢复"。
        // 关键：Java File.delete() 对【已不存在的文件】返回 false，导致 `a && b` 整体失败，
        // 使"普通安装（无 update 目录）的模块"恢复永远失败。这里改为"幂等"语义：
        // 删除后只要两个标记都不存在，即视为恢复成功。
        fs.delete("$path/remove")
        fs.delete("$updatePath/update_remove")
        return !fs.exists("$path/remove") && !fs.exists("$updatePath/update_remove")
    }

    //===================================
    // IGNITER
    //===================================

    suspend fun resetManagerNative(
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): FlashResult = withContext(Dispatchers.IO) {

        fun out(s: String) = onStdout(s + "\n")
        fun err(s: String) = onStderr(s + "\n")

        out("Resetting AxManager")
        out("at $AXERONDIR")
        out("- Removing plugins")

        // 1) mark plugin remove
        val fs = axFS
        val pluginsDir = fs?.getDirectories(PLUGINDIR) ?: emptyList()
        if (pluginsDir.isEmpty()) {
            out("- No plugins directory")
        } else {
            pluginsDir.filter {
                it.isDirectory
            }.forEach { pluginDir ->
                out("- Mark to remove ${pluginDir.path}")
                runCatching {
                    fs?.createNewFile(File(pluginDir.path, "remove").absolutePath)
                }.onFailure {
                    err("!! failed touch ${pluginDir.path}/remove : ${it.message}")
                }
            }
        }

        // 2) jalankan igniter secara native (langsung panggil class)
        out("- Running igniter (native)")
        runCatching {
            igniteSuspendService(false,
                onStdout,
                onStderr)
        }.onFailure {
            err("!! Igniter crash: ${it.stackTraceToString()}")
            return@withContext FlashResult(-1, it.stackTraceToString(), false)
        }

        // 3) hapus folder
        out("- Removing AXERONDIR")
        runCatching {
            execWithIO("rm -rf \"$AXERONDIR\"")
        }.getOrElse {
            err("!! deleteRecursively error: ${it.message}")
            false
        }

        out("Complete")

        FlashResult(0, "", true)
    }


    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        fun isSuccess() = exitCode == 0
    }

    suspend fun execProcessSafe(
        cmd: Array<String>,
        env: Environment? = null,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {}
    ): ExecResult = withContext(Dispatchers.IO) {

        val process = Axeron.newProcess(cmd, env, null)

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outJob = launch {
            process.inputStream.bufferedReader().useLines {
                it.forEach { line ->
                    onStdout(line)
                    stdout.appendLine(line)
                }
            }
        }

        val errJob = launch {
            process.errorStream.bufferedReader().useLines {
                it.forEach { line ->
                    onStderr(line)
                    stderr.appendLine(line)
                }
            }
        }

        val exitCode = process.waitFor()

        outJob.join()
        errJob.join()

        process.destroy()

        ExecResult(exitCode, stdout.toString(), stderr.toString())
    }

    /**
     * 【带真实超时的 exec】与 [execProcessSafe] 的唯一区别：
     * 到点会**真正强杀底层进程**，而不是只取消协程。
     *
     * 背景（为什么必须新增这个函数）：
     * [execProcessSafe] 内部用 `process.waitFor()` 阻塞等待，这是 JVM 阻塞调用而非协程
     * 挂起点。调用方用 `withTimeoutOrNull { execProcessSafe(...) }` 只能取消协程，**杀不掉
     * 底层进程**：协程层"以为"超时返回了空串，实际进程还在后台跑，且 stdout 是在
     * waitFor() 之后才取的，超时分支永远拿不到任何输出。
     *
     * 本函数改为：先在独立协程里等 waitFor()，主协程用 withTimeoutOrNull 限时；
     * 一旦超时，显式 process.destroy() 强杀进程，再返回**已收集到的部分输出**
     * （流式读取的 stdout/stderr 在进程被杀后仍保留已读到的内容）。这样：
     *  - 超时真正生效（进程被杀，不再泄漏后台 strace/sh -x）；
     *  - 超时也能拿到部分日志，而不是丢成空串。
     *
     * @param timeoutMs 超时时长（毫秒）；<= 0 表示不限时（退化为 execProcessSafe 行为）。
     */
    suspend fun execProcessSafeWithTimeout(
        cmd: Array<String>,
        env: Environment? = null,
        timeoutMs: Long,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {}
    ): ExecResult = withContext(Dispatchers.IO) {
        if (timeoutMs <= 0) {
            return@withContext execProcessSafe(cmd, env, onStdout, onStderr)
        }

        val process = Axeron.newProcess(cmd, env, null)

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outJob = launch {
            runCatching {
                process.inputStream.bufferedReader().useLines {
                    it.forEach { line ->
                        onStdout(line)
                        synchronized(stdout) { stdout.appendLine(line) }
                    }
                }
            }
        }

        val errJob = launch {
            runCatching {
                process.errorStream.bufferedReader().useLines {
                    it.forEach { line ->
                        onStderr(line)
                        synchronized(stderr) { stderr.appendLine(line) }
                    }
                }
            }
        }

        // 在独立协程里等进程结束（waitFor 是阻塞调用，不能直接放在可取消的主协程上）
        val waitJob = async(Dispatchers.IO) { process.waitFor() }

        val exitCode = withTimeoutOrNull(timeoutMs) { waitJob.await() }

        val timedOut = (exitCode == null)
        if (timedOut) {
            // 关键：真正强杀底层进程（否则后台 strace / sh -x 会一直跑）
            runCatching { process.destroy() }
        }

        // 给流读取协程一点时间把已到达的数据读干（强杀后流会 EOF，join 很快返回）
        runCatching {
            withTimeoutOrNull(1000L) { outJob.join() }
        }
        runCatching {
            withTimeoutOrNull(1000L) { errJob.join() }
        }
        if (!outJob.isCompleted) outJob.cancel()
        if (!errJob.isCompleted) errJob.cancel()
        if (!waitJob.isCompleted) waitJob.cancel()

        runCatching { process.destroy() }

        val out = synchronized(stdout) { stdout.toString() }
        val err = synchronized(stderr) { stderr.toString() }

        ExecResult(
            exitCode = exitCode ?: -1,
            stdout = out,
            stderr = err + if (timedOut) "\n[execProcessSafeWithTimeout] 已超时 ${timeoutMs}ms，进程被强制终止" else ""
        )
    }

    suspend fun fsBarrier() {
        withContext(Dispatchers.IO) {
            // opsi minimal & portable
            delay(10)
        }
    }

    @JvmStatic
    fun igniteService(): Boolean {
        return runBlocking(Dispatchers.IO) {
            igniteSuspendService()
        }
    }

    suspend fun igniteSuspendService(
        ensure: Boolean = true,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {}
    ): Boolean =
        withContext(Dispatchers.IO) {

            val localVer = Axeron.getAxeronInfo().getVersionCode()
            val serverVer = AxeronApiConstant.server.VERSION_CODE

            if (serverVer > localVer) {
                Log.i(TAG, "Updating.. $localVer < $serverVer")
                return@withContext false
            }

            if (Axeron.isFirstInit(true)) {
                Log.i(TAG, "First Init: Removing old bin")
                removeScripts()
                removeLibrary()
                fsBarrier()
            }

            if (ensure) {
                if (!ensureLibrary()) return@withContext false
                fsBarrier()

                if (!ensureScripts()) return@withContext false
                fsBarrier()
            }

            val cmd =
                "CLASSPATH=$AXERONBIN/ax_reignite.dex; app_process / frb.axeron.reignite.Igniter ${AxeronSettings.getEnableDeveloperOptions()}"

            Log.d(TAG, "Start Init Service")

            val result = execProcessSafe(
                arrayOf(BUSYBOX, "sh", "-c", cmd),
                Axeron.getEnvironment(),
                onStdout,
                onStderr
            )

            if (result.stdout.isNotBlank()) Log.i(TAG, "STDOUT:\n${result.stdout}")
            if (result.stderr.isNotBlank()) Log.e(TAG, "STDERR:\n${result.stderr}")

            // ---- 激活回调钩子 ----
            // 场景：受非 root 权限限制，App 无法开机自启；开机后需要用户通过 shell
            // 指令等方式重新激活本软件（触发上面的 Igniter）。此时如果用户在
            // AXERONBIN 下放置了 onactivate.sh，则执行它，供用户挂接"开机后激活时"
            // 的自定义逻辑（同一路径也会随 assets/scripts/ 一起释放默认脚本）。
            //
            // 位置放在 Igniter 成功之后：激活成功才回调，失败时不误触发。
            if (result.isSuccess()) {
                runActivateHook(onStdout, onStderr)
            }
            // ---- 钩子结束 ----

            result.isSuccess()
        }

    /**
     * 执行激活回调脚本 [AXERONBIN]/onactivate.sh（存在才执行）。
     *
     * 需求：开机后通过 shell 指令等方式激活软件时，运行用户放置的脚本。
     * 这是纯可选钩子——脚本不存在时静默跳过，不影响激活主流程。
     */
    private suspend fun runActivateHook(
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val hook = File(AXERONBIN, "onactivate.sh")
        val exists = runCatching { axFS?.exists(hook.absolutePath) == true }.getOrDefault(false)
        if (!exists) return@withContext
        Log.i(TAG, "Running activate hook: ${hook.absolutePath}")
        runCatching {
            execProcessSafe(
                arrayOf(BUSYBOX, "sh", hook.absolutePath),
                Axeron.getEnvironment(),
                onStdout,
                onStderr,
            )
        }.onFailure { Log.w(TAG, "activate hook failed", it) }
    }


    suspend fun removeScripts() = withContext(Dispatchers.IO) {
        val fs = axFS ?: return@withContext
        val files = application.assets.list("scripts") ?: return@withContext
        if (files.isEmpty()) return@withContext

        for (filename in files) {
            val dstFile = File(AXERONBIN, filename)
            if (!fs.exists(dstFile.absolutePath)) continue

            if (!fs.delete(dstFile.absolutePath)) {
                Log.e(TAG, "failed to remove ${dstFile.absolutePath}")
                continue
            }

            Log.i(TAG, "removed ${dstFile.absolutePath}")
        }
    }

    suspend fun removeLibrary() = withContext(Dispatchers.IO) {
        val fs = axFS ?: return@withContext
        val dstBusybox = File(AXERONBIN, "busybox")

        if (fs.exists(dstBusybox.absolutePath)) {
            if (!fs.delete(dstBusybox.absolutePath)) {
                return@withContext
            }

            val cmd = "find $AXERONBIN -type l -delete"
            val result = execWithIO(cmd, useBusybox = false, hideStderr = false)

            if (!result.isSuccess()) {
                Log.e(TAG, "remove symlink failed: ${result.err}")
                return@withContext
            }

            Log.i(TAG, "symlink from busybox removed")
        }
        val dstResetprop = File(AXERONBIN, "resetprop")
        if (fs.exists(dstResetprop.absolutePath)) {
            fs.delete(dstResetprop.absolutePath)
        }
    }

    private fun isProbablyText(file: File): Boolean {
        val fs = axFS ?: return false
        if (!fs.exists(file.absolutePath)) return false
        return try {
            val inputStream = fs.setFileInputStream(file.absolutePath)
            val buffer = ByteArray(512) // Baca 512 byte pertama saja
            val bytesRead = inputStream.read(buffer)
            inputStream.close()

            if (bytesRead <= 0) return false

            // 1. Check Shebang (#! ) - Pasti script
            if (bytesRead >= 2 && buffer[0] == 0x23.toByte() && buffer[1] == 0x21.toByte()) {
                return true
            }

            // 2. Check Binary Signatures (ELF, DEX, ZIP) - Pasti bukan script
            // ELF: 7F 45 4C 46
            if (bytesRead >= 4 && buffer[0] == 0x7F.toByte() && buffer[1] == 0x45.toByte() &&
                buffer[2] == 0x4C.toByte() && buffer[3] == 0x46.toByte()) return false

            // DEX: 64 65 78
            if (bytesRead >= 3 && buffer[0] == 0x64.toByte() && buffer[1] == 0x65.toByte() &&
                buffer[2] == 0x78.toByte()) return false

            // 3. Fallback: Check if it's readable text (no null bytes)
            for (i in 0 until bytesRead) {
                if (buffer[i] == 0.toByte()) return false // Binary file biasanya punya null bytes
            }

            true
        } catch (e: Exception) {
            false
        }
    }


    private suspend fun ensureScripts(): Boolean = withContext(Dispatchers.IO) {
        val fs = axFS ?: return@withContext false
        val files = application.assets.list("scripts") ?: return@withContext false

        if (files.isEmpty()) return@withContext false

        val binDir = AXERONBIN

        if (!fs.exists(binDir) && !fs.mkdirs(binDir)) return@withContext false

        for (filename in files) {
            val dstFile = File(binDir, filename)

            // 用 sha256 比对 APK 内 asset 与设备上已释放文件内容：
            // 内容一致则跳过；不一致（含旧版本残留）则删除后重新解压，保证脚本更新能生效。
            // 之前用「存在即跳过」，导致更新 APK 后旧脚本（如 axeron-dpm）不会被覆盖。
            if (fs.exists(dstFile.absolutePath)) {
                val needUpdate = run {
                    val hashCmd =
                        "apkhash=\$($BUSYBOX unzip -p $BASEAPK assets/scripts/$filename | $BUSYBOX sha256sum | $BUSYBOX cut -d' ' -f1); " +
                            "curhash=\$($BUSYBOX sha256sum ${dstFile.absolutePath} | $BUSYBOX cut -d' ' -f1); " +
                            "[ \"\$apkhash\" = \"\$curhash\" ] && echo SAME || echo DIFF"
                    val r = execWithIO(hashCmd, hideStderr = true)
                    r.out.trim() != "SAME"
                }
                if (!needUpdate) {
                    Log.i(TAG, "$filename unchanged, skip")
                    continue
                }
                Log.i(TAG, "$filename changed, updating")
                fs.delete(dstFile.absolutePath)
            }

            // Ekstrak file
            val extractCmd = "$BUSYBOX unzip -p $BASEAPK assets/scripts/$filename > ${dstFile.absolutePath} && chmod 755 ${dstFile.absolutePath}"
            execWithIO(extractCmd)

            // Step 2: Cek tipe file secara advance
            val isText = isProbablyText(dstFile)

            if (isText) {
                // Jika text/script, jalankan dos2unix
                val fixCmd = "$BUSYBOX dos2unix ${dstFile.absolutePath}"
                execWithIO(fixCmd)
                Log.i(TAG, "$filename (Script) fixed with dos2unix")
            } else {
                Log.i(TAG, "$filename (Binary) skipped")
            }
        }
        return@withContext true
    }

    suspend fun ensureLibrary(): Boolean = withContext(Dispatchers.IO) {
        val fs = axFS ?: return@withContext false
        return@withContext try {
            if (!fs.exists(AXERONBIN) && !fs.mkdirs(AXERONBIN)) return@withContext false

            val dstBusyBox = File(AXERONBIN, "busybox")
            val dstResetProp = File(AXERONBIN, "resetprop")

            if (fs.exists(dstBusyBox.absolutePath) && fs.exists(dstResetProp.absolutePath)) return@withContext true

            val cmdBB =
                "cp $BUSYBOX ${dstBusyBox.absolutePath} && chmod 755 ${dstBusyBox.absolutePath}" +
                        " && ${dstBusyBox.absolutePath} --install -s $AXERONBIN"

            val rBB = execWithIO(cmdBB, useBusybox = false, hideStderr = false)
            if (!rBB.isSuccess()) {
                Log.e(TAG, "Failed to ensure busybox: ${rBB.err}")
                return@withContext false
            }

            val cmdRP =
                "cp $RESETPROP ${dstResetProp.absolutePath} && chmod 755 ${dstResetProp.absolutePath}"

            val rRP = execWithIO(cmdRP, useBusybox = false, hideStderr = false)
            if (!rRP.isSuccess()) {
                Log.e(TAG, "Failed to ensure resetprop: ${rRP.err}")
                return@withContext false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure library", e)
            false
        }
    }

}
