package frb.axeron.manager.features.runtime.process

import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import java.io.File

/**
 * 采集数据下发器（对应设计文档 4.3）。
 *
 * 把 App 侧采集到的 JSON 写入模块目录下的 feed.json，模块脚本自行轮询消费。
 *
 * 关键：模块目录位于 /data/user_de/0/com.android.shell/axeron/runtime_plugins 下，
 * 是 com.android.shell 私有目录，App 进程无写权限。因此必须通过
 * AxeronPluginService（shell 身份）执行命令写入——这也是本项目既有做法。
 */
object FeedWriter {
    private const val TAG = "RuntimeFeedWriter"
    const val FILE_NAME = "feed.json"

    /**
     * 写入 feed.json。
     *
     * JSON 通过 base64 传递，避免 shell 引号转义问题。
     *
     * @param dir  模块私有目录
     * @param json 已序列化好的 JSON 文本
     * @return true 表示写入成功
     */
    fun writeAtomically(dir: File, json: String): Boolean {
        val path = File(dir, FILE_NAME).absolutePath
        return try {
            val b64 = android.util.Base64.encodeToString(
                json.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val cmd = "mkdir -p '" + dir.absolutePath + "' && echo '" + b64 + "'"
            val r = kotlinx.coroutines.runBlocking {
                AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", cmd + " | base64 -d > '" + path + "'"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = 5_000L,
                )
            }
            if (r.exitCode != 0) {
                Log.w(TAG, "写入 feed.json 失败(" + r.exitCode + "): " + r.stderr.trim())
            }
            r.exitCode == 0
        } catch (e: Throwable) {
            Log.e(TAG, "写入 feed.json 异常: " + path, e)
            false
        }
    }

    /** 删除某模块的 feed.json（停止时清理）。 */
    fun clear(dir: File) {
        try {
            val path = File(dir, FILE_NAME).absolutePath
            kotlinx.coroutines.runBlocking {
                AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", "rm -f '" + path + "'"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = 3_000L,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "清理 feed.json 失败", e)
        }
    }
}
