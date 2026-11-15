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
import okhttp3.CertificatePinner
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
        //       externalFilesDir/AxManagerD/logs/crash_<时间戳>.log（与运行日志同一可取出目录），
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
                // [harden] 证书固定(SPKI)：只作用于官方 AI 中转站域名，其它域名不受影响。
                // 故意不 pin 叶证书（Let's Encrypt 90 天轮换会直接打死客户端），
                // 而是 pin 当前链上的中间CA + ISRG 根，任一枚命中即通过。
                .certificatePinner(
                    CertificatePinner.Builder()
                        // [source-release] service host redacted to "Your link"; SPKI pin removed.
                        // Replace "Your link" with your own endpoint and add your own pins if needed.
                        .build()
                )
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


        // 【v1.4.2 修复 · 「DO 显示已授权但没有任何功能」的启动兜底】
        //
        // 背景：非 ADB 直连激活时，setActiveAdmin 先于 setDeviceOwner 执行，
        // 系统那次 onEnabled 回调里 isDeviceOwnerApp 还是 false → grantPermissions
        // 全部落空；DO 设定后系统只发 ACTION_DEVICE_OWNER_CHANGED（隐式广播），
        // manifest receiver 收不到 ⇒ 后置初始化不会自己补上。
        //
        // 这里在每次冷启动时做一次「已经是 Owner 就补 sync」：
        //   · 对已经激活但权限没授上的用户，升级后重启 App 即可自愈，无需重新激活；
        //   · sync 内部会实时查询 DPM（queryIsOwner），不会因为静态缓存过期而误判；
        //   · grantPermissions 是幂等的（重复授予同一状态无副作用）。
        // 放到后台线程，避免 binder 调用拖慢冷启动。
        runCatching {
            val app = this
            Thread {
                try {
                    if (frb.axeron.manager.owner.DeviceOwnerState.queryIsOwner(app)) {
                        frb.axeron.manager.owner.DeviceOwnerState.sync(app)
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("AxManager", "onCreate post-activation sync failed", t)
                }
            }.start()
        }.onFailure {
            android.util.Log.w("AxManager", "onCreate post-activation sync spawn failed", it)
        }
    }
}