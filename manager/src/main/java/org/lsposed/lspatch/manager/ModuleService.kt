package org.lsposed.lspatch.manager

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.launch
import org.lsposed.lspatch.lspApp

class ModuleService : Service() {

    companion object {
        private const val TAG = "ModuleService"
    }

    override fun onBind(intent: Intent): IBinder? {
        val packageName = intent.getStringExtra("packageName") ?: return null
        // TODO: Authentication
        Log.i(TAG, "$packageName requests binder")
        // After the binder, never before it: this bind may be what created the process, and the app
        // on the other end is holding its own startup open until this call returns. The rest of the
        // manager's start-up work is posted so it lands once that app is on its way.
        lspApp.globalScope.launch { lspApp.startBackgroundWork() }
        return ManagerService.asBinder()
    }
}
