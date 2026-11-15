package frb.axeron.manager.util

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 【v1.4.1】以 root 身份执行一条命令。
 *
 * 为什么单独开一个工具，而不是继续用 `Shell.getShell().isRoot` 判断：
 *
 * 本项目的默认 libsu builder 被 [frb.axeron.manager.ui.util.createShellBuilder] 覆写成
 * `sh -c "sh <axrun 脚本> || su"`。也就是说 libsu 会**先**尝试 axrun（Shizuku 身份，
 * uid 2000），只要 axrun 能跑起来就永远不会落到 `su`。于是在「既装了 Shizuku 又有 root」
 * 的设备上，`Shell.getShell().isRoot` 恒为 false，激活页的「root 启动」按钮必然报失败
 * —— 这正是用户反馈的「root 启动方案失效」。
 *
 * 这里绕开 libsu，直接用 `su -c` 起进程，只看退出码：
 *  - 不依赖 libsu 会话状态，不会被 axrun 抢占；
 *  - 不需要 libsu 已初始化（开机广播早期、DO 回调进程里也能用）；
 *  - 失败重试一次（su 管理器弹窗/守护进程刚拉起时首次常失败）。
 */
object RootLauncher {

    private const val TAG = "RootLauncher"
    private const val TIMEOUT_SEC = 30L
    private const val LOG_LIMIT = 4096

    /** 设备是否真的能拿到 root（`su -c id` 输出含 uid=0）。 */
    fun isRootAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS) &&
                process.exitValue() == 0 &&
                output.contains("uid=0")
    }.getOrDefault(false)

    /**
     * 以 root 执行 [command]。
     *
     * @return true = 进程正常结束且退出码为 0
     */
    fun run(command: String): Boolean {
        repeat(2) { attempt ->
            val ok = runOnce(command)
            Log.d(TAG, "run attempt ${attempt + 1} -> $ok")
            if (ok) return true
            if (attempt == 0) {
                runCatching { Thread.sleep(1200) }
            }
        }
        return false
    }

    private fun runOnce(command: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        // 必须把输出读干净：su 的 stdout 管道写满后子进程会阻塞，waitFor 会一直等不到。
        val output = StringBuilder()
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(1024)
            while (true) {
                val n = reader.read(buffer)
                if (n <= 0) break
                if (output.length < LOG_LIMIT) {
                    output.append(buffer, 0, minOf(n, LOG_LIMIT - output.length))
                }
            }
        }
        val finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "timeout after ${TIMEOUT_SEC}s")
            return false
        }
        val code = process.exitValue()
        if (code != 0) {
            Log.w(TAG, "exit=$code output=${output.take(400)}")
        }
        code == 0
    }.getOrElse {
        Log.w(TAG, "su 执行失败: ${it.message}")
        false
    }
}
