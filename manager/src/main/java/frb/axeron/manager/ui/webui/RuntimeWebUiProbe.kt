package frb.axeron.manager.ui.webui

import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService

/**
 * 运行时模块 WebUI 探测。
 *
 * 运行时模块目录位于 shell 数据目录（/data/user_de/0/com.android.shell/axeron/
 * runtime_plugins/<id>），App 进程无法直接 stat，必须用 shell 权限探测。
 *
 * 这里刻意不复用 RuntimeModuleDetector.probePath：
 *  - 那个带进程内缓存，WebUIActivity 是独立 Activity，缓存生命周期不匹配；
 *  - 那个在 features.runtime 包，UI 层引用会引入不必要的耦合。
 * 本对象保持最小实现，单独放 UI 层。
 */
internal object RuntimeWebUiProbe {

    /**
     * 判断给定绝对路径（普通文件）是否存在，以 shell 权限执行 test -f。
     */
    fun exists(path: String): Boolean = try {
        val r = kotlinx.coroutines.runBlocking {
            AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("/system/bin/sh", "-c", "test -f '" + path + "' && echo YES"),
                env = Axeron.getEnvironment(),
                timeoutMs = 3_000L,
            )
        }
        r.stdout.contains("YES")
    } catch (_: Throwable) {
        false
    }
}