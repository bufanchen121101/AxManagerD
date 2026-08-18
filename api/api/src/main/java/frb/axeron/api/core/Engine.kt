package frb.axeron.api.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.File
import kotlin.system.exitProcess


open class Engine: Application() {

    companion object {
        @JvmStatic
        lateinit var application: Engine
            private set
    }

    private fun saveCrashLog(t: Throwable) {
        try {
            val logFile = File(externalCacheDir, "crash.log")
            logFile.appendText(
                "\n=== Crash at ${System.currentTimeMillis()} ===\n" +
                        Log.getStackTraceString(t) + "\n"
            )
        } catch (e: Exception) {
            // gagal nulis log
            Log.e("Engine", "Failed to save crash log", e)
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val errorLog = Log.getStackTraceString(throwable)

            saveCrashLog(throwable)

            // Buka CrashActivity
            val intent = Intent().apply {
                setClassName(packageName, "frb.axeron.manager.ui.CrashActivity")
                putExtra("error_log", errorLog)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("Engine", "Failed to start CrashActivity", e)
            }

            // Matikan proses saat ini agar bersih
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}