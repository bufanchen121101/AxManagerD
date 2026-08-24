package org.lsposed.patch;

import java.io.IOException;

/**
 * A patch could not be completed.
 *
 * An {@link IOException} rather than the {@code Error} this used to be. {@code Error} means the JVM
 * itself is in trouble and that recovery is not expected -- catching one is a code smell, and every
 * caller here has to catch this. Being an {@code IOException} also means it travels the same
 * {@code throws} clause as the real I/O failures it sits beside, instead of needing its own.
 */
public class PatchException extends IOException {

    public PatchException(String message) {
        super(message);
    }

    public PatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
