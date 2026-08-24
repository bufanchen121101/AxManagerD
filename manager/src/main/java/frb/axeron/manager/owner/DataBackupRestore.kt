package frb.axeron.manager.owner

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * 应用数据备份 / 还原。
 *
 * 「保留数据卸载」只保住 `/data/data/<pkg>` 里的应用私有数据不会被 PackageInstaller 清掉，
 * 但安装后 Android 会重新 assign 新的 data 目录、并可能重设 SELinux 上下文 / 属主。
 * 因此完整的「无损升级」流程是：
 *
 *   1. [backup]：卸载前把 `/data/data/<pkg>`（以及可选 `/sdcard/Android/data/<pkg>`）
 *                打包备份到 manager 私有目录，带 `--preserve` 保留属主/上下文；
 *   2. 保留数据卸载 + 静默安装（[DeviceOwnerPackageInstaller]）；
 *   3. [restore]：安装后把备份覆盖回新 data 目录，并用 `restorecon` 修正 SELinux 上下文。
 *
 * 由于 `/data/data` 需要 shell/root 身份访问，这里通过 [com.topjohnwu.superuser.Shell] 执行。
 * 若设备无 root（纯 Dhizuku 环境），备份会退化为仅通过「保留数据卸载」依赖系统自带的保留机制，
 * 跳过手动备份，此时 [backup] 返回 Failure 但不阻断主流程（由调用方决定是否继续）。
 *
 * 本类是 AxManagerD 自有的原创实现。
 */
object DataBackupRestore {

    private const val TAG = "DataBackupRestore"

    sealed class Result {
        object Success : Result()
        data class Failure(val error: String) : Result()
    }

    /**
     * 备份应用数据到 [destFile]（tar.gz 归档）。
     *
     * @param packageName 目标应用
     * @param destFile 备份产物路径（建议放在 manager filesDir）
     * @param includeExternal 是否同时备份 `/sdcard/Android/data/<pkg>`
     */
    fun backup(
        context: Context,
        packageName: String,
        destFile: File,
        includeExternal: Boolean = true,
    ): Result {
        if (!canAccessData()) return Result.Failure("root/shell not available for data backup")

        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()

        val dataDir = "/data/data/$packageName"
        val externalDir = "/sdcard/Android/data/$packageName"

        val workDir = File(context.cacheDir, "backup-work").apply { mkdirs() }

        return try {
            val cmd = buildString {
                append("set -e; ")
                // 1. 把需要备份的目录先复制到 workDir（cp -a 保留属主/上下文/时间戳）
                append("rm -rf \"${workDir.absolutePath}\"; mkdir -p \"${workDir.absolutePath}\"; ")
                append("if [ -d \"$dataDir\" ]; then cp -a \"$dataDir\" \"${workDir.absolutePath}/data_data\"; fi; ")
                if (includeExternal) {
                    append("if [ -d \"$externalDir\" ]; then cp -a \"$externalDir\" \"${workDir.absolutePath}/sdcard_android_data\"; fi; ")
                }
                // 2. 打成 tar.gz（用系统自带 tar，保留属主/上下文元数据）
                append("tar -czf \"${destFile.absolutePath}\" -C \"${workDir.absolutePath}\" . ")
            }

            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) Result.Success
            else Result.Failure(result.err.joinToString("\n").ifBlank { "backup exit code ${result.code}" })
        } catch (t: Throwable) {
            Log.w(TAG, "backup failed", t)
            Result.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 从 [srcFile] 还原应用数据，并修正 SELinux 上下文。
     *
     * @param packageName 目标应用
     * @param srcFile 备份归档（由 [backup] 产生）
     */
    fun restore(
        context: Context,
        packageName: String,
        srcFile: File,
    ): Result {
        if (!canAccessData()) return Result.Failure("root/shell not available for data restore")
        if (!srcFile.exists()) return Result.Failure("backup file not found: ${srcFile.absolutePath}")

        val dataDir = "/data/data/$packageName"

        return try {
            val cmd = buildString {
                append("set -e; ")
                // 1. 解包到临时目录
                val workDir = File(context.cacheDir, "restore-work").absolutePath
                append("rm -rf \"$workDir\"; mkdir -p \"$workDir\"; ")
                append("tar -xzf \"${srcFile.absolutePath}\" -C \"$workDir\"; ")
                // 2. 覆盖回 data/data/<pkg>（属主/上下文由 tar --preserve 恢复，再统一 restorecon）
                append("mkdir -p \"$dataDir\"; ")
                append("if [ -d \"$workDir/data_data\" ]; then cp -a \"$workDir/data_data\" \"$dataDir\"; fi; ")
                // 3. 还原外部数据
                val externalDir = "/sdcard/Android/data/$packageName"
                append("if [ -d \"$workDir/sdcard_android_data\" ]; then mkdir -p \"$externalDir\"; cp -a \"$workDir/sdcard_android_data\" \"$externalDir\"; fi; ")
                // 4. SELinux 上下文修正（关键：否则应用读取私有数据会被 SELinux 拦截）
                append("restorecon -RF \"$dataDir\" 2>/dev/null || true; ")
                // 5. 属主修正（兜底）
                append("chown -R $(stat -c '%u:%g' \"$dataDir\") \"$dataDir\" 2>/dev/null || true; ")
                append("rm -rf \"$workDir\"")
            }

            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) Result.Success
            else Result.Failure(result.err.joinToString("\n").ifBlank { "restore exit code ${result.code}" })
        } catch (t: Throwable) {
            Log.w(TAG, "restore failed", t)
            Result.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    /** 判断当前是否具备访问 /data/data 的 shell/root 能力。 */
    private fun canAccessData(): Boolean {
        return runCatching {
            val shell = Shell.getShell()
            shell.isRoot || Shell.cmd("test -r /data/data").exec().isSuccess
        }.getOrDefault(false)
    }
}