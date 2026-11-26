package org.apvgakasinay.identity.internal;

/** The four states of a browser's session (identity-and-security.md, 4.3). */
public enum SessionState {
    ANONYMOUS("anonymous"),
    MFA_REQUIRED("mfa_required"),
    MFA_ENROLLMENT_REQUIRED("mfa_enrollment_required"),
    AUTHENTICATED("authenticated");

    private final String code;

    SessionState(String code) {
        this.code = code;
    }

    /** The name used in the contract, and as the 401 code for the two pending states. */
    public String code() {
        return code;
    }

    public boolean pending() {
        return this == MFA_REQUIRED || this == MFA_ENROLLMENT_REQUIRED;
    }

    static SessionState of(Object stored) {
        for (SessionState state : values()) {
            if (state.code.equals(stored)) {
                return state;
            }
        }
        return ANONYMOUS;
    }
}
