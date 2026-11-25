package org.apvgakasinay.probe;

/** Names of the probe's own session attributes. */
final class SessionKeys {

    /** Epoch second after which the session is dead whatever its activity. */
    static final String ABSOLUTE_DEADLINE = "apv.absoluteDeadline";
    /** Epoch second of the password step. The absolute deadline of the full session is measured from here. */
    static final String SIGNED_IN_AT = "apv.signedInAt";

    private SessionKeys() {
    }
}
