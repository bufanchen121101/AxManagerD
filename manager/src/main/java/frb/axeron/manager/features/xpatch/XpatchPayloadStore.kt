package frb.axeron.manager.features.xpatch

import android.content.Context
import android.net.Uri
import android.util.Log
import org.lsposed.lspatch.share.AssetSource
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 【便捷版】修补核心资源的私有目录存储。
 *
 * 完整版把 metaloader.dex / loader.dex / so / public.xml 等直接打进 APK 的 assets；
 * 便捷版为了瘦身不带这些资源，改由用户安装「Xposed 修补核心」模块提供：
 *  - 模块 ZIP 内约定用 `xpayload/` 目录承载资源，其内部结构与 APK 的 assets 一一对应
 *    （如 `xpayload/lspatch/loader.dex` → APK 里的 `assets/lspatch/loader.dex`）；
 *  - 安装该模块时由 [extractFromZip] 把 `xpayload/` 目录解压到本 App 私有目录
 *    `filesDir/xpatch/`（不需要 root / Shizuku 读系统目录）；
 *  - 打补丁时由 [installProvider] 注入的 Provider 把 `assets/xxx` 映射到该私有目录。
 *
 * 未安装模块时 [isReady] 为 false，UI 会提示用户安装模块；此时 Provider 一律返回 null，
 * 资源读取回退到 APK 自带 assets（便捷版里本就为空），最终由调用方抛出「资源缺失」。
 */
object XpatchPayloadStore {

    private const val TAG = "XpatchPayload"

    /** 模块 ZIP 内承载修补资源的目录名（与 module.prop 同级）。 */
    const val PAYLOAD_DIR_IN_ZIP = "xpayload"

    /** 私有目录名：filesDir/xpatch。 */
    private const val DIR_NAME = "xpatch"

    /**
     * 打补丁必需资源（相对 xpatch 根目录，即「去掉 assets/ 前缀后的 APK 内路径」）。
     *
     * 不含 `lspatch.jar`：全仓已确认没有任何代码读取它，无需随模块分发。
     */
    private val REQUIRED = listOf(
        "lspatch/metaloader.dex",
        "lspatch/loader.dex",
        // 四架构 so 缺一不可：ApkPatcher 会为每个架构各写一份进目标 APK
        "lspatch/so/arm64-v8a/liblspatch.so",
        "lspatch/so/armeabi-v7a/liblspatch.so",
        "lspatch/so/x86/liblspatch.so",
        "lspatch/so/x86_64/liblspatch.so",
        "public.xml",
    )

    @Volatile
    private var providerInstalled = false

    /** 资源目录：filesDir/xpatch。 */
    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 修补核心是否已就绪（用于 UI 判断是否提示「需安装模块」）。 */
    fun isReady(context: Context): Boolean {
        val root = dir(context)
        return REQUIRED.all { rel ->
            val f = File(root, rel)
            f.isFile && f.length() > 0L
        }
    }

    /** 缺失的必需资源列表（用于提示文案 / 排查）。 */
    fun missing(context: Context): List<String> {
        val root = dir(context)
        return REQUIRED.filter { rel ->
            val f = File(root, rel)
            !f.isFile || f.length() == 0L
        }
    }

    /**
     * 注入资源来源。只应在便捷版调用一次（Application 启动时）。
     * Provider 内只认自家私有目录，读不到就返回 null，交回 [AssetSource] 回退。
     */
    fun installProvider(context: Context) {
        if (providerInstalled) return
        providerInstalled = true
        val app = context.applicationContext
        AssetSource.setProvider { assetPath -> openAsset(app, assetPath) }
        Log.i(TAG, "provider installed, ready=${isReady(app)}")
    }

    /** 把 `assets/xxx` 映射为私有目录里的 `xxx`；没有则返回 null。 */
    private fun openAsset(context: Context, assetPath: String): InputStream? {
        val prefix = "assets/"
        if (!assetPath.startsWith(prefix)) return null
        val rel = assetPath.substring(prefix.length)
        if (rel.isEmpty() || rel.contains("..")) return null
        val f = File(dir(context), rel)
        if (!f.isFile || f.length() == 0L) return null
        return runCatching { FileInputStream(f) as InputStream }.getOrNull()
    }

    /** 待安装模块是否携带修补核心资源（安装弹窗预检用）。 */
    fun hasPayloadInZip(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.trimStart('.', '/', '\\')
                    if (!entry.isDirectory && name.startsWith("$PAYLOAD_DIR_IN_ZIP/")) {
                        return@use true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                false
            }
        } ?: false
    }.getOrDefault(false)

    /**
     * 从模块 ZIP 解压 `xpayload/` 目录到私有目录。
     *
     * @return 是否至少解压出一个文件；ZIP 里没有 `xpayload/` 时返回 false（不影响模块安装）。
     */
    fun extractFromZip(context: Context, uri: Uri): Boolean = runCatching {
        val root = dir(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                var found = false
                var count = 0
                while (entry != null) {
                    val name = entry.name.trimStart('.', '/', '\\')
                    val prefix = "$PAYLOAD_DIR_IN_ZIP/"
                    if (!entry.isDirectory && name.startsWith(prefix)) {
                        val rel = name.substring(prefix.length)
                        if (rel.isNotEmpty() && !rel.contains("..")) {
                            val dst = File(root, rel)
                            dst.parentFile?.mkdirs()
                            FileOutputStream(dst).use { out -> zip.copyTo(out) }
                            found = true
                            count++
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                if (found) Log.i(TAG, "extracted $count files -> ${root.absolutePath}")
                found
            }
        } ?: false
    }.getOrElse {
        Log.w(TAG, "extract payload failed", it)
        false
    }

    /** 清空私有目录里的修补核心（卸载模块时调用）。 */
    fun clear(context: Context): Boolean = runCatching {
        dir(context).deleteRecursively()
    }.getOrDefault(false)
}