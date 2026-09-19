package frb.axeron.manager.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import frb.axeron.api.core.AxeronSettings
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
     * 设备所有者保活加固开关。
     *
     * 仅当本应用是 Device Owner 时才生效；非 DO 设备上打开开关不会报错，
     * 但加固动作会被 [frb.axeron.manager.owner.DeviceOwnerKeepAlive] 静默跳过。
     */
    fun setDoKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            isDoKeepAliveEnabled = enabled
            AxeronSettings.setEnableDoKeepAlive(enabled)
            if (enabled) {
                // 立即应用一次，用户不必等到下次开机。
                frb.axeron.manager.owner.DeviceOwnerKeepAlive.apply(
                    context = getApplication(),
                )
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