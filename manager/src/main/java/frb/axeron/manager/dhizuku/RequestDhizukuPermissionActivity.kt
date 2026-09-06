package frb.axeron.manager.dhizuku

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener
import com.rosan.dhizuku.shared.DhizukuVariables
import frb.axeron.manager.owner.DeviceOwnerState
import frb.axeron.server.util.Logger

/**
 * 设备所有者（Dhizuku 兼容）授权请求 Activity。
 *
 * 第三方 app 通过 Dhizuku API 的 `Dhizuku.requestPermission` 发起授权请求，
 * 通过广播 action `${applicationId}.action.REQUEST_DHIZUKU_PERMISSION` 启动本 Activity。
 * 用户同意后，记录 uid + 签名到 [DhizukuAuthStore]，并回调授权结果。
 */
class RequestDhizukuPermissionActivity : androidx.activity.ComponentActivity() {
    private val LOGGER = Logger("RequestDhizukuPermissionActivity")

    private var uid: Int = -1
    private var signature: String = ""
    private var listener: IDhizukuRequestPermissionListener? = null
    private var shouldShowDialog by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!registerAppEntity(intent)) {
            finish()
            return
        }

        setContent {
            if (shouldShowDialog) {
                showDialog()
            } else {
                LaunchedEffect(Unit) { finish() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!registerAppEntity(intent)) finish()
        else {
            shouldShowDialog = true
            setContent {
                if (shouldShowDialog) showDialog()
                else LaunchedEffect(Unit) { finish() }
            }
        }
    }

    private var allowFlag: Boolean = false
    private var decided: Boolean = false

    override fun finish() {
        // 在 finish 时持久化授权结果并回调 listener。
        if (uid != -1) {
            val allowApi = decided && allowFlag
            val entry = DhizukuAuthStore.get(this, uid)
            val newEntry = DhizukuAuthStore.AuthEntry(
                uid = uid,
                packageName = DhizukuAuthStore.packageNameForUid(this, uid) ?: "",
                signature = signature,
                allowApi = allowApi || (entry?.allowApi == true && entry.signature == signature),
                blocked = entry?.blocked ?: false,
            )
            if (newEntry.packageName.isNotEmpty()) {
                DhizukuAuthStore.put(this, newEntry)
            }
            listener?.let {
                try {
                    it.onRequestPermission(
                        if (allowApi) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                    )
                } catch (e: Exception) {
                    LOGGER.w("onRequestPermission callback failed: ${e.message}")
                }
            }
        }
        super.finish()
    }

    private fun registerAppEntity(intent: Intent?): Boolean {
        if (intent == null) return false
        val bundle = listOfNotNull(
            intent.extras,
            intent.getBundleExtra("bundle")
        ).find { it.containsKey(DhizukuVariables.PARAM_CLIENT_UID) } ?: return false

        val uid = bundle.getInt(DhizukuVariables.PARAM_CLIENT_UID, -1)
        if (uid == -1) return false

        val binder = bundle.getBinder(DhizukuVariables.PARAM_CLIENT_REQUEST_PERMISSION_BINDER)
            ?: return false

        val listener = runCatching {
            IDhizukuRequestPermissionListener.Stub.asInterface(binder)
        }.getOrElse {
            it.printStackTrace()
            return false
        }

        this.uid = uid
        this.signature = DhizukuAuthStore.signatureForUid(this, uid) ?: ""
        this.listener = listener
        this.allowFlag = false
        this.shouldShowDialog = true
        return true
    }

    @Composable
    private fun showDialog() {
        val uid = this.uid
        val pkgName = DhizukuAuthStore.packageNameForUid(this, uid)
        val appInfo = if (pkgName != null) {
            try {
                packageManager.getApplicationInfo(pkgName, 0)
            } catch (e: Exception) {
                null
            }
        } else null

        val label = appInfo?.loadLabel(packageManager)?.toString() ?: (pkgName ?: "unknown")
        val iconBitmap = appInfo?.loadIcon(packageManager)?.toBitmap()

        AlertDialog(
            onDismissRequest = { finish() },
            icon = {
                iconBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }
            },
            title = {
                Text(
                    text = getString(
                        frb.axeron.manager.R.string.dhizuku_request_permission_title,
                        label
                    )
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = getString(frb.axeron.manager.R.string.dhizuku_request_permission_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    TextButton(
                        onClick = {
                            allowFlag = true
                            decided = true
                            shouldShowDialog = false
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(getString(frb.axeron.manager.R.string.dhizuku_allow))
                    }
                    TextButton(
                        onClick = {
                            allowFlag = false
                            decided = true
                            shouldShowDialog = false
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text(getString(frb.axeron.manager.R.string.dhizuku_deny))
                    }
                }
            }
        )
    }
}