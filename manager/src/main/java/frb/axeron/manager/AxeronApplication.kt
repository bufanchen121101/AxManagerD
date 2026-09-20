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
            Axerish.initialize(BuildConfig.APPLICATION_ID)
            Shell.setDefaultBuilder(createShellBuilder())
            Shell.enableLegacyStderrRedirection = true
            Shell.enableVerboseLogging = BuildConfig.DEBUG

            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                System.loadLibrary("adb")
            }
        }
    }

    lateinit var okhttpClient: OkHttpClient

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        axeronApp = this
        AxeronSettings.initialize(axeronApp)
    }

    @SuppressLint("ResourceType")
    override fun onCreate() {
        super.onCreate()

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

        // 首次启动自动备份一份软件文件到手机存储（/sdcard/AxManagerD/backup/）。
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
                frb.axeron.manager.util.OverlayLog.e("启动自检异常", it as? Throwable)
            }
        }.start()
    }
}