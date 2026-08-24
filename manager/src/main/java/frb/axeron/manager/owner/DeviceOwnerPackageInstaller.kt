package frb.axeron.manager.owner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import com.rosan.dhizuku.api.Dhizuku
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device Owner（Dhizuku）专属的「保留数据卸载 + 静默安装」执行器。
 *
 * 这是 AxManagerD 自有的原创实现，实现思路参考了 InstallerX 的能力边界
 * （保留数据卸载 flag、Dhizuku binder 提升、Session 内部 binder 替换），
 * 但代码完全重写、不复制其 GPL 源码。
 *
 * 核心机制（与 [DeviceOwnerPrivilege] 用 IDevicePolicyManager$Stub 反射完全同构）：
 *  1. 通过 ServiceManager 拿到 "package" 服务的 binder；
 *  2. 用 Dhizuku.binderWrapper 把它提升为 Device Owner 身份；
 *  3. 反射 IPackageInstaller$Stub.asInterface 还原为系统真实接口；
 *  4. 调用隐藏的 uninstall / 通过 PackageInstaller Session 静默安装。
 *
 * 关键差异（相对 InstallerX 的简化）：
 *  - 卸载/安装结果用「静态 BroadcastReceiver + CountDownLatch」同步阻塞等待；
 *  - 安装统一为「单 base.apk 全量安装」(MODE_FULL_INSTALL + INSTALL_REPLACE_EXISTING)；
 *  - 安装时把 Session 的内部 mSession binder 也经 Dhizuku 提升，确保真正的 Device Owner 身份。
 */
object DeviceOwnerPackageInstaller {

    private const val TAG = "DeviceOwnerPkgInstaller"

    /** 保留数据卸载 flag（对应 InstallerX UninstallFlags.DELETE_KEEP_DATA）。 */
    private const val DELETE_KEEP_DATA = 0x00000001

    /** 安装 flag：替换已有应用。 */
    private const val INSTALL_REPLACE_EXISTING = 0x00000002

    private const val REAL_PACKAGE_INSTALLER_STUB = "android.content.pm.IPackageInstaller\$Stub"

    private const val ACTION_UNINSTALL_RESULT = "frb.axeron.manager.action.UNINSTALL_RESULT"
    private const val ACTION_INSTALL_RESULT = "frb.axeron.manager.action.INSTALL_RESULT"

    sealed class Result {
        object Success : Result()
        data class Failure(val error: String) : Result()
    }

    /**
     * 经 Dhizuku 提升为 Device Owner 身份的 IPackageInstaller 代理对象。
     * @return 失败返回 null。
     */
    private fun resolvePackageInstaller(context: Context): Any? {
        return try {
            // 1. Dhizuku 初始化 + 鉴权
            var inited = runCatching { Dhizuku.init(context) }.getOrDefault(false)
            if (!inited) {
                Dhizuku.reset()
                inited = runCatching { Dhizuku.init(context) }.getOrDefault(false)
                if (!inited) {
                    Log.w(TAG, "Dhizuku not available")
                    return null
                }
            }
            if (!Dhizuku.isPermissionGranted()) {
                Log.w(TAG, "Dhizuku permission not granted")
                return null
            }

            // 2. 拿 "package" 服务的原始 binder
            val pmBinder = ServiceManager.getService("package") ?: return null

            // 3. 反射拿到 IPackageManager$Stub 以获取其 packageInstaller 子 binder
            val iPackageManager = Class.forName("android.content.pm.IPackageManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, pmBinder) ?: return null

            // 反射调用 packageInstaller 字段（IPackageManager.packageInstaller 是 getter）
            val pkgInstallerBinder = try {
                val getter = iPackageManager.javaClass.getMethod("getPackageInstaller")
                getter.invoke(iPackageManager)
            } catch (e: NoSuchMethodException) {
                val field = iPackageManager.javaClass.getDeclaredField("packageInstaller")
                field.isAccessible = true
                field.get(iPackageManager)
            } as? IBinder ?: return null

            // 4. 经 Dhizuku 提升
            val wrapped = Dhizuku.binderWrapper(pkgInstallerBinder)

            // 5. 还原为 IPackageInstaller 代理
            Class.forName(REAL_PACKAGE_INSTALLER_STUB)
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, wrapped)
        } catch (e: Exception) {
            Log.w(TAG, "resolvePackageInstaller failed", e)
            null
        }
    }

    /**
     * 保留数据卸载指定应用，并阻塞等待卸载结果。
     *
     * 等价于：IPackageInstaller.uninstall(versionedPackage, callerPackageName, DELETE_KEEP_DATA, sender, userId)
     */
    fun uninstallKeepData(context: Context, packageName: String): Result {
        return try {
            val installer = resolvePackageInstaller(context) ?: return Result.Failure("Device Owner not active")

            val callerPackageName = Dhizuku.getOwnerPackageName()
            val userId = android.os.Process.myUid() / 100000
            val versionedPackage = VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST)

            val uninstallMethod = findUninstallMethod(installer)
                ?: return Result.Failure("uninstall method not found")

            // 构造本地阻塞 receiver，等待 system_server 通过 IntentSender 回调卸载结果
            val local = LocalIntentReceiver(context, ACTION_UNINSTALL_RESULT)

            invokeUninstall(installer, uninstallMethod, versionedPackage, callerPackageName, local.intentSender, userId)

            // 阻塞等待结果（最多 60s）
            val status = local.awaitResult()
            local.unregister(context)
            if (status == PackageInstaller.STATUS_SUCCESS) {
                Result.Success
            } else {
                Result.Failure(local.resultMessage ?: "uninstall failed (status $status)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "uninstallKeepData failed", e)
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun findUninstallMethod(installer: Any): Method? {
        return installer.javaClass.methods.firstOrNull { m ->
            m.name == "uninstall" && m.parameterTypes.size >= 4
        }
    }

    private fun invokeUninstall(
        installer: Any,
        method: Method,
        versionedPackage: VersionedPackage,
        callerPackageName: String,
        sender: IntentSender?,
        userId: Int,
    ) {
        val paramTypes = method.parameterTypes
        when {
            paramTypes.size >= 5 -> {
                // uninstall(VersionedPackage, String, int, IntentSender, int)
                method.invoke(
                    installer,
                    versionedPackage,
                    callerPackageName,
                    DELETE_KEEP_DATA,
                    sender,
                    userId,
                )
            }
            paramTypes.size == 4 -> {
                // uninstall(VersionedPackage, String, int, IntentSender)
                method.invoke(
                    installer,
                    versionedPackage,
                    callerPackageName,
                    DELETE_KEEP_DATA,
                    sender,
                )
            }
            else -> throw IllegalStateException("unsupported uninstall signature")
        }
    }

    /**
     * 静默安装 APK（使用 PackageInstaller Session，替换已存在的应用），阻塞等待结果。
     *
     * 关键：session 内部 mSession binder 必须经 Dhizuku 提升，
     * 否则 commit 时系统认为调用方是普通 app、抛 SecurityException。
     */
    fun installApk(
        context: Context,
        apkFile: File,
        packageName: String,
    ): Result {
        return try {
            // 1. 经 Dhizuku 提升的 IPackageInstaller 代理（用于 createSession）
            val installer = resolvePackageInstaller(context) ?: return Result.Failure("Device Owner not active")

            // 2. 构造 SessionParams：MODE_FULL_INSTALL + INSTALL_REPLACE_EXISTING
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            runCatching {
                val field = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
                field.isAccessible = true
                field.setInt(params, field.getInt(params) or INSTALL_REPLACE_EXISTING)
            }
            params.setAppPackageName(packageName)

            // 3. 反射调用 createSession（走提升后的 installer，确保 system_server 以 DO 身份创建）
            val createMethod = installer.javaClass.methods.firstOrNull {
                it.name == "createSession" && it.parameterTypes.size >= 1
            } ?: return Result.Failure("createSession not found")

            val sessionId = createMethod.invoke(installer, params) as? Int
                ?: return Result.Failure("createSession returned null")

            // 4. 用系统公开 API 打开 session，再经 Dhizuku 提升其内部 mSession binder
            val packageInstaller = context.packageManager.packageInstaller
            val session = packageInstaller.openSession(sessionId)
            elevateSession(session)

            var result: Result = Result.Success
            session.use { s ->
                apkFile.inputStream().use { input ->
                    s.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        s.fsync(output)
                    }
                }
                // 5. 本地阻塞 receiver + commit
                val local = LocalIntentReceiver(context, ACTION_INSTALL_RESULT)
                s.commit(local.intentSender)

                val status = local.awaitResult()
                local.unregister(context)
                if (status != PackageInstaller.STATUS_SUCCESS) {
                    result = Result.Failure(local.resultMessage ?: "install failed (status $status)")
                }
            }

            result
        } catch (e: Exception) {
            Log.w(TAG, "installApk failed", e)
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 把 PackageInstaller.Session 内部的 mSession binder 经 Dhizuku 提升。
     *
     * InstallerX 用的是 `IPackageInstallerSession$Stub` 直接 asInterface 后再替换，
     * 这里沿用同样的思路：读取 session 的 mSession(IBinder) 字段 → Dhizuku.binderWrapper
     * → 用系统真实 Stub.asInterface 还原 → 写回字段，规避 native crash / ClassCastException。
     */
    private fun elevateSession(session: PackageInstaller.Session) {
        val field = PackageInstaller.Session::class.java.getDeclaredField("mSession")
        field.isAccessible = true
        val raw = field.get(session) as? IBinder ?: return
        val wrapped = Dhizuku.binderWrapper(raw)
        val stub = Class.forName("android.content.pm.IPackageInstallerSession\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped)
        field.set(session, stub)
    }

    /**
     * 提取目标应用的 APK 源文件（base.apk）。
     *
     * 用于「版本调大」或注入前的源 APK 获取。从 packageInfo.sourceDir 读回。
     * 对已安装的 split APK 场景，这里只取 base.apk（主 APK）。
     * @return APK 源文件，失败返回 null。
     */
    fun extractTargetApk(context: Context, packageName: String): File? {
        return try {
            val pi = context.packageManager.getPackageInfo(packageName, 0)
            val sourceDir = pi.applicationInfo?.sourceDir ?: return null
            val src = File(sourceDir)
            if (!src.exists() || !src.isFile) return null

            // 复制到 cache，避免直接读 /data/app 下的只读源在 lspatch 输出时权限问题
            val outDir = File(context.cacheDir, "target-apks")
            outDir.mkdirs()
            val out = File(outDir, "$packageName-base.apk")
            src.inputStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "extractTargetApk failed", e)
            null
        }
    }

    // ---------- 本地阻塞式 IntentSender receiver ----------

    /**
     * 一个一次性的动态注册 BroadcastReceiver，接收 system_server 通过 PendingIntent 回传的
     * 卸载/安装结果，并用 [CountDownLatch] 阻塞等待调用方。
     */
    private class LocalIntentReceiver(
        context: Context,
        action: String,
    ) {
        private val latch = CountDownLatch(1)
        var resultStatus: Int = PackageInstaller.STATUS_FAILURE
            private set
        var resultMessage: String? = null
            private set

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                resultStatus = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
                resultMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                latch.countDown()
            }
        }

        private val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val intentSender: IntentSender
            get() = pendingIntent.intentSender

        init {
            context.registerReceiver(receiver, IntentFilter(action))
        }

        /** 阻塞等待结果，返回 status（默认超时 60s）。 */
        fun awaitResult(timeoutSec: Long = 60): Int {
            latch.await(timeoutSec, TimeUnit.SECONDS)
            return resultStatus
        }

        fun unregister(context: Context) {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}