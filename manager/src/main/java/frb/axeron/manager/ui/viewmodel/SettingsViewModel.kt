package frb.axeron.manager.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import frb.axeron.api.core.AxeronSettings
import frb.axeron.manager.features.keepalive.KeepAliveService
import frb.axeron.manager.util.PortHelper
import frb.axeron.manager.ui.theme.basePrimaryDefault
import frb.axeron.manager.ui.theme.toHexString
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
//    private val prefs = AxeronSettings.getPreferences()

    val themeOptions = listOf("Follow System", "Dark Theme", "Light Theme")

    var isIgniteWhenRelogEnabled by mutableStateOf(
        AxeronSettings.getEnableIgniteRelog()
    )
        private set

    var isActivateOnBootEnabled by mutableStateOf(
        AxeronSettings.getStartOnBoot()
    )
        private set

    var isTcpModeEnabled by mutableStateOf(
        AxeronSettings.getTcpMode()
    )
        private set

    var tcpPortInt: Int? by mutableStateOf(
        AxeronSettings.getTcpPort()
    )
        private set

    var isDynamicColorEnabled by mutableStateOf(
        AxeronSettings.getEnableDynamicColor()
    )
        private set

    var getAppThemeId by mutableIntStateOf(
        AxeronSettings.getAppThemeId()
    )
        private set

    var isDeveloperModeEnabled by mutableStateOf(
        AxeronSettings.getEnableDeveloperOptions()
    )
        private set

    var isWebDebuggingEnabled by mutableStateOf(
        AxeronSettings.getEnableWebDebugging()
    )
        private set
    var isKeepAliveEnabled by mutableStateOf(
        AxeronSettings.getEnableKeepAlive()
    )
        private set

    /** 设备所有者保活加固（电池白名单等）；非 DO 设备上无效。 */
    var isDoKeepAliveEnabled by mutableStateOf(
        AxeronSettings.getEnableDoKeepAlive()
    )
        private set

    var isBootStartEnabled by mutableStateOf(
        AxeronSettings.getBootStartService()
    )
        private set

    // fungsi toggle / set manual

    fun setIgniteWhenRelog(enabled: Boolean) {
        viewModelScope.launch {
            isIgniteWhenRelogEnabled = enabled
            AxeronSettings.setEnableIgniteRelog(enabled)
        }
    }

    fun setActivateOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            isActivateOnBootEnabled = enabled
            AxeronSettings.setStartOnBoot(enabled)
        }
    }

    fun setTcpMode(enabled: Boolean) {
        viewModelScope.launch {
            isTcpModeEnabled = enabled
            AxeronSettings.setTcpMode(enabled)
        }
    }

    fun setTcpPort(port: Int?) {
        viewModelScope.launch {
            tcpPortInt = port
            AxeronSettings.setTcpPort(port)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            isDynamicColorEnabled = enabled
            AxeronSettings.setEnableDynamicColor(enabled)
        }
    }



    fun setAppTheme(themeId: Int) {
        viewModelScope.launch {
            getAppThemeId = themeId
            AxeronSettings.setAppThemeId(themeId)
        }
    }

    fun setDeveloperOptions(enabled: Boolean) {
        viewModelScope.launch {
            isDeveloperModeEnabled = enabled
            AxeronSettings.setEnableDeveloperOptions(enabled)
        }
    }

    fun setWebDebugging(enabled: Boolean) {
        viewModelScope.launch {
            isWebDebuggingEnabled = enabled
            AxeronSettings.setEnableWebDebugging(enabled)
        }
    }
    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            isKeepAliveEnabled = enabled
            AxeronSettings.setEnableKeepAlive(enabled)
        }
    }
    /**
     * 「保活通知」开关（v1.3.1：由原「设备所有者保活加固」**改名并替换功能**）。
     *
     * 打开时：
     *   ① 把「后台保活」总开关一并打开 —— 两者是同一套机制的「总闸」与
     *      「可操作的通知」两个面。只开通知而不允许保活，会出现
     *      「通知在、重启后却不再保活」的自相矛盾状态；
     *   ② 立即启动 [KeepAliveService]，消息栏马上出现一条可展开、
     *      带「停止」按钮的保活通知 —— 用户随时能从通知里结束保活。
     *
     * 关闭时：只把通知重建成普通状态通知（去掉展开区的「停止」按钮）。
     * 是否继续保活交给「后台保活」开关，本开关不负责停服务。
     *
     * 注意：DO 保活加固（电池白名单等）已不再挂在开关上，而是由
     * [KeepAliveService.onCreate] 在服务启动时自动尝试（非 DO 设备静默跳过），
     * 因此这里去掉旧的 `DeviceOwnerKeepAlive.apply` 调用**不会**丢功能。
     */
    fun setDoKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            isDoKeepAliveEnabled = enabled
            AxeronSettings.setEnableDoKeepAlive(enabled)
            val app: Application = getApplication()
            if (enabled) {
                if (!AxeronSettings.getEnableKeepAlive()) {
                    AxeronSettings.setEnableKeepAlive(true)
                    isKeepAliveEnabled = true
                }
                KeepAliveService.refreshNotification(app)
            } else if (KeepAliveService.isRunning(app)) {
                // 服务已在运行：只需重建通知，让它退回「不带停止按钮」的普通形态。
                KeepAliveService.refreshNotification(app)
            }
        }
    }


    /**
     * 「设备所有者自启动」开关（v1.6.2）。
     *
     * 打开时：
     *   ① 若尚未分配固定端口，则生成一个安全端口并持久化；
     *   ② 同步打开 TCP 模式并把端口写进去 —— 这样下一次激活时
     *      [frb.axeron.manager.adb.AdbStarter.startAdbClient] 会执行 `tcpip:<port>`，
     *      把 adbd 固定切到该端口；此后每次重启 adbd 都监听这个端口，
     *      用户进软件点「激活」即可直接连它，无需再靠 mDNS 搜索。
     *
     * 关闭时：清掉端口与开关状态（TCP 模式留给用户自行决定）。
     */
    fun setBootStart(enabled: Boolean) {
        viewModelScope.launch {
            isBootStartEnabled = enabled
            AxeronSettings.setBootStartService(enabled)
            if (enabled) {
                var port = AxeronSettings.getBootStartPort()
                if (port !in 1..65535) {
                    port = PortHelper.generateSafeRandomPort()
                    if (port > 0) {
                        AxeronSettings.setBootStartPort(port)
                    }
                }
                // Do NOT touch the global tcpMode/tcpPort here: that would hijack the
                // classic "Enable ADB and Activate" flow. The fixed port is passed
                // explicitly by the Port Auto Start button instead.
            } else {
                AxeronSettings.clearBootStartPort()
            }
        }
    }

    var customPrimaryColorHex by mutableStateOf(
        AxeronSettings.getCustomPrimaryColor() ?: basePrimaryDefault.toHexString()
    )
        private set

    fun setCustomPrimaryColor(hex: String) {
        viewModelScope.launch {
            customPrimaryColorHex = hex
            AxeronSettings.setPrimaryColor(hex)
        }
    }

    fun removeCustomPrimaryColor() {
        viewModelScope.launch {
            customPrimaryColorHex = basePrimaryDefault.toHexString()
            AxeronSettings.removePrimaryColor()
        }
    }

}