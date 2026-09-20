package frb.axeron.manager.util

import android.util.Log

/**
 * Overlay 功能专用日志标签。
 *
 * 统一标签便于 `logcat -s AxOverlay` 过滤；
 * 同时提供可选的环形缓冲，供授权页/调试页展示最近日志（第一期仅打印）。
 */
object OverlayLog {

    const val TAG = "AxOverlay"

    fun d(msg: String) {
        runCatching { Log.d(TAG, msg) }
    }

    fun i(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    fun w(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    fun e(msg: String, tr: Throwable? = null) {
        runCatching { Log.e(TAG, msg, tr) }
    }
}