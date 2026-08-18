package frb.axeron.server

import android.os.Parcelable
import android.os.SystemClock
import kotlinx.parcelize.Parcelize

enum class Mode(val label: String) {
    NOT_ACTIVATED("Not Activated"), ROOT("Root"), SHELL("Shell"), USER("User")
}


@Parcelize
data class ServerInfo(
    val version: String = "Unknown",
    val versionCode: Long = -1,
    val uid: Int = -1,
    val pid: Int = -1,
    val selinuxContext: String = "Unknown",
    val starting: Long = SystemClock.elapsedRealtime(),
    val permission: Boolean = false
) : Parcelable {

    fun getMode(): Mode {
        return when (uid) {
            -1 -> Mode.NOT_ACTIVATED
            0 -> Mode.ROOT
            2000 -> Mode.SHELL
            else -> Mode.ROOT
        }
    }
}