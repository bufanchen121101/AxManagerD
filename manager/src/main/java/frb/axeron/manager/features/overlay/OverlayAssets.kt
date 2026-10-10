package frb.axeron.manager.features.overlay

import android.content.Context
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.api.core.AxeronSettings
import frb.axeron.manager.util.OverlayLog
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 模块覆盖层（overlay）——App 侧「assets 优先读覆盖」的统一入口。
 *
 * 背景：overlay 目录约定为 `<模块目录>/overlay/assets/<相对路径>`，
 * 与 api 层 [AxeronPluginService] 释放 `bin/` 核心脚本时使用的目录**完全一致**。
 * 但 api 层此前只消费 `assets/scripts/` 下的核心脚本，App 自己读取的其它
 * assets（`js/` 下的脚本、`docs/` 下的文档等）没有覆盖入口 —— 于是「核心文件修改」
 * 实际只能改脚本，改不了界面相关内容。
 *
 * 本类补上 App 侧的消费端：
 *   - 枚举所有模块的覆盖层，按 priority（整数，大者优先；未声明按路径升序）选出胜者；
 *   - 用 shell 身份读出覆盖内容（App 进程无权限读 shell 私有目录）；
 *   - 调用方以「overlay 优先 → 回退 APK assets」的方式取用。
 *
 * 与本项目既有约定保持一致：
 *   - 总开关沿用 [AxeronSettings.getEnableModuleOverlay]（与 api 层同一开关），
 *     关闭时本类一律返回 null，行为与改动前完全相同；
 *   - 目录、优先级、remove 标记过滤规则与 api 层一致，不引入第二套语义；
 *   - 读取失败/超时一律返回 null，绝不抛出，保证「没有覆盖」时走原路径。
 */
object OverlayAssets {

    private const val TAG = "OverlayAssets"

    /** 单次 shell 调用超时。 */
    private const val TIMEOUT_MS = 4_000L

    /** 相对路径长度上限（防御性）。 */
    private const val MAX_REL_LEN = 512

    /** shell 单引号安全包裹（与 OverlayManager 同款写法）。 */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 相对路径安全校验。
     *
     * 复用 [OverlayManager.isSafeRel]（禁止绝对路径、`..`、控制字符），
     * 并额外禁止单引号（本类会把路径拼进 shell 单引号内）与超长路径。
     */
    private fun isSafe(relPath: String): Boolean =
        OverlayManager.isSafeRel(relPath) &&
            relPath.length <= MAX_REL_LEN &&
            !relPath.contains("'")

    /** 以 shell 身份执行单行命令，返回 (exitCode, stdout, stderr)；异常一律吞掉。 */
    private suspend fun exec(command: String): Triple<Int, String, String> {
        return try {
            val r = AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("/system/bin/sh", "-c", command),
                env = Axeron.getEnvironment(),
                timeoutMs = TIMEOUT_MS,
            )
            Triple(r.exitCode, r.stdout, r.stderr)
        } catch (e: Throwable) {
            OverlayLog.w("$TAG exec 失败: ${e.message}")
            Triple(-1, "", e.toString())
        }
    }

    /**
     * 枚举所有模块的覆盖层，返回命中 [relPath] 且优先级最高的文件（shell 域绝对路径）。
     *
     * 排序规则与 api 层一致：priority 大的胜出；相同（含都未声明）时按路径升序，
     * 保证结果稳定可复现。
     *
     * @return 绝对路径；未命中或总开关关闭时返回 null。
     */
    suspend fun resolvePath(relPath: String): String? {
        if (!isSafe(relPath)) return null
        // 总开关关闭 → 一律不生效（与改动前行为一致）。
        if (!runCatching { AxeronSettings.getEnableModuleOverlay() }.getOrDefault(false)) return null

        val runtime = OverlayManager.runtimeRoot()
        val shell = OverlayManager.shellRoot()
        // 逐个基目录展开一级子目录；跳过带 remove 标记的模块；
        // 命中时输出 "priority|绝对路径"，由 App 侧排序选优。
        // 注意：这里的 `$base/$d/$f/$p` 属于**目标 shell 脚本**的变量，
        // 必须写成 `\$` 转义，否则会被 Kotlin 当作字符串模板插值（编译报未定义引用）。
        val cmd = buildString {
            append("for base in ").append(q(runtime)).append(" ").append(q(shell)).append("; do ")
            append("[ -d \"\$base\" ] || continue; ")
            append("for d in \"\$base\"/*; do ")
            append("[ -d \"\$d\" ] || continue; ")
            append("[ -f \"\$d/remove\" ] && continue; ")
            append("f=\"\$d/overlay/assets/").append(relPath).append("\"; ")
            append("[ -f \"\$f\" ] || continue; ")
            append("p=0; [ -f \"\$d/overlay/priority\" ] && p=\$(cat \"\$d/overlay/priority\" 2>/dev/null); ")
            append("echo \"\$p|\$f\"; ")
            append("done; ")
            append("done")
        }
        val (code, out, _) = exec(cmd)
        if (code != 0 || out.isBlank()) return null

        val hits = out.lineSequence()
            .mapNotNull { raw ->
                val line = raw.trim()
                val idx = line.indexOf('|')
                if (idx <= 0) return@mapNotNull null
                val pr = line.substring(0, idx).trim().toIntOrNull() ?: 0
                val path = line.substring(idx + 1).trim()
                if (path.isEmpty()) return@mapNotNull null
                pr to path
            }
            .toList()
        if (hits.isEmpty()) return null

        val winner = hits.sortedWith(
            compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second }
        ).first()
        OverlayLog.i("$TAG $relPath → ${winner.second} (priority=${winner.first}, 候选 ${hits.size})")
        return winner.second
    }

    /**
     * 读取覆盖层中的文本文件（utf-8）。
     *
     * @return 内容；未命中/读取失败返回 null。
     */
    suspend fun readTextOrNull(relPath: String): String? {
        val path = resolvePath(relPath) ?: return null
        val (code, out, _) = exec("cat " + q(path))
        return if (code == 0) out else null
    }

    /**
     * 读取覆盖层中的任意文件（base64 传输，适配二进制）。
     *
     * @return 字节；未命中/读取失败返回 null。
     */
    suspend fun readBytesOrNull(relPath: String): ByteArray? {
        val path = resolvePath(relPath) ?: return null
        // 用 base64 走单行管道，避免二进制内容在 stdout 中被破坏。
        val (code, out, _) = exec("base64 < " + q(path))
        if (code != 0 || out.isBlank()) return null
        return runCatching {
            android.util.Base64.decode(out.replace("\n", "").replace("\r", ""), android.util.Base64.DEFAULT)
        }.getOrNull()
    }

    /**
     * **非协程上下文**下的便捷读取（内部 runBlocking）。
     *
     * 仅供 WebView 资源回调这类「必须同步返回」的调用点使用；
     * 调用点本身已位于 IO 线程（如 shouldInterceptRequest），不会阻塞主线程。
     */
    fun readTextBlocking(relPath: String): String? = runCatching {
        runBlocking { readTextOrNull(relPath) }
    }.getOrNull()

    /**
     * overlay 优先的 InputStream。
     *
     * 命中覆盖 → 返回覆盖内容；未命中 → 回退 APK 内置 assets；两者都失败返回 null。
     */
    suspend fun open(context: Context, relPath: String): InputStream? {
        // 只枚举一次覆盖层：命中后先按 base64 读（兼容二进制），失败再按文本读。
        val path = resolvePath(relPath)
        if (path != null) {
            val bytes = runCatching {
                val (code, out, _) = exec("base64 < " + q(path))
                if (code == 0 && out.isNotBlank()) {
                    android.util.Base64.decode(
                        out.replace("\n", "").replace("\r", ""),
                        android.util.Base64.DEFAULT,
                    )
                } else null
            }.getOrNull()
            if (bytes != null) return ByteArrayInputStream(bytes)

            val text = runCatching {
                val (code, out, _) = exec("cat " + q(path))
                if (code == 0) out else null
            }.getOrNull()
            if (text != null) return ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        }
        return runCatching { context.assets.open(relPath) }.getOrNull()
    }
}