package frb.axeron.manager

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import coil.Coil
import coil.ImageLoader
import com.topjohnwu.superuser.Shell
import frb.axeron.Axerish
import frb.axeron.api.AxeronPluginService
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Engine
import frb.axeron.manager.ui.util.createShellBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.zhanghai.android.appiconloader.coil.AppIconFetcher
import me.zhanghai.android.appiconloader.coil.AppIconKeyer
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

open class AxeronApplication : Engine() {
    companion object {
        lateinit var axeronApp: AxeronApplication

        init {
//            logd("ShizukuApplication", "init")
            // 【v1.9.0 闪退加固 — 高版本重点】
            // 本 init 会在**任何**访问 AxeronApplication 的进程里执行，包括
            // DeviceOwnerReceiver 在「DO 激活成功」瞬间被系统广播唤醒的后台进程。
            // 类初始化块里任何一步抛异常都会变成 ExceptionInInitializerError
            // → 进程当场闪退，现象正是用户反馈的「激活成功了，但界面突然闪退」。
            // 因此这里逐步隔离：失败只记录日志，绝不冒泡。
            runCatching { Axerish.initialize(BuildConfig.APPLICATION_ID) }
                .onFailure { android.util.Log.e("AxManager", "Axerish.initialize failed", it) }
            runCatching {
                Shell.setDefaultBuilder(createShellBuilder())
                Shell.enableLegacyStderrRedirection = true
                Shell.enableVerboseLogging = BuildConfig.DEBUG
            }.onFailure { android.util.Log.e("AxManager", "Shell init failed", it) }

            if (Build.VERSION.SDK_INT >= 28) {
                runCatching { HiddenApiBypass.setHiddenApiExemptions("") }
                    .onFailure { android.util.Log.e("AxManager", "HiddenApiBypass failed", it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { System.loadLibrary("adb") }
                    .onFailure { android.util.Log.e("AxManager", "loadLibrary(adb) failed", it) }
            }
        }
    }

    lateinit var okhttpClient: OkHttpClient

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        axeronApp = this
        AxeronSettings.initialize(axeronApp)
        // 【v1.4.6】把真实 applicationId 注入设备管理组件（修复 manages 变体
        // （frb.axerond.manages）下 DO 激活/解除/转移与 axeron-dpm 自我 DO 判定全部查错包的问题）
        runCatching { frb.axeron.manager.owner.DeviceOwnerState.init(this) }
    }

    @SuppressLint("ResourceType")
    override fun onCreate() {
        super.onCreate()

        // 【v1.9.0 闪退定位 · 高版本】
        // 目的：让真机闪退后能拿到堆栈。
        // 现状：父类 Engine.onCreate 已注册全局处理器，但只把日志写 externalCacheDir/crash.log
        //       （路径 Android/data/<pkg>/cache/），普通用户几乎无法访问，也没有 UI 入口，
        //       所以「激活成功瞬间闪退」反馈发生时，我们手里没有任何堆栈可查。
        // 做法：**包装**（而不是替换）现有处理器 —— 崩溃时先补写一份到
        //       externalFilesDir/AxManagerD/logs/crash_<时间戳>.log（与 OverlayLog 同一可取出目录），
        //       然后把异常原样交回原处理器，CrashActivity/杀进程等既有行为完全不变。
        // 隔离性：只在 App 侧追加，不改 api 模块 Engine（公共路径）。
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val dir = File(getExternalFilesDir(null), "AxManagerD/logs")
                    if (dir.exists() || dir.mkdirs()) {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(java.util.Date())
                        File(dir, "crash_$stamp.log").writeText(
                            "=== Crash at ${System.currentTimeMillis()} ===\n" +
                                    android.util.Log.getStackTraceString(throwable) + "\n"
                        )
                    }
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }

        val context = this
        val iconSize = resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components {
                    add(AppIconKeyer())
                    add(AppIconFetcher.Factory(iconSize, false, context))
                }
                .build()
        )


        okhttpClient =
            OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                // AI 推理（尤其 reasoning 模型）响应很慢，放宽读写超时，避免 10s 默认值截断请求
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "AxManager/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()

        // 注入 AI 命令分析器（方案 A：在 execWithIO/flashPlugin 执行前拦截分析）
        AxeronPluginService.commandAnalyzer = frb.axeron.manager.ai.AIEngineManager
        // 初始化运行时日志落盘（v1.3.1）：模块安装 / 特权路由 / 环境注入等关键
        // 链路日志，可在「设置 → 运行日志」中查看、复制与导出。
        runCatching { frb.axeron.api.AxeronRuntimeLog.init(context) }

        // 首次启动自动备份一份软件文件到应用专属外部目录
        // （Android/data/<pkg>/files/AxManagerD/backup/，v1.9.1 起替换原 /sdcard 路径）。
        // 放后台线程，避免阻塞冷启动；失败静默（只记日志）。
        Thread {
            runCatching { frb.axeron.manager.features.backup.BackupManager.ensureFirstBackup(context) }
                .onFailure { frb.axeron.manager.util.OverlayLog.w("首次备份异常: $it") }
        }.start()

        // Overlay 授权链路自检（排查「点击无反应 / 不弹窗」用）：
        // 打印 App 侧解析出的 axeron 路径，并实测一次 shell 执行 + 目录可见性。
        // 日志落到 /sdcard/AxManagerD/logs/overlay.log，无需 adb 即可取。
        // 注意：installedModuleIds / execProcessSafeWithTimeout 都是 suspend 函数，
        // 必须在协程上下文里调用。这里用 runBlocking 包一层（已在后台、非主线程）。
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    frb.axeron.manager.util.OverlayLog.i("========== App 启动自检 ==========")
                    val om = frb.axeron.manager.features.overlay.OverlayManager
                    om.logPaths()
                    // 【v1.9.1 修复】global.json 自愈：
                    // 用户在设置里开启过总开关、但 shell 域 perm/global.json 丢失时
                    // （真机实测会丢），模块侧 `axoverlay check` 会恒返回 denied。
                    // 这里只在「开关为开」时才补写，避免无意义的冷启动 IO。
                    runCatching {
                        val ops = frb.axeron.manager.features.overlay.OverlayPermissionStore
                        if (ops.isEnabled(context)) {
                            frb.axeron.manager.util.OverlayLog.i("启动自愈: 补写 perm/global.json")
                            ops.syncGlobalJson(context)
                        }
                    }.onFailure {
                        frb.axeron.manager.util.OverlayLog.w("启动自愈 global.json 失败: $it")
                    }
                    // 实测 shell 链路是否可用（能执行 /bin/sh 并拿到输出）
                    val probe = frb.axeron.api.AxeronPluginService.execProcessSafeWithTimeout(
                        cmd = arrayOf("/system/bin/sh", "-c", "id; echo '--- perm ---'; ls -la '${om.permDir()}' 2>&1"),
                        env = frb.axeron.api.Axeron.getEnvironment(),
                        timeoutMs = 5_000L,
                    )
                    frb.axeron.manager.util.OverlayLog.i(
                        "shell 自检: exit=${probe.exitCode} out=${probe.stdout.trim()} err=${probe.stderr.trim()}"
                    )
                    // 模块枚举自检
                    val runtime = om.installedModuleIds(frb.axeron.manager.features.overlay.OverlayManager.ModuleKind.RUNTIME)
                    val shell = om.installedModuleIds(frb.axeron.manager.features.overlay.OverlayManager.ModuleKind.SHELL)
                    frb.axeron.manager.util.OverlayLog.i("模块枚举: runtime=$runtime | shell=$shell")
                    frb.axeron.manager.util.OverlayLog.i("日志文件: ${frb.axeron.manager.util.OverlayLog.logFilePath()}")
                    frb.axeron.manager.util.OverlayLog.i("========== 自检结束 ==========")
                }
            }.onFailure {
                frb.axeron.manager.util.OverlayLog.e("启动自检异常", it)
            }
        }.start()
    }
}