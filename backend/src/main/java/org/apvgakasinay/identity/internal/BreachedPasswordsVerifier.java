package org.apvgakasinay.identity.internal;

import java.nio.file.Path;

/** The full verification of a list, as production start-up performs it, for the command line. */
public final class BreachedPasswordsVerifier {

    private BreachedPasswordsVerifier() {
    }

    /** Verifies a list against the production requirements. Returns a one-line result, or throws with the reason. */
    public static String verify(Path file) {
        try (BreachedPasswords list = BreachedPasswords.opened(file, BreachedPasswords.MIN_PRODUCTION_ENTRIES, true, true)) {
            return "Suitable for production: " + list.entries() + " entries, format " + BreachedPasswords.FORMAT
                    + ", order, known passwords and SHA-256 verified. Source: " + list.source();
        }
    }
}
