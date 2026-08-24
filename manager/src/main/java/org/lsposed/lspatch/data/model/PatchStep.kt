package org.lsposed.lspatch.data.model

import android.util.Log
import org.matrix.vector.ui.logs.LogLevel
import java.io.File

/**
 * The phases a patch passes through, as the UI names them.
 *
 * A mirror of the patcher's own `Logger.Stage` rather than a re-derivation of it from log text:
 * matching English literals breaks the moment one is reworded, and the split branch short-circuits
 * before most of them are emitted at all.
 */
enum class PatchStage {
    ReadingApk,
    SigningSetup,
    RewritingManifest,
    InjectingLoader,
    EmbeddingModules,
    PackingSplit,

    /**
     * Deflation, realignment and signing, all of which happen while the output zip is closed.
     *
     * Nothing is emitted throughout, for tens of seconds on a large app. That silence is the actual
     * reason the old log console read as frozen, so this stage is presented as explicitly
     * indeterminate and labelled as the longest one.
     */
    WritingAndSigning,
    Finished,
}

/**
 * One line of patcher output, with the level it was logged at and when it arrived.
 *
 * [elapsedMs] is measured from the start of the job rather than being a wall-clock time. What a
 * patch log is read for is *where the time went* -- the writing-and-signing stage alone can account
 * for most of a run -- and an absolute timestamp makes the reader do that subtraction themselves.
 */
data class LogLine(
    val level: Int,
    val text: String,
    val elapsedMs: Long = 0L,
    /**
     * The stage that was running when this line arrived, so the step list can show a step's own
     * output rather than sending the reader to the whole log to find it.
     */
    val stage: PatchStage? = null,
) {

    /** `m:ss.t`, the width of which is stable for any patch short enough to sit and watch. */
    val stamp: String
        get() {
            val totalSeconds = elapsedMs / 1000
            val tenths = (elapsedMs % 1000) / 100
            return "%d:%02d.%d".format(totalSeconds / 60, totalSeconds % 60, tenths)
        }
}

/** Maps `android.util.Log` levels onto the shared log renderer's own enum. */
fun Int.toSharedLogLevel(): LogLevel = when (this) {
    Log.VERBOSE -> LogLevel.VERBOSE
    Log.DEBUG -> LogLevel.DEBUG
    Log.WARN -> LogLevel.WARN
    Log.ERROR -> LogLevel.ERROR
    Log.ASSERT -> LogLevel.FATAL
    else -> LogLevel.INFO
}

/**
 * Where a patch job has got to.
 *
 * Shaped on the shared `store/InstallStep`, so a long-running job in LSPatch reports itself the way
 * one in Vector does. Patching and installing are separate states with a pause between them
 * ([Patched] waits for an explicit press): a thirty-second wait must not end in an unbidden system
 * install prompt under a button the user pressed labelled "Patch".
 */
sealed interface PatchStep {

    data object Idle : PatchStep

    data class Preparing(val request: PatchRequest) : PatchStep

    data class Running(
        val request: PatchRequest,
        val stage: PatchStage,
        val apkIndex: Int,
        val apkCount: Int,
        /** The module currently being embedded, when that is what is happening. */
        val module: String? = null,
    ) : PatchStep

    /** Patched, not yet installed. The files are on disk and the next move is the user's. */
    data class Patched(val request: PatchRequest, val files: List<File>) : PatchStep

    /**
     * The installed app has a different signature and must go before the new one can arrive.
     *
     * Its own state rather than a dialog raised from inside the install call, because the answer
     * costs the user their app data and has to be asked where it can be read.
     */
    data class NeedsUninstall(val request: PatchRequest, val files: List<File>) : PatchStep

    data class Uninstalling(val packageName: String) : PatchStep

    data class Installing(val packageName: String) : PatchStep

    /** Waiting on the platform installer's confirmation UI. */
    data class Confirming(val packageName: String) : PatchStep

    data class Done(val packageName: String, val label: String) : PatchStep

    data class Failed(val label: String, val reason: String?, val request: PatchRequest?) : PatchStep

    /** Restoring an app to its unpatched original -- no patch runs, but the job is the same shape. */
    data class Restoring(val packageName: String, val label: String) : PatchStep

    val terminal: Boolean
        get() = this is Idle || this is Done || this is Failed || this is Patched || this is NeedsUninstall
}
