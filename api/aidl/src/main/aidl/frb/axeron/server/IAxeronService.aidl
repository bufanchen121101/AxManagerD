// IAxeronService.aidl
package frb.axeron.server;

import frb.axeron.server.IFileService;
import frb.axeron.server.IAxeronApplication;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.parcelablelist.ParcelableListSlice;
parcelable ServerInfo;
parcelable PluginInfo;
parcelable Environment;

interface IAxeronService {
    IFileService getFileService() = 2;
    ServerInfo getServerInfo() = 4;
    ParcelableListSlice<PackageInfo> getPackages(int flags) = 6;
    ParcelableListSlice<PluginInfo> getPlugins() = 7;
    PluginInfo getPluginById(in String id) = 8;
    boolean isFirstInit(boolean markAsFirstInit) = 9;
    IShizukuService getShizukuService() = 10;
    void enableShizukuService(boolean enable) = 11;
    Environment getEnvironment(int envType) = 12;
    void setNewEnvironment(in Environment env) = 13;
    String getSystemProperty(in String name, in String defaultValue) = 15;
    void setSystemProperty(in String name, in String value) = 16;
    int addUserService(in IShizukuServiceConnection conn, in Bundle args) = 17;
    int removeUserService(in IShizukuServiceConnection conn, in Bundle args) = 18;
    void requestPermission(int requestCode) = 19;
    boolean checkSelfPermission() = 20;
    boolean shouldShowRequestPermissionRationale() = 21;
    void attachApplication(in IShizukuApplication application,in Bundle args) = 22;
    IRemoteProcess newProcess(in String[] cmd, in String[] env, in String dir) = 23;
    void attachUserService(in IBinder binder, in Bundle options) = 101;
    oneway void dispatchPackageChanged(in Intent intent) = 102;
    oneway void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, in Bundle data) = 104;
    int getFlagsForUid(int uid, int mask) = 105;
    void updateFlagsForUid(int uid, int mask, int value) = 106;
    int checkPermission(String permission) = 107;
    void exit() = 16777114;
}