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
    }

    record SignedIn(String status, AccountView account) {
    }

    record SecondFactorPending(String status, String pendingExpiresAt) {
    }

    record MfaEnrollment(String secret, String otpauthUri, String expiresAt) {
    }

    record MfaRecoveryCodes(List<String> codes, String generatedAt) {
    }
}
