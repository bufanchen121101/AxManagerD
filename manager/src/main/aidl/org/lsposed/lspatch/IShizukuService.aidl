package org.lsposed.lspatch;

interface IShizukuService {
    // Executes a single program (no shell: no pipes, globs or redirects) and returns its output.
    String runShellCommand(String cmd) = 1;

    // Shizuku's own teardown, and the only one there is: on unbind (or when a non-daemon service's
    // client dies) the server one-way transacts this exact code and nothing else -- it never signals
    // or kills the process. The id is therefore fixed by Shizuku, not ours to choose:
    // ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy is 16777115, which is 16777114 written as
    // an aidl offset from FIRST_CALL_TRANSACTION. Any other id leaves the shell process running for
    // the rest of the boot.
    void destroy() = 16777114;

    // Runs [script] through `sh -c`, so globs, loops and redirects work — used to gather the export
    // archive (tombstones, anr, …). Output is tail-capped like runShellCommand.
    String runShellScript(String script) = 8;

    // --- Continuous log collection (shell UID owns the files; the app reads them back through
    // readLogPart, never touching the filesystem itself — cross-UID reads of /data/local/tmp are
    // not otherwise permitted). Appended with fresh ids; existing ids are never renumbered. ---

    // Starts a collector that fans one live logcat into two rotating, timestamped streams in
    // [logDir]: "verbose" (every line) and "framework" (lines from a uid in [relevantUids], plus
    // AndroidRuntime warnings/errors and fatals). Kills any collector already running first.
    boolean startLogCollector(String logDir, in int[] relevantUids) = 3;

    // Stops the running collector, if any.
    void stopLogCollector() = 4;

    // Whether the collector process is currently alive.
    boolean isLogCollectorRunning() = 5;

    // The rotating parts of one stream ([prefix] = "verbose" or "framework") in [logDir], oldest
    // first, each as "absolutePath\tsizeBytes".
    String[] listLogParts(String logDir, String prefix) = 6;

    // Reads a part file, keeping at most [maxChars] from the tail (the newest lines).
    String readLogPart(String path, int maxChars) = 7;

    // Reads at most [maxBytes] from [path] starting at [offset], as bytes rather than text: a
    // Binder transaction is capped around 1 MB, so anything worth exporting has to arrive in pieces,
    // and a piece boundary that fell inside a multi-byte character would corrupt it. Returns an
    // empty array at end of file or when the file cannot be read, which ends the caller's loop.
    byte[] readFileChunk(String path, long offset, int maxBytes) = 10;

    // Starts a new log part on both streams without stopping the collector or deleting anything: the
    // current parts close and fresh ones open, so collection never has a gap. This is the rootless
    // equivalent of Vector's "start a new log". Returns false when no collector is running.
    boolean startNewLogPart() = 9;
}
