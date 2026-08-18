package frb.axeron.api

import android.os.Parcelable
import frb.axeron.server.ServerInfo
import frb.axeron.shared.AxeronApiConstant
import kotlinx.parcelize.Parcelize

@Parcelize
data class AxeronInfo(
    val serverInfo: ServerInfo = ServerInfo()
) : Parcelable {

    fun getVersionCode(): Long {
        return serverInfo.versionCode
    }

    fun isRunning(): Boolean {
        return Axeron.pingBinder() && AxeronApiConstant.server.VERSION_CODE <= getVersionCode()
    }

    fun isNeedUpdate(): Boolean {
        return AxeronApiConstant.server.VERSION_CODE > getVersionCode() && Axeron.pingBinder()
    }

    fun isNeedExtraStep(): Boolean {
        return isRunning() && !serverInfo.permission
    }

    fun isRoot(): Boolean {
        return serverInfo.uid == 0
    }

}