package dev.frb.demo_axerish

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import frb.axeron.Axerish

class Application : android.app.Application() {

    init {
        Axerish.initialize(BuildConfig.APPLICATION_ID)
        Log.d("Axerish","Axerish initialized: ${Axerish.axrun_path.absolutePath}")
        Shell.enableLegacyStderrRedirection = true
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().run {
            setCommands("sh", Axerish.axrun_path.absolutePath)
        })
    }

//    override fun attachBaseContext(base: Context?) {
//        super.attachBaseContext(base)
//        Axerish.initialize(this)
//        Log.d("Axerish","Axerish initialized: ${Axerish.axerish_path.absolutePath}")
//        Shell.enableLegacyStderrRedirection = true
//        Shell.enableVerboseLogging = BuildConfig.DEBUG
//        Shell.setDefaultBuilder(Shell.Builder.create().run {
//            setCommands("sh", Axerish.axerish_path.absolutePath)
//        })
//    }
}