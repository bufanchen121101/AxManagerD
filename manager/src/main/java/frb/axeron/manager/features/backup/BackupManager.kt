package frb.axeron.manager.features.backup

import android.content.Context
import android.os.Build
import frb.axeron.manager.features.overlay.OverlayManager
import frb.axeron.manager.util.OverlayLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用数据备份 / 还原管理器。
 *
 * 需求：首次打开软件时默认先备份一份「软件文件」到手机存储目录，
 * 后续方便用户还原。
 *
 * 设计要点：
 *  - **备份位置**：`/sdcard/AxManagerD/backup/`（用户可见、可手动拷走）。
 *  - **触发时机**：App 首次启动（SP 标记 [PREF_BACKED_UP] 未置位）自动执行一次；
 *    之后可在设置页手动「立即备份」/「从备份还原」。
 *  - **备份内容**：
 *      1. 本应用 APK 自身（`base.apk`），还原时用户可直接安装；
 *      2. 应用私有偏好（`shared_prefs`）只记录版本与时间戳，不复制敏感数据；
 *      3. Axeron 侧模块目录清单（`runtime_plugins/` + `plugins/` 一级目录名），
 *         用于还原后核对模块是否齐全（模块内容由 Axeron 自身管理，这里只做清单）。
 *  - **隔离性**：全部走独立目录 [backupRoot()]，不改动任何既有公共路径。
 *
 * 注意（真机约束）：App 属 `untrusted_app`，无法读写 shell 私有目录，
 * 因此模块**内容**不在此复制，仅经 shell 读取目录清单；APK 副本来自
 * 应用自身可读的 `ApplicationInfo.sourceDir`。
 */
object BackupManager {

    /** 备份根目录（手机存储，用户可见）。 */
    fun backupRoot(): File = File("/sdcard/AxManagerD/backup")

    /** 当前「最新」备份目录（每次备份生成带时间戳的子目录）。 */
    fun latestRoot(): File = File(backupRoot(), "latest")

    private const val PREF = "axmanager_backup"
    private const val PREF_BACKED_UP = "first_backup_done"
    private const val PREF_LAST_TIME = "last_backup_time"

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun dirStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    // -----------------------------------------------------------------------
    // 首次备份
    // -----------------------------------------------------------------------

    /** 是否已经完成过首次备份。 */
    fun isFirstBackupDone(context: Context): Boolean =
        sp(context).getBoolean(PREF_BACKED_UP, false)

    /** 上次备份时间（可读字符串；无则空）。 */
    fun lastBackupTime(context: Context): String =
        sp(context).getString(PREF_LAST_TIME, "").orEmpty()

    /**
     * 若尚未做过首次备份，则执行一次。
     *
     * @return true 表示本次执行了备份。
     */
    fun ensureFirstBackup(context: Context): Boolean {
        if (isFirstBackupDone(context)) return false
        val ok = backup(context, reason = "首次启动自动备份")
        return ok
    }

    /**
     * 执行一次完整备份。
     *
     * @return true 成功。
     */
    fun backup(context: Context, reason: String = "手动备份"): Boolean = try {
        val root = backupRoot()
        if (!root.exists() && !root.mkdirs()) {
            OverlayLog.w("backup: 无法创建备份目录 ${root.absolutePath}")
            return false
        }

        val dir = File(root, dirStamp())
        if (!dir.exists() && !dir.mkdirs()) {
            OverlayLog.w("backup: 无法创建本次备份目录 ${dir.absolutePath}")
            return false
        }

        // 1. 复制本应用 APK
        val srcApk = File(context.applicationInfo.sourceDir)
        if (srcApk.exists()) {
            srcApk.copyTo(File(dir, "AxManagerD-${BuildConfigVersion}.apk"), overwrite = true)
        } else {
            OverlayLog.w("backup: sourceDir 不存在 ${srcApk.absolutePath}")
        }

        // 2. 写元数据
        val meta = buildString {
            appendLine("app=frb.axeron.manager")
            appendLine("versionName=${BuildConfigVersion}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("time=${stamp()}")
            appendLine("reason=$reason")
        }
        File(dir, "backup.info").writeText(meta)

        // 3. 写模块清单（仅目录名，内容由 Axeron 管理）
        val manifest = buildString {
            appendLine("[runtime_plugins]")
            appendLine(moduleIds(OverlayManager.ModuleKind.RUNTIME).joinToString("\n"))
            appendLine("[plugins]")
            appendLine(moduleIds(OverlayManager.ModuleKind.SHELL).joinToString("\n"))
        }
        File(dir, "modules.manifest").writeText(manifest)

        // 4. 维护 latest 软链接风格副本（覆盖式，方便用户找）
        runCatching {
            val latest = latestRoot()
            if (latest.exists()) latest.deleteRecursively()
            dir.copyRecursively(latest, overwrite = true)
        }

        sp(context).edit()
            .putBoolean(PREF_BACKED_UP, true)
            .putString(PREF_LAST_TIME, stamp())
            .apply()

        OverlayLog.d("backup: 完成 -> ${dir.absolutePath}")
        true
    } catch (e: Throwable) {
        OverlayLog.w("backup: 失败 ${e}")
        false
    }

    /**
     * 还原：把 latest 备份里的 APK 复制回一个用户可访问的位置并提示安装。
     *
     * 说明：应用无法在无交互情况下自我覆盖安装（Android 限制），
     * 因此「还原」的实际动作是：把备份 APK 落到 `/sdcard/AxManagerD/backup/restore/`
     * 并返回该路径，由 UI 引导用户点击安装。模块内容不在此处理。
     *
     * @return 还原出的 APK 文件；失败返回 null。
     */
    fun restore(context: Context): File? = try {
        val latest = latestRoot()
        if (!latest.exists()) return null
        val apk = latest.listFiles()?.firstOrNull { it.name.endsWith(".apk") } ?: return null

        val outDir = File(backupRoot(), "restore")
        if (!outDir.exists()) outDir.mkdirs()
        val dst = File(outDir, apk.name)
        apk.copyTo(dst, overwrite = true)
        OverlayLog.d("restore: 已导出到 ${dst.absolutePath}")
        dst
    } catch (e: Throwable) {
        OverlayLog.w("restore: 失败 ${e}")
        null
    }

    /** 是否存在可还原的备份。 */
    fun hasBackup(): Boolean = latestRoot().let { it.exists() && (it.listFiles()?.isNotEmpty() == true) }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun moduleIds(kind: OverlayManager.ModuleKind): List<String> = runCatching {
        kotlinx.coroutines.runBlocking { OverlayManager.installedModuleIds(kind) }
    }.getOrDefault(emptyList())

    private fun sp(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 版本名（避免直接依赖 BuildConfig，便于单测）。 */
    private val BuildConfigVersion: String
        get() = runCatching { frb.axeron.manager.BuildConfig.VERSION_NAME }
            .getOrDefault("unknown")
}
