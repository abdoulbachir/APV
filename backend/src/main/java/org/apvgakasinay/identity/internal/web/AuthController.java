package org.apvgakasinay.identity.internal.web;

import static org.apvgakasinay.identity.internal.SessionState.ANONYMOUS;
import static org.apvgakasinay.identity.internal.SessionState.AUTHENTICATED;
import static org.apvgakasinay.identity.internal.SessionState.MFA_ENROLLMENT_REQUIRED;
import static org.apvgakasinay.identity.internal.SessionState.MFA_REQUIRED;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Set;
import org.apvgakasinay.identity.internal.Account;
import org.apvgakasinay.identity.internal.IdentityProperties;
import org.apvgakasinay.identity.internal.IdentityService;
import org.apvgakasinay.identity.internal.MfaService;
import org.apvgakasinay.identity.internal.Sessions;
import org.apvgakasinay.shared.AppClock;
import org.apvgakasinay.shared.Timestamps;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The eleven operations under /api/auth of contract version 0.3.0. By the time a method here
 * runs, the request pipeline has already checked the path and method, the origin, the body's
 * size and type, the CSRF token, the per-address rate limit and the session state. What is
 * left is step 7, validating the body, and step 8, the operation itself.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final IdentityService identity;
    private final MfaService mfa;
    private final AccountViews views;
    private final IdentityProperties properties;
    private final AppClock clock;
    private final JsonMapper json;

    AuthController(IdentityService identity, MfaService mfa, AccountViews views, IdentityProperties properties, AppClock clock, JsonMapper json) {
        this.identity = identity;
        this.mfa = mfa;
        this.views = views;
        this.properties = properties;
        this.clock = clock;
        this.json = json;
    }

    /** Returns the token of this browser's session, creating a short anonymous session when there is none. */
    @GetMapping("/csrf")
    @IdentityOperation(states = {ANONYMOUS, MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED, AUTHENTICATED}, limit = "token")
    Responses.CsrfToken csrf(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Sessions.markAnonymous(session, clock.now());
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new Responses.CsrfToken(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/options")
    @IdentityOperation(states = {ANONYMOUS, MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED, AUTHENTICATED}, limit = "token")
    Responses.AuthOptions options() {
        return new Responses.AuthOptions(properties.registrationOpen());
    }

    @PostMapping("/register")
    @IdentityOperation(states = {ANONYMOUS, MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED}, limit = "signup", body = true)
    ResponseEntity<Responses.RegistrationAccepted> register(HttpServletRequest request) {
        Current current = Current.of(request);
        JsonBody body = JsonBody.read(request, json, Set.of("email", "password", "displayName", "locale"));
        String email = body.email("email");
        String password = body.text("password", 1, 128);
        String displayName = body.text("displayName", 1, 80);
        String locale = body.has("locale") ? body.oneOf("locale", Set.of("en", "fr")) : "en";
        endPendingSession(request, current);
        identity.register(email, password, displayName, locale, current.caller());
        return ResponseEntity.accepted().body(new Responses.RegistrationAccepted("accepted"));
    }

    @PostMapping("/login")
    @IdentityOperation(states = {ANONYMOUS, MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED}, limit = "signin", body = true)
    Object login(HttpServletRequest request, HttpServletResponse response) {
        Current current = Current.of(request);
        JsonBody body = JsonBody.read(request, json, Set.of("email", "password"));
        String email = body.email("email");
        String password = body.text("password", 1, 128);
        // The request is valid, so the operation proceeds, and it proceeds as from anonymous: a
        // sign-in that was waiting for a second factor is over, whatever the password turns out to be.
        endPendingSession(request, current);
        IdentityService.SignIn result = identity.login(email, password, request, response, current.caller());
        if (result.state() == AUTHENTICATED) {
            return new Responses.SignedIn("authenticated", views.of(result.account(), request.getSession(false)));
        }
        return new Responses.SecondFactorPending(result.state().code(), Timestamps.format(result.pendingExpiresAt()));
    }

    /** Succeeds also when there was no session, so it can always be called. */
    @PostMapping("/logout")
    @IdentityOperation(states = {ANONYMOUS, MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED, AUTHENTICATED})
    ResponseEntity<Void> logout(HttpServletRequest request) {
        JsonBody.requireEmpty(request);
        identity.logout(request, Current.of(request).caller());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    @IdentityOperation(states = {AUTHENTICATED}, limit = "protected", body = true)
    ResponseEntity<Void> changePassword(HttpServletRequest request, HttpServletResponse response) {
        Current current = Current.of(request);
        JsonBody body = JsonBody.read(request, json, Set.of("currentPassword", "newPassword"));
        String currentPassword = body.text("currentPassword", 1, 128);
        String newPassword = body.text("newPassword", 1, 128);
        identity.changePassword(current.account(), currentPassword, newPassword, request, response, current.caller());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/set-password")
    @IdentityOperation(states = {ANONYMOUS, MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED}, limit = "credential", body = true)
    ResponseEntity<Void> setPassword(HttpServletRequest request) {
        Current current = Current.of(request);
        JsonBody body = JsonBody.read(request, json, Set.of("credential", "newPassword"));
        // Any text is a syntactically valid credential: a wrong one is answered credential_invalid, whatever its shape.
        String credential = body.text("credential", 1, 128);
        String newPassword = body.text("newPassword", 1, 128);
        endPendingSession(request, current);
        identity.setPassword(credential, newPassword, current.caller());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mfa/verify")
    @IdentityOperation(states = {MFA_REQUIRED}, body = true)
    Responses.SignedIn verify(HttpServletRequest request, HttpServletResponse response) {
        Current current = Current.of(request);
        JsonBody body = JsonBody.read(request, json, Set.of("method", "code"));
        boolean recovery = body.oneOf("method", Set.of("totp", "recovery_code")).equals("recovery_code");
        // The code must have the shape of its method, before any code is checked or counted.
        String code = recovery ? body.matching("code", 16, 19, JsonBody.RECOVERY_CODE) : body.matching("code", 6, 6, JsonBody.TOTP_CODE);
        Account account = mfa.verify(current.account(), recovery, code, request, response, current.caller());
        return new Responses.SignedIn("authenticated", views.of(account, request.getSession(false)));
    }

    @PostMapping("/mfa/enroll")
    @IdentityOperation(states = {MFA_ENROLLMENT_REQUIRED})
    Responses.MfaEnrollment enroll(HttpServletRequest request) {
        JsonBody.requireEmpty(request);
        Current current = Current.of(request);
        MfaService.Enrollment enrollment = mfa.enroll(current.account(), request, current.caller());
        return new Responses.MfaEnrollment(enrollment.secret(), enrollment.otpauthUri(), Timestamps.format(enrollment.expiresAt()));
    }

    @PostMapping("/mfa/confirm")
    @IdentityOperation(states = {MFA_ENROLLMENT_REQUIRED}, body = true)
    Responses.MfaRecoveryCodes confirm(HttpServletRequest request, HttpServletResponse response) {
        Current current = Current.of(request);
        JsonBody body = JsonBody.read(request, json, Set.of("code"));
        String code = body.matching("code", 6, 6, JsonBody.TOTP_CODE);
        List<String> codes = mfa.confirm(current.account(), code, request, response, current.caller());
        return new Responses.MfaRecoveryCodes(codes, Timestamps.format(clock.now()));
    }

    @PostMapping("/mfa/recovery-codes")
    @IdentityOperation(states = {AUTHENTICATED}, limit = "protected", body = true, staffOnly = true)
    Responses.MfaRecoveryCodes recoveryCodes(HttpServletRequest request) {
        Current current = Current.of(request);
        JsonBody body = JsonBody.read(request, json, Set.of("currentPassword", "code"));
        String currentPassword = body.text("currentPassword", 1, 128);
        String code = body.matching("code", 6, 6, JsonBody.TOTP_CODE);
        List<String> codes = mfa.regenerateRecoveryCodes(current.account(), currentPassword, code, request, current.caller());
        return new Responses.MfaRecoveryCodes(codes, Timestamps.format(clock.now()));
    }

    /** A session that was waiting for a second factor is discarded before sign-in, sign-up or set-password proceeds. */
    private static void endPendingSession(HttpServletRequest request, Current current) {
        if (current.state().pending()) {
            Sessions.end(request);
        }
    }
}
