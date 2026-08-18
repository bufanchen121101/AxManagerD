package frb.axeron.server

import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemProperties
import android.system.Os
import frb.axeron.server.api.FileServiceHolder
import frb.axeron.server.api.ShizukuIntercept
import frb.axeron.server.util.Logger
import frb.axeron.server.util.OsUtils
import frb.axeron.server.util.UserHandleCompat
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import frb.axeron.shared.ShizukuApiConstant.ATTACH_APPLICATION_API_VERSION
import frb.axeron.shared.ShizukuApiConstant.ATTACH_APPLICATION_PACKAGE_NAME
import frb.axeron.shared.ShizukuApiConstant.BINDER_TRANSACTION_transact
import frb.axeron.shared.ShizukuApiConstant.SHIZUKU_SERVER_VERSION
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuServiceConnection
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.parcelablelist.ParcelableListSlice
import rikka.rish.RishConfig
import rikka.rish.RishService
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.Collections
import kotlin.system.exitProcess

abstract class Service<UserServiceMgr : UserServiceManager,
        ClientMgr : ClientManager<ConfigMgr>,
        ConfigMgr : ConfigManager> : IAxeronService.Stub(), ShizukuIntercept {

    var userServiceManager: UserServiceMgr
    var configManager: ConfigMgr
    var clientManager: ClientMgr

    var rishService: RishService

    val environmentManager: EnvironmentManager by lazy {
        EnvironmentManager(isRoot)
    }

    abstract fun onCreateUserServiceManager(): UserServiceMgr
    abstract fun onCreateClientManager(): ClientMgr
    abstract fun onCreateConfigManager(): ConfigMgr

    companion object {
        protected const val TAG: String = "AxeronService"

        @JvmStatic
        protected val isRoot = Os.getuid() == 0

        @JvmStatic
        protected val LOGGER: Logger = Logger(TAG)

        @JvmStatic
        val mainHandler by lazy {
            Handler(Looper.getMainLooper())
        }
    }

    init {
        RishConfig.init(AxeronApiConstant.server.BINDER_DESCRIPTOR, 30000)
        userServiceManager = onCreateUserServiceManager()
        configManager = onCreateConfigManager()
        clientManager = onCreateClientManager()
        rishService = object : RishService() {
            override fun enforceCallingPermission(func: String) {
                this@Service.enforceCallingPermission(func)
            }
        }
    }

    abstract fun checkCallerManagerPermission(
        func: String?,
        callingUid: Int,
        callingPid: Int
    ): Boolean

    fun enforceManagerPermission(func: String) {
        val callingUid = getCallingUid()
        val callingPid = getCallingPid()

        if (callingPid == Os.getpid()) {
            return
        }

        if (checkCallerManagerPermission(func, callingUid, callingPid)) {
            return
        }

        val msg = ("Permission Denial: " + func + " from pid="
                + getCallingPid()
                + " is not manager ")
        LOGGER.w(msg)
        throw SecurityException(msg)
    }

    abstract fun checkCallerPermission(
        func: String?,
        callingUid: Int,
        callingPid: Int,
        clientRecord: ClientRecord?
    ): Boolean

    @Synchronized
    fun enforceCallingPermission(func: String) {
        val callingUid = getCallingUid()
        val callingPid = getCallingPid()

        if (callingUid == OsUtils.getUid()) {
            return
        }

        val clientRecord: ClientRecord? = clientManager.findClient(callingUid, callingPid)

        if (checkCallerPermission(func, callingUid, callingPid, clientRecord)) {
            return
        }

        if (clientRecord == null) {
            val msg = ("Permission Denial: " + func + " from pid="
                    + getCallingPid()
                    + " is not an attached client")
            LOGGER.w(msg)
            throw SecurityException(msg)
        }

        if (!clientRecord.allowed) {
            val msg = ("Permission Denial: " + func + " from pid="
                    + getCallingPid()
                    + " requires permission")
            LOGGER.w(msg)
            throw SecurityException(msg)
        }
    }

    @Synchronized
    @Throws(RemoteException::class)
    override fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        enforceCallingPermission("transactRemote")

        val targetBinder = data.readStrongBinder()
        val targetCode = data.readInt()
        val targetFlags: Int

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()
        val clientRecord: ClientRecord? =
            clientManager.findClient(callingUid, callingPid)

        targetFlags = if (clientRecord != null && clientRecord.apiVersion >= 13) {
            data.readInt()
        } else {
            flags
        }

        LOGGER.d(
            "transact: uid=%d, descriptor=%s, code=%d",
            getCallingUid(),
            targetBinder.interfaceDescriptor,
            targetCode
        )
        val newData = Parcel.obtain()
        try {
            newData.appendFrom(data, data.dataPosition(), data.dataAvail())
        } catch (tr: Throwable) {
            LOGGER.w(tr, "appendFrom")
            return
        }
        try {
            val id = clearCallingIdentity()
            targetBinder.transact(targetCode, newData, reply, targetFlags)
            restoreCallingIdentity(id)
        } finally {
            newData.recycle()
        }
    }

    override fun getSystemProperty(name: String?, defaultValue: String?): String {
        enforceCallingPermission("getSystemProperty")

        try {
            return SystemProperties.get(name, defaultValue)
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    override fun setSystemProperty(name: String?, value: String?) {
        enforceCallingPermission("setSystemProperty")

        try {
            SystemProperties.set(name, value)
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    override fun removeUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int {
        enforceCallingPermission("removeUserService")

        return userServiceManager.removeUserService(conn, args)
    }

    override fun addUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int {
        enforceCallingPermission("addUserService")

        LOGGER.i("addUserService: uid=%d", getCallingUid())

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()
        val callingApiVersion: Int

        val clientRecord: ClientRecord? =
            clientManager.findClient(callingUid, callingPid)
        callingApiVersion = clientRecord?.apiVersion ?: SHIZUKU_SERVER_VERSION
        return userServiceManager.addUserService(conn, args, callingApiVersion)
    }

    override fun attachUserService(binder: IBinder?, options: Bundle) {
        enforceManagerPermission("attachUserService")
        userServiceManager.attachUserService(binder, options)
    }

    override fun checkSelfPermission(): Boolean {
        val callingUid = getCallingUid()
        val callingPid = getCallingPid()



        if (callingUid == 0 || callingUid == 2000) return true

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return true
        }

        val granted = configManager.find(callingUid)?.isAllowed ?: false

        LOGGER.i("checkSelfPermission: uid=%d, pid=%d, granted=%s", callingUid, callingPid, granted)

        return granted
    }

    override fun requestPermission(requestCode: Int) {
        val callingUid = getCallingUid()
        val callingPid = getCallingPid()
        val userId = UserHandleCompat.getUserId(callingUid)

        LOGGER.i("requestPermission: uid=%d, pid=%d", callingUid, callingPid)

        if (callingUid == 0 || callingUid == 2000) return

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return
        }

        val clientRecord: ClientRecord? =
            clientManager.findClient(callingUid, callingPid)

        if (clientRecord != null && clientRecord.allowed) {
            clientRecord.dispatchRequestPermissionResult(requestCode, true)
            return
        }

        LOGGER.i("requestPermission1: uid=%d, pid=%d", callingUid, callingPid)

        val entry: ConfigPackageEntry? = configManager.find(callingUid)
        if (clientRecord != null && entry != null && entry.isAllowed()) {
            clientRecord.dispatchRequestPermissionResult(requestCode, true)
            return
        }

        LOGGER.i("requestPermission2: uid=%d, pid=%d", callingUid, callingPid)

        showPermissionConfirmation(requestCode, clientRecord, callingUid, callingPid, userId)
    }

    abstract fun showPermissionConfirmation(
        requestCode: Int,
        clientRecord: ClientRecord?,
        callingUid: Int,
        callingPid: Int,
        userId: Int
    )

    //if true they need to request permission manually
    override fun shouldShowRequestPermissionRationale(): Boolean {
        LOGGER.i(
            "shouldShowRequestPermissionRationale: uid=%d, pid=%d",
            getCallingUid(),
            getCallingPid()
        )

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return true
        }

        val clientRecord = clientManager.requireClient(callingUid, callingPid)

        if (!clientRecord.allowed) {
            return false
        }

        val entry: ConfigPackageEntry? = configManager.find(callingUid)
        return entry != null && entry.isAllowed
    }

    private var firstInitFlag = true

    override fun getFileService(): IFileService? {
        return FileServiceHolder()
    }

    @Throws(RemoteException::class)
    override fun getPackages(flags: Int): ParcelableListSlice<PackageInfo?>? {
        val list = PackageManagerApis.getInstalledPackagesNoThrow(flags.toLong(), 0)
        LOGGER.i(TAG, "getPackages: " + list.size)
        return ParcelableListSlice<PackageInfo?>(list)
    }

    @Throws(RemoteException::class)
    override fun getPlugins(): ParcelableListSlice<PluginInfo?>? {
        val pluginsPath =
            PathHelper.getWorkingPath(isRoot, AxeronApiConstant.folder.PARENT_PLUGIN).absolutePath
        val backupPath =
            PathHelper.getWorkingPath(isRoot, AxeronApiConstant.folder.PARENT_PLUGIN_BACKUP).absolutePath
        val plugins = readAllPlugin(pluginsPath, isBackup = false)
        plugins.addAll(readAllPlugin(backupPath, isBackup = true))
        return ParcelableListSlice<PluginInfo?>(plugins)
    }

    @Throws(RemoteException::class)
    override fun getPluginById(id: String): PluginInfo? {
        val dir =
            File(
                PathHelper.getWorkingPath(
                    isRoot,
                    AxeronApiConstant.folder.PARENT_PLUGIN
                ).absolutePath, id
            )
        return getPluginByDir(dir, false)
    }

    override fun isFirstInit(markAsFirstInit: Boolean): Boolean {
        val firstInitFlag = this.firstInitFlag
        if (markAsFirstInit) {
            this.firstInitFlag = false
        }
        return firstInitFlag
    }

    private fun readAllPlugin(pluginsDirPath: String, isBackup: Boolean = false): MutableList<PluginInfo?> {
        val pluginsDir = File(pluginsDirPath)
        val result: MutableList<PluginInfo?> = ArrayList()
        if (!pluginsDir.exists() || !pluginsDir.isDirectory()) return result

        val subDirs = pluginsDir.listFiles { obj: File? -> obj!!.isDirectory() }
        if (subDirs == null) return result

        for (dir in subDirs) {
            val pluginInfo: PluginInfo = getPluginByDir(dir, isBackup) ?: continue
            result.add(pluginInfo)
        }

        return result
    }

    private fun getPluginByDir(dir: File, isBackup: Boolean = false): PluginInfo? {
        if (!dir.isDirectory) return null

        val propFile = File(dir, "module.prop")
        val moduleProp = if (propFile.exists() && propFile.isFile) readFileProp(propFile) else null
        if (moduleProp == null) return null

        val pluginId = moduleProp.id
        val updateDir =
            File(
                PathHelper.getWorkingPath(isRoot, AxeronApiConstant.folder.PARENT_PLUGIN_UPDATE),
                pluginId
            )
        val updateFiles = updateDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val isUpdate = updateFiles.isNotEmpty()

        // List semua file/folder di plugin dir
        val dirFiles = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        val pluginInfo: MutableMap<String, Any?> = HashMap()

        pluginInfo["prop"] = moduleProp.toMap()
        pluginInfo["update"] = isUpdate
        pluginInfo["update_install"] = "update_install" in updateFiles
        pluginInfo["update_remove"] = "update_remove" in updateFiles
        pluginInfo["update_enable"] = "update_enable" in updateFiles
        pluginInfo["update_disable"] = "update_disable" in updateFiles

        pluginInfo["enabled"] = "disable" !in dirFiles
        pluginInfo["remove"] = "remove" in dirFiles
        pluginInfo["action"] = "action.sh" in dirFiles
        pluginInfo["web"] = "webroot" in dirFiles && File(dir, "webroot/index.html").exists()
        pluginInfo["size"] = getFolderSize(dir)
        pluginInfo["backup"] = isBackup
        pluginInfo["dir_id"] = pluginId

        return ParcelableMapJson.fromMap<PluginInfo>(Collections.unmodifiableMap(pluginInfo))
    }


    private fun getFolderSize(folder: File?): Long {
        var length: Long = 0

        if (folder != null && folder.exists()) {
            val files = folder.listFiles()
            if (files != null) {
                for (file in files) {
                    length += if (file.isFile()) {
                        file.length() // ukuran file
                    } else {
                        getFolderSize(file) // rekursif ke subfolder
                    }
                }
            }
        }

        return length
    }

    private fun readFileProp(file: File): ModuleProp? {
        val map: MutableMap<String?, Any?> = java.util.HashMap<String?, Any?>()

        try {
            BufferedReader(FileReader(file)).use { br ->
                var line: String?
                while ((br.readLine().also { line = it }) != null) {
                    if (line!!.trim { it <= ' ' }.isEmpty() || line.trim { it <= ' ' }
                            .startsWith("#")) continue

                    val parts: Array<String?> =
                        line.split("=".toRegex(), limit = 2).toTypedArray()
                    if (parts.size == 2) {
                        val key = parts[0]!!.trim { it <= ' ' }
                        val rawValue = parts[1]!!.trim { it <= ' ' }

                        val value: Any = when {
                            // Boolean
                            rawValue.equals("true", ignoreCase = true) -> true
                            rawValue.equals("false", ignoreCase = true) -> false

                            // Integer/Long (tidak diawali 0 kecuali 0 sendiri)
                            rawValue.matches(Regex("0|[1-9]\\d*")) -> {
                                try {
                                    rawValue.toLong()
                                } catch (e: NumberFormatException) {
                                    rawValue
                                }
                            }

                            // Float/Double (tidak diawali 0 kecuali 0.x)
                            rawValue.matches(Regex("0\\.\\d+|[1-9]\\d*\\.\\d+")) -> {
                                try {
                                    rawValue.toDouble()
                                } catch (e: NumberFormatException) {
                                    rawValue
                                }
                            }

                            // Fallback string
                            else -> rawValue
                        }

                        map[key] = value
                    }
                }
            }
        } catch (e: IOException) {
            LOGGER.e(TAG, "Error reading file: " + file.absolutePath, e)
        }

        map["id"] ?: return null

        return ParcelableMapJson.fromMap<ModuleProp>(Collections.unmodifiableMap(map))
    }

    @Throws(RemoteException::class)
    override fun exit() {
        enforceManagerPermission("exit")
        exitProcess(0)
    }

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == BINDER_TRANSACTION_transact) {
            data.enforceInterface(AxeronApiConstant.server.BINDER_DESCRIPTOR)
            transactRemote(data, reply, flags)
            return true
        } else if (code == 14) {
            data.enforceInterface(AxeronApiConstant.server.BINDER_DESCRIPTOR)
            val binder = data.readStrongBinder()
            val packageName = data.readString()
            val args = Bundle()
            args.putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName)
            args.putInt(ATTACH_APPLICATION_API_VERSION, -1)
            attachApplication(IShizukuApplication.Stub.asInterface(binder), args)
            reply!!.writeNoException()
            return true
        } else if (rishService.onTransact(
                code,
                data,
                reply,
                flags,
                userServiceManager.environment
            )
        ) {
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    @Throws(RemoteException::class)
    override fun checkPermission(permission: String?): Int {
        enforceCallingPermission("checkPermission")
        return PermissionManagerApis.checkPermission(permission, Os.getuid())
    }
}