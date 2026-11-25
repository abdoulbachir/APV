package org.apvgakasinay.identity.internal.web;

import java.util.List;

/**
 * The identity responses, exactly as the contract's schemas define them. Each is built field
 * by field from named values; none is a serialized row. Nothing here can hold a hash, a stored
 * secret, a session identifier or a counter, because there is no field for one.
 */
final class Responses {

    private Responses() {
    }

    record CsrfToken(String headerName, String token) {

        /** Never the contents: frameworks print the object they are about to write when logging at DEBUG. */
        @Override
        public String toString() {
            return "CsrfToken[not shown]";
        }
    }

    record AuthOptions(boolean registrationOpen) {
    }

    record RegistrationAccepted(String status) {
    }

    record Mfa(boolean required, boolean enrolled, Integer recoveryCodesRemaining) {
    }

    record SessionInfo(String idleExpiresAt, String absoluteExpiresAt) {
    }

    record AccountView(String id, String email, boolean emailVerified, String displayName, String role, List<String> permissions,
                       Mfa mfa, String locale, String createdAt, SessionInfo session) {

        /** Never the contents: frameworks print the object they are about to write when logging at DEBUG. */
        @Override
        public String toString() {
            return "AccountView[not shown]";
        }
    }

    record SignedIn(String status, AccountView account) {

        /** Never the contents: frameworks print the object they are about to write when logging at DEBUG. */
        @Override
        public String toString() {
            return "SignedIn[not shown]";
        }
    }

    record SecondFactorPending(String status, String pendingExpiresAt) {
    }

    record MfaEnrollment(String secret, String otpauthUri, String expiresAt) {

        /** Never the contents: frameworks print the object they are about to write when logging at DEBUG. */
        @Override
        public String toString() {
            return "MfaEnrollment[not shown]";
        }
    }

    record MfaRecoveryCodes(List<String> codes, String generatedAt) {

        /** Never the contents: frameworks print the object they are about to write when logging at DEBUG. */
        @Override
        public String toString() {
            return "MfaRecoveryCodes[not shown]";
        }
    }
}
