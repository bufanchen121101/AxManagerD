package frb.axeron.api.core;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.text.TextUtils;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.util.Locale;

import frb.axeron.api.utils.EmptySharedPreferencesImpl;


public class AxeronSettings {

    public static final String NAME = "settings";
    public static final String APP_THEME_ID = "app_theme_id";

    public static final String TCP_MODE = "tcp_mode";
    public static final String TCP_PORT = "tcp_port";
    public static final String LAUNCH_MODE = "mode";
    public static final String ENABLE_DYNAMIC_COLOR = "enable_dynamic_color";
    public static final String ENABLE_DEVELOPER_OPTIONS = "enable_developer_options";
    public static final String ENABLE_WEB_DEBUGGING = "enable_web_debugging";
    public static final String CUSTOM_PRIMARY_COLOR = "custom_primary_color";
    public static final String ENABLE_IGNITE_RELOG = "enable_ignite_relog";
    public static final String LANGUAGE = "language";
    public static final String ENABLE_START_ON_BOOT = "enable_start_on_boot";
    public static final String ENABLE_KEEP_ALIVE = "enable_keep_alive";
    /** v1.6.1：开机自动启动（无线调试预热）。独立于 enable_start_on_boot 的总开关。 */
    public static final String BOOT_START_SERVICE = "boot_start_service";
    /** v1.6.2：设备所有者自启动使用的固定 ADB 端口（开启开关时生成并持久化）。 */
    public static final String BOOT_START_PORT = "boot_start_port";
    public static final String ENABLE_MODULE_KEEP_ALIVE = "enable_module_keep_alive";

    /**
     * 模块核心文件修改（Overlay）总开关。
     *
     * 该开关由 manager 层的 OverlayPermissionStore 同时写入：
     *  - manager 自己的 SharedPreferences（overlay_settings/overlay_enabled）
     *  - 本 settings（用于 api/server 层在 shell/server 进程内读取）
     * 只有此处为 true 时，运行时模块的 overlay 才会被 ensureScripts 优先采用。
     */
    public static final String ENABLE_MODULE_OVERLAY = "enable_module_overlay";

    /** 设备所有者保活加固总开关（电池白名单等）。 */
    public static final String ENABLE_DO_KEEP_ALIVE = "enable_do_keep_alive";

    /** 禁止卸载本应用（DO 持久策略，需总开关已开）。 */
    public static final String ENABLE_DO_UNINSTALL_BLOCK = "enable_do_uninstall_block";

    private static SharedPreferences sPreferences;

    public static SharedPreferences getPreferences() {
        return sPreferences;
    }

    @NonNull
    private static Context getSettingsStorageContext(@NonNull Context context) {
        Context storageContext;
        storageContext = context.createDeviceProtectedStorageContext();

        storageContext = new ContextWrapper(storageContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                try {
                    return super.getSharedPreferences(name, mode);
                } catch (IllegalStateException e) {
                    // SharedPreferences in credential encrypted storage are not available until after user is unlocked
                    return new EmptySharedPreferencesImpl();
                }
            }
        };

        return storageContext;
    }

    public static void initialize(Context context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                    .getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    @LaunchMethod
    public static int getLastLaunchMode() {
        return getPreferences().getInt(LAUNCH_MODE, LaunchMethod.UNKNOWN);
    }

    public static void setLastLaunchMode(@LaunchMethod int method) {
        getPreferences().edit().putInt(LAUNCH_MODE, method).apply();
    }

    public static boolean getTcpMode() {
        return getPreferences().getBoolean(TCP_MODE, true);
    }

    public static void setTcpMode(boolean enable) {
        getPreferences().edit().putBoolean(TCP_MODE, enable).apply();
    }

    public static int getTcpPort() {
        try {
            var port = SystemProperties.getInt("service.adb.tcp.port", -1);
            if (port <= 0) port = SystemProperties.getInt("persist.adb.tcp.port", -1);
            if (port <= 0) port = 5555;
            return getPreferences().getInt(TCP_PORT, port);
        } catch (NumberFormatException e) {
            return 5555;
        }
    }

    public static void setTcpPort(@Nullable Integer port) {
        if (port != null) {
            getPreferences().edit().putInt(TCP_PORT, port).apply();
        } else {
            getPreferences().edit().remove(TCP_PORT).apply();
        }
    }

    // IGNITE RELOG

    public static boolean getEnableIgniteRelog() {
        return getPreferences().getBoolean(ENABLE_IGNITE_RELOG, false);
    }

    public static void setEnableIgniteRelog(boolean enable) {
        getPreferences().edit().putBoolean(ENABLE_IGNITE_RELOG, enable).apply();
    }


    // DEVELOPER MODE

    public static boolean getEnableDeveloperOptions() {
        return getPreferences().getBoolean(ENABLE_DEVELOPER_OPTIONS, false);
    }

    public static void setEnableDeveloperOptions(boolean enable) {
        getPreferences().edit().putBoolean(ENABLE_DEVELOPER_OPTIONS, enable).apply();
    }

    // WEB DEBUGGING

    public static boolean getEnableWebDebugging() {
        return getPreferences().getBoolean(ENABLE_WEB_DEBUGGING, false);
    }

    public static void setEnableWebDebugging(boolean enable) {
        getPreferences().edit().putBoolean(ENABLE_WEB_DEBUGGING, enable).apply();
    }

    // APP THEME

    public static int getAppThemeId() {
        return getPreferences().getInt(APP_THEME_ID, 0);
    }

    public static void setAppThemeId(int id) {
        getPreferences().edit().putInt(APP_THEME_ID, id).apply();
    }

    // DYNAMIC COLOR

    public static boolean getEnableDynamicColor() {
        return getPreferences().getBoolean(ENABLE_DYNAMIC_COLOR, false);
    }

    public static void setEnableDynamicColor(boolean enable) {
        getPreferences().edit().putBoolean(ENABLE_DYNAMIC_COLOR, enable).apply();
    }

    // ON BOOT

    public static boolean getStartOnBoot() {
        return getPreferences().getBoolean(ENABLE_START_ON_BOOT, true);
    }

    public static void setStartOnBoot(boolean method) {
        getPreferences().edit().putBoolean(ENABLE_START_ON_BOOT, method).apply();
    }

    // KEEP ALIVE
    public static boolean getEnableKeepAlive() {
        return getPreferences().getBoolean(ENABLE_KEEP_ALIVE, false);
    }
    public static void setEnableKeepAlive(boolean enable) {
        getPreferences().edit().putBoolean(ENABLE_KEEP_ALIVE, enable).apply();
    }
    // BOOT START (v1.6.1 wireless-debugging prewarm)
    public static boolean getBootStartService() {
        return getPreferences().getBoolean(BOOT_START_SERVICE, false);
    }

    public static void setBootStartService(boolean enable) {
        getPreferences().edit().putBoolean(BOOT_START_SERVICE, enable).apply();
    }

    // BOOT START PORT (fixed ADB TCP port for device-owner auto start)
    public static int getBootStartPort() {
        return getPreferences().getInt(BOOT_START_PORT, -1);
    }

    public static void setBootStartPort(int port) {
        getPreferences().edit().putInt(BOOT_START_PORT, port).apply();
    }

    public static void clearBootStartPort() {
        getPreferences().edit().remove(BOOT_START_PORT).apply();
    }

    // MODULE KEEP ALIVE (module process keep-alive)
    public static boolean getEnableModuleKeepAlive() {
        return getPreferences().getBoolean(ENABLE_MODULE_KEEP_ALIVE, false);
    }
    public static void setEnableModuleKeepAlive(boolean enable) {
        getPreferences().edit().putBoolean(ENABLE_MODULE_KEEP_ALIVE, enable).apply();
    }

    // MODULE OVERLAY (allow modules to override core files)

    /**
     * 模块核心文件修改（Overlay）总开关。
     *
     * 说明：真正的权限判定/授权持久化在 manager 层的 OverlayPermissionStore，
     * 这里只提供一个 api/server 层（shell 进程）可读取的镜像值，供
     * [AxeronPluginService.ensureScripts] 决定是否允许 overlay 覆盖核心脚本。
     */
    public static boolean getEnableModuleOverlay() {
        return getPreferences().getBoolean(ENABLE_MODULE_OVERLAY, false);
    }

    public static void setEnableModuleOverlay(boolean enable) {
        getPreferences().edit().putBoolean(ENABLE_MODULE_OVERLAY, enable).apply();
    }

    // DEVICE OWNER KEEP ALIVE (DO-only hardening; no-op on non-DO devices)
    public static boolean getEnableDoKeepAlive() {
        return getPreferences().getBoolean(ENABLE_DO_KEEP_ALIVE, false);
    }

    public static void setEnableDoKeepAlive(boolean enable) {
        getPreferences().edit().putBoolean(ENABLE_DO_KEEP_ALIVE, enable).apply();
    }

    // DO UNINSTALL BLOCK (persistent policy; must provide an in-app way to release)
    public static boolean getEnableDoUninstallBlock() {
        return getPreferences().getBoolean(ENABLE_DO_UNINSTALL_BLOCK, false);
    }

    public static void setEnableDoUninstallBlock(boolean enable) {
        getPreferences().edit().putBoolean(ENABLE_DO_UNINSTALL_BLOCK, enable).apply();
    }

    //PRIMARY COLOR

    @Nullable
    public static String getCustomPrimaryColor() {
        return getPreferences().getString(CUSTOM_PRIMARY_COLOR, null);
    }

    public static void setPrimaryColor(String hex) {
        getPreferences().edit().putString(CUSTOM_PRIMARY_COLOR, hex).apply();
    }

    public static void removePrimaryColor() {
        getPreferences().edit().remove(CUSTOM_PRIMARY_COLOR).apply();
    }

    public static Locale getLocale() {
        String tag = getPreferences().getString(LANGUAGE, null);
        if (TextUtils.isEmpty(tag) || "SYSTEM".equals(tag)) {
            return Locale.getDefault();
        }
        return Locale.forLanguageTag(tag);
    }

    @IntDef({
            LaunchMethod.UNKNOWN,
            LaunchMethod.ROOT,
            LaunchMethod.ADB,
            LaunchMethod.DEVICE_OWNER,
    })
    @Retention(SOURCE)
    public @interface LaunchMethod {
        int UNKNOWN = -1;
        int ROOT = 0;
        int ADB = 1;
        int DEVICE_OWNER = 2;
    }
}