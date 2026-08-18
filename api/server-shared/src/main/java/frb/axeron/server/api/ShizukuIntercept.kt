package frb.axeron.server.api

import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import frb.axeron.server.Environment
import frb.axeron.server.ServerInfo
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuServiceConnection
import rikka.parcelablelist.ParcelableListSlice

interface ShizukuIntercept {
    fun getServerInfo(): ServerInfo
    fun transactRemote(data: Parcel, reply: Parcel?, flags: Int)
    fun checkPermission(permission: String?): Int
    fun newProcess(cmd: Array<out String?>?, env: Array<out String?>?, dir: String?): IRemoteProcess
    fun getEnvironment(envType: Int): Environment
    fun getSystemProperty(name: String?, defaultValue: String?): String
    fun setSystemProperty(name: String?, value: String?)
    fun addUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int
    fun removeUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int
    fun requestPermission(requestCode: Int)
    fun checkSelfPermission(): Boolean
    fun shouldShowRequestPermissionRationale(): Boolean
    fun attachApplication(application: IShizukuApplication?, args: Bundle?)
    fun enableShizukuService(bool: Boolean)
    fun attachUserService(binder: IBinder?, options: Bundle)
    fun dispatchPackageChanged(intent: Intent?)
    fun dispatchPermissionConfirmationResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: Bundle?
    )

    fun getFlagsForUid(uid: Int, mask: Int): Int
    fun updateFlagsForUid(uid: Int, mask: Int, value: Int)
    fun getApplications(userId: Int): ParcelableListSlice<PackageInfo?>
}