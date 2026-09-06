package frb.axeron.manager.ai

import android.os.Build

/**
 * strace 跟踪辅助器（方案 A：运行前用静态 strace 抓取模块「真实执行的指令」）。
 *
 * 背景：加密脚本（YTAS 三层壳、base64、openssl、eval、shc 编译的 ELF 等）在静态时是
 * 乱码，无法直接读出真实行为。但只要模块最终要执行真实命令（如 `axeron-dpm suspend`、
 * `pm disable`、`setprop`），就必然经过 `execve` 系统调用。
 *
 * 本辅助器用「静态编译的 strace」跟踪整个进程树（-f），只抓 execve（-e trace=execve），
 * 从而 100% 捕获解密/展开后「最终真实启动的命令」，不受任何加密方式影响。
 *
 * 两个静态 strace 二进制随 APK 打包在 assets/bin/ 下，运行时按 CPU ABI 选择，
 * 释放到 shell 权限可执行的目录（/data/local/tmp）并 chmod 755。
 */
object StraceHelper {
    /** 当前 ABI 对应的目标二进制文件名（落在 /data/local/tmp 下） */
    fun binaryName(): String {
        // 按设备 ABI 选择：arm64 设备用 strace-arm64，32 位 arm 设备用 strace-arm。
        // 两个静态二进制都已交叉编译并随 APK 打包在 assets/bin/ 下，运行时按 ABI 释放。
        // strace-arm64 之前的卡死（do_wait 不退出）已由 v33 的 `timeout -k 1` 从机制上修复
        // （timeout 会补发 SIGKILL 强杀 strace 本身），arm64 设备可放心用它跟踪 64 位进程树。
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return if (abi.startsWith("arm64") || abi.startsWith("aarch64")) "strace-arm64" else "strace-arm"
    }

    /** strace 在 /data/local/tmp 下的完整路径 */
    fun destPath(): String = "/data/local/tmp/${binaryName()}"

    /**
     * 生成「通过 shell 权限从 APK 自身 assets 里抽二进制并 chmod」的命令。
     * 用 `unzip -p $apkPath assets/xxx > dest`，规避 app 进程对 /data/local/tmp 无写权限的问题。
     * 调用方需在协程内用 AxeronPluginService.execProcessSafe 执行此命令。
     */
    fun buildExtractCmd(apkPath: String): String {
        val bin = binaryName()
        val dest = destPath()
        // busybox unzip 兼容更广；无 busybox 时退回系统 unzip
        return "unzip -p \"$apkPath\" assets/bin/$bin > \"$dest\" 2>/dev/null && chmod 755 \"$dest\" 2>/dev/null"
    }

    /**
     * 生成「后台启动」strace 的命令：用 setsid + & 让 strace 脱离当前 shell 立即返回，
     * 记录后台 shell 的 PID 到 pidFile，主流程随后轮询日志动态判断是否抓取完成。
     *
     * 为什么不用固定 timeout：每个模块解密/执行耗时差异巨大（简单模块 2 秒，常驻型
     * 带 watchdog 的模块几十秒），固定超时要么漏抓（太短）要么卡死（太长）。
     * 改用「后台启动 + 轮询日志 + 连续 N 秒无新 execve 即结束」的自适应方案，
     * 对任意大小/类型的模块都适用。
     */
    fun buildTraceCmd(stracePath: String, pluginPath: String, pluginBin: String, traceLog: String, pidFile: String, scriptName: String = "action.sh"): String {
        // 注意：这里【不再】手动 export PATH。模块解密脚本依赖系统 toybox 工具
        // （如 `grep -b`），若把 axeron/bin（busybox）提前会覆盖 toybox grep 导致解密失败。
        // 正确 PATH 由调用方通过 `execProcessSafe(env = Axeron.getEnvironment())` 注入，
        // 其顺序为 $AXERONXBIN:$PATH:$AXERONBIN（system 优先、axeron/bin 补充）。
        return "cd \"${pluginPath}\"; " +
            "rm -f \"$traceLog\"; " +
            "setsid sh -c '$stracePath -f -e trace=execve -o \"$traceLog\" sh ./$scriptName >/dev/null 2>&1 & echo \$! > \"$pidFile\"'; exit 0"
    }

    /**
     * 【前台同步 + timeout 版】用 `timeout` 限定时长前台跑 strace，到点强制杀进程并退出，
     * 避免常驻型 service.sh（while true 死循环）让 `setsid &` 后台方案在 Shizuku binder
     * 环境下 `waitFor()` 卡死。跑完命令自然返回，随后直接 cat 日志即可。
     *
     * @param timeoutMs 抓取时长（毫秒）。常驻脚本抓"启动后前几秒核心指令"即可。
     */
    fun buildTraceCmdSync(stracePath: String, pluginPath: String, traceLog: String, scriptName: String, timeoutSeconds: Int = 5): String {
        // 关键：必须加 `-k 1`（kill-after）。timeout 默认只发 SIGTERM，strace 作为 ptrace
        // 追踪者会把 SIGTERM 转发给被追踪的子进程（常驻 service.sh 死循环忽略它），而自己不退出，
        // 导致 waitFor() 卡死、日志不产出。加 `-k 1` 会在 SIGTERM 后 1 秒补发 SIGKILL 强制杀掉
        // strace 本身，使命令自然返回并写出日志（实测 exit=137、strace 无残留、日志正常生成）。
        //
        // v1.1.1 拦截效率优化：模块脚本（尤其"极限调度"类）里常嵌大量 `sleep`（几十处，累计十几秒），
        // 固定 timeout 方案会被这些 sleep 拖延，导致：① 耗时长（必须等 timeout 到点）；② 截不全
        // （关键指令如 pm disable 在品牌分支后段，timeout 到点强杀时还没执行到）。
        // 方案：生成脚本的【临时加速副本】——用 sed 把所有 `sleep X` 替换成 `sleep 0.01`，
        // 十几秒的 sleep 瞬间归零，strace 在极短时间内即可抓全所有 execve（含后段品牌分支指令），
        // 且不会改动模块原脚本。副本在 strace 结束后立即删除。
        val tmpScript = "${traceLog}.run.sh"
        return "cd \"${pluginPath}\"; " +
            "rm -f \"$traceLog\"; " +
            "sed 's/\\([[:space:]]\\)sleep[[:space:]][0-9.]*/\\1sleep 0.01/g' \"./$scriptName\" > \"$tmpScript\" 2>/dev/null; " +
            "timeout -k 1 $timeoutSeconds $stracePath -f -e trace=execve -o \"$traceLog\" sh \"$tmpScript\" >/dev/null 2>&1; " +
            "rm -f \"$tmpScript\"; " +
            "exit 0"
    }

    /** 生成「读取日志当前 execve 行数」的命令。 */
    fun buildCountCmd(traceLog: String): String =
        "grep -c execve \"$traceLog\" 2>/dev/null || echo 0"

    /** 生成「按 PID 清理整个后台进程树」的命令（杀 strace + 其跟踪的所有子孙进程）。 */
    fun buildKillCmd(pidFile: String, traceLog: String): String =
        "if [ -f \"$pidFile\" ]; then P=\$(cat \"$pidFile\" 2>/dev/null); if [ -n \"\$P\" ]; then kill -9 -- -\$P 2>/dev/null; kill -9 \"\$P\" 2>/dev/null; fi; rm -f \"$pidFile\"; fi; " +
            "pkill -9 -f strace-arm 2>/dev/null; pkill -9 -f strace-arm64 2>/dev/null; exit 0"

    /**
     * 解析一行 strace execve 输出，抽取出「真实命令」（argv0 及参数）。
     *
     * 形如：
     *  21693 execve("/data/user_de/.../axeron-dpm", ["axeron-dpm", "suspend", "com.bbk.updater"], 0x...) = 0
     * 返回：`axeron-dpm suspend com.bbk.updater`
     *
     * 若该行不是 execve，或无法解析出有效 argv，返回 null。
     */
    fun parseExecveLine(line: String): String? {
        if (line.isBlank()) return null
        if (!line.contains("execve(")) return null
        val arrStart = line.indexOf("[\"")
        if (arrStart < 0) return null
        val arrEnd = line.indexOf(']', arrStart)
        if (arrEnd < 0) return null
        val arrStr = line.substring(arrStart + 1, arrEnd)
        val args = mutableListOf<String>()
        var i = 0
        val sb = StringBuilder()
        var inStr = false
        var escaped = false
        while (i < arrStr.length) {
            val c = arrStr[i]
            when {
                escaped -> { sb.append(c); escaped = false }
                c == '\\' -> { escaped = true }
                c == '"' -> {
                    if (inStr) { args.add(sb.toString()); sb.setLength(0) }
                    inStr = !inStr
                }
                inStr -> sb.append(c)
            }
            i++
        }
        val meaningful = args.filter { it.isNotBlank() }
        if (meaningful.isEmpty()) return null
        val argv0 = meaningful.first()
        val base = argv0.substringAfterLast('/')
        val rest = meaningful.drop(1).joinToString(" ")
        return if (rest.isBlank()) base else "$base $rest"
    }
}
