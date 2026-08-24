package org.lsposed.patch.util;

public abstract class Logger {

    public boolean verbose = false;

    abstract public void d(String msg);

    abstract public void i(String msg);

    abstract public void e(String msg);

    /**
     * The coarse phases a patch passes through, in the order they occur.
     *
     * <p>A structured signal exists because the log alone cannot drive a progress display: matching
     * the English literals would break the moment one is reworded, and the split branch
     * short-circuits before most of them are ever emitted. {@link #WRITING} is the one phase with no
     * end marker of its own -- deflation, realignment and v2 signing all happen while the output
     * zip is being closed, emitting nothing for tens of seconds. A consumer should present it as
     * indeterminate rather than stalled.
     */
    public enum Stage {
        READING, SIGNING, PACKING_SPLIT, REWRITING, INJECTING, EMBEDDING, WRITING, DONE
    }

    /**
     * Reports that {@code stage} has begun for APK {@code index} of {@code total}.
     *
     * <p>A no-op by default, so the CLI logger and any other existing subclass are unaffected and no
     * log output changes.
     */
    public void stage(Stage stage, int index, int total) {
    }
}
