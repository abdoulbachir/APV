package org.apvgakasinay.probe;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Just enough endpoints to drive every session transition of the contract. */
@RestController
@RequestMapping("/probe")
class ProbeController {

    static final Duration ANONYMOUS_IDLE = Duration.ofMinutes(30);
    static final Duration ANONYMOUS_ABSOLUTE = Duration.ofHours(2);
    static final Duration PENDING = Duration.ofMinutes(10);
    static final Duration DONOR_IDLE = Duration.ofHours(2);
    static final Duration DONOR_ABSOLUTE = Duration.ofHours(24);
    static final Duration STAFF_IDLE = Duration.ofMinutes(30);
    static final Duration STAFF_ABSOLUTE = Duration.ofHours(12);

    private final ProbeAccounts accounts;
    private final PasswordEncoder encoder;
    private final SessionTransitions transitions;
    private final ProbeClock clock;
    private final SecureRandom random = new SecureRandom();

    ProbeController(ProbeAccounts accounts, PasswordEncoder encoder, SessionTransitions transitions, ProbeClock clock) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.transitions = transitions;
        this.clock = clock;
    }

    @GetMapping("/public")
    Map<String, Object> open() {
        return Map.of("public", true);
    }

    /** Returns the token of this browser's session, creating an anonymous session if there is none. */
    @GetMapping("/csrf")
    ResponseEntity<Map<String, Object>> csrf(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        if (session.getAttribute(SessionKeys.ABSOLUTE_DEADLINE) == null) {
            session.setMaxInactiveInterval((int) ANONYMOUS_IDLE.toSeconds());
            session.setAttribute(SessionKeys.ABSOLUTE_DEADLINE, clock.now().plus(ANONYMOUS_ABSOLUTE).getEpochSecond());
        }
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return noStore(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        ProbeAccounts.Account account = accounts.find(body.get("email"));
        if (account == null || !encoder.matches(body.getOrDefault("password", ""), account.passwordHash)) {
            return error(401, "invalid_credentials");
        }
        Instant now = clock.now();
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + account.role.toUpperCase()));
        authorities.add(FactorGrantedAuthority.withAuthority(ProbeSecurityConfig.PASSWORD_FACTOR).issuedAt(now).build());
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(account.email, null, authorities);

        if (account.isStaff()) {
            // No permission yet: a short session that can only finish the second factor.
            transitions.establish(request, response, authentication, PENDING, now, now.plus(PENDING));
            return noStore(Map.of("status", account.totpSecret == null ? "mfa_enrollment_required" : "mfa_required"));
        }
        transitions.establish(request, response, authentication, DONOR_IDLE, now, now.plus(DONOR_ABSOLUTE));
        return noStore(Map.of("status", "authenticated"));
    }

    @PostMapping("/mfa/verify")
    ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        ProbeAccounts.Account account = accounts.find(current.getName());
        if (account.totpSecret == null || !accept(account, account.totpSecret, body.get("code"))) {
            return error(401, "mfa_code_invalid");
        }
        completeSecondFactor(current, request, response);
        return noStore(Map.of("status", "authenticated"));
    }

    @PostMapping("/mfa/enroll")
    ResponseEntity<Map<String, Object>> enroll() {
        ProbeAccounts.Account account = accounts.find(SecurityContextHolder.getContext().getAuthentication().getName());
        if (account.totpSecret != null) {
            return error(403, "forbidden");
        }
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        account.pendingTotpSecret = secret;
        return noStore(Map.of("status", "pending"));
    }

    @PostMapping("/mfa/confirm")
    ResponseEntity<Map<String, Object>> confirm(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        ProbeAccounts.Account account = accounts.find(current.getName());
        byte[] pending = account.pendingTotpSecret;
        if (account.totpSecret != null || pending == null || !accept(account, pending, body.get("code"))) {
            return error(401, "mfa_code_invalid");
        }
        account.totpSecret = pending;
        account.pendingTotpSecret = null;
        completeSecondFactor(current, request, response);
        return noStore(Map.of("status", "authenticated"));
    }

    @PostMapping("/change-password")
    ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        ProbeAccounts.Account account = accounts.find(current.getName());
        if (!encoder.matches(body.getOrDefault("currentPassword", ""), account.passwordHash)) {
            // About the secret only: the session is left exactly as it was.
            return error(401, "invalid_credentials");
        }
        account.passwordHash = encoder.encode(body.get("newPassword"));
        HttpSession session = request.getSession(false);
        Instant signedInAt = Instant.ofEpochSecond((Long) session.getAttribute(SessionKeys.SIGNED_IN_AT));
        Instant deadline = Instant.ofEpochSecond((Long) session.getAttribute(SessionKeys.ABSOLUTE_DEADLINE));
        // Same privileges, same deadlines, new identifier and new token.
        transitions.establish(request, response, current, account.isStaff() ? STAFF_IDLE : DONOR_IDLE, signedInAt, deadline);
        return noStore(Map.of("status", "changed"));
    }

    @PostMapping("/logout")
    ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return noStore(Map.of("status", "signed_out"));
    }

    @GetMapping("/me")
    ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", current.getName());
        body.put("authorities", current.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList());
        body.put("idleSeconds", session.getMaxInactiveInterval());
        body.put("absoluteDeadline", session.getAttribute(SessionKeys.ABSOLUTE_DEADLINE));
        return noStore(body);
    }

    /** The second factor succeeded: grant its authority and move to the full staff session. */
    private void completeSecondFactor(Authentication current, HttpServletRequest request, HttpServletResponse response) {
        Instant now = clock.now();
        List<GrantedAuthority> authorities = new ArrayList<>(current.getAuthorities());
        authorities.add(FactorGrantedAuthority.withAuthority(ProbeSecurityConfig.TOTP_FACTOR).issuedAt(now).build());
        Authentication full = UsernamePasswordAuthenticationToken.authenticated(current.getName(), null, authorities);
        Instant signedInAt = Instant.ofEpochSecond((Long) request.getSession(false).getAttribute(SessionKeys.SIGNED_IN_AT));
        // The absolute deadline is measured from the password step: fixed at sign-in.
        transitions.establish(request, response, full, STAFF_IDLE, signedInAt, signedInAt.plus(STAFF_ABSOLUTE));
    }

    /** Accepts a code once: the last accepted time step only ever moves forward, atomically. */
    private boolean accept(ProbeAccounts.Account account, byte[] secret, String code) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return false;
        }
        long step = Totp.matchingStep(secret, code, clock.now());
        if (step < 0) {
            return false;
        }
        while (true) {
            long last = account.lastAcceptedStep.get();
            if (step <= last) {
                return false;
            }
            if (account.lastAcceptedStep.compareAndSet(last, step)) {
                return true;
            }
        }
    }

    private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", Map.of("code", code)));
    }
}
