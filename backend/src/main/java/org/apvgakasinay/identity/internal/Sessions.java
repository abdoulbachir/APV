package org.apvgakasinay.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Everything that changes a session, in one place.
 *
 * {@link #establish} is the only way a session gains or changes privilege. It always replaces
 * the session identifier, replaces the CSRF token, sets this session's own idle timeout and
 * absolute deadline, and stores the authentication. The contract's sign-in, second-factor and
 * password-change operations are JSON endpoints, so the framework does none of this by itself
 * (ADR 0002, probe 3).
 */
@Component
public class Sessions {

    /** The framework's name for the password factor, and ours for the authenticator code. */
    public static final String PASSWORD_FACTOR = FactorGrantedAuthority.PASSWORD_AUTHORITY;
    public static final String TOTP_FACTOR = FactorGrantedAuthority.fromFactor("TOTP").getAuthority();

    static final String ACCOUNT_ID = "apv.accountId";
    static final String SECURITY_VERSION = "apv.securityVersion";
    static final String STATE = "apv.state";
    static final String SIGNED_IN_AT = "apv.signedInAt";
    public static final String ABSOLUTE_DEADLINE = "apv.absoluteDeadline";
    static final String MFA_FAILURES = "apv.mfaFailures";
    static final String BOUND_ID = "apv.boundSessionId";

    static final Duration ANONYMOUS_IDLE = Duration.ofMinutes(30);
    static final Duration ANONYMOUS_ABSOLUTE = Duration.ofHours(2);
    static final Duration PENDING = Duration.ofMinutes(10);
    static final Duration DONOR_IDLE = Duration.ofHours(2);
    static final Duration DONOR_ABSOLUTE = Duration.ofHours(24);
    static final Duration STAFF_IDLE = Duration.ofMinutes(30);
    static final Duration STAFF_ABSOLUTE = Duration.ofHours(12);
    static final int MAX_MFA_FAILURES = 5;

    /** What a session says about who it belongs to. Null fields never occur: an anonymous session has no identity at all. */
    public record Identity(long accountId, long securityVersion, SessionState state, Instant signedInAt, Instant absoluteDeadline,
                           boolean boundToThisIdentifier) {
    }

    private final SessionAuthenticationStrategy rotateIdentifierThenToken;
    private final SecurityContextRepository contexts;
    private final SessionStore store;

    public Sessions(CsrfTokenRepository csrfTokens, SecurityContextRepository contexts, SessionStore store) {
        this.rotateIdentifierThenToken = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokens)));
        this.contexts = contexts;
        this.store = store;
    }

    /** Reads the identity a session carries, or null for no session or an anonymous one. */
    public static Identity identity(HttpSession session) {
        if (session == null || !(session.getAttribute(ACCOUNT_ID) instanceof Long accountId)) {
            return null;
        }
        return new Identity(
                accountId,
                (Long) session.getAttribute(SECURITY_VERSION),
                SessionState.of(session.getAttribute(STATE)),
                Instant.ofEpochSecond((Long) session.getAttribute(SIGNED_IN_AT)),
                Instant.ofEpochSecond((Long) session.getAttribute(ABSOLUTE_DEADLINE)),
                session.getId().equals(session.getAttribute(BOUND_ID)));
    }

    /** Gives a brand-new session the short life of an anonymous one. It holds only the CSRF token. */
    public static void markAnonymous(HttpSession session, Instant now) {
        if (session.getAttribute(ABSOLUTE_DEADLINE) == null) {
            session.setMaxInactiveInterval((int) ANONYMOUS_IDLE.toSeconds());
            session.setAttribute(ABSOLUTE_DEADLINE, now.plus(ANONYMOUS_ABSOLUTE).getEpochSecond());
        }
    }

    /**
     * Moves the session to a state for an account.
     *
     * @param signedInAt the instant of the password step; the absolute deadline of a full session is measured from it
     */
    public void establish(HttpServletRequest request, HttpServletResponse response, Account account, SessionState state, Instant signedInAt) {
        Duration idle;
        Instant deadline;
        if (state.pending()) {
            idle = PENDING;
            deadline = signedInAt.plus(PENDING);
        } else if (account.isStaff()) {
            idle = STAFF_IDLE;
            deadline = signedInAt.plus(STAFF_ABSOLUTE);
        } else {
            idle = DONOR_IDLE;
            deadline = signedInAt.plus(DONOR_ABSOLUTE);
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + account.role().toUpperCase()));
        authorities.add(FactorGrantedAuthority.withAuthority(PASSWORD_FACTOR).issuedAt(signedInAt).build());
        if (state == SessionState.AUTHENTICATED && account.isStaff()) {
            authorities.add(FactorGrantedAuthority.withAuthority(TOTP_FACTOR).issuedAt(signedInAt).build());
        }
        // The principal name is the stable account identifier: it is what sessions are found by.
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(account.publicId(), null, authorities);

        // The identifier can only be changed on a session that exists.
        request.getSession(true);
        rotateIdentifierThenToken.onAuthentication(authentication, request, response);

        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval((int) idle.toSeconds());
        session.setAttribute(ACCOUNT_ID, account.id());
        session.setAttribute(SECURITY_VERSION, account.securityVersion());
        session.setAttribute(STATE, state.code());
        session.setAttribute(SIGNED_IN_AT, signedInAt.getEpochSecond());
        session.setAttribute(ABSOLUTE_DEADLINE, deadline.getEpochSecond());
        session.removeAttribute(MFA_FAILURES);
        // The privilege belongs to this identifier and to no other. The session store saves the
        // identifier on every request, so a slow request that started before the rotation could
        // write the old identifier back. Such a session no longer matches this value and is
        // refused and ended at the next request: an old identifier can never carry the privilege.
        session.setAttribute(BOUND_ID, session.getId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    /** Ends the session of this request, if there is one. */
    public static void end(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /** Counts a wrong second-factor code on this pending sign-in. Returns true when it was the last allowed one. */
    public static boolean countMfaFailure(HttpSession session) {
        int failures = session.getAttribute(MFA_FAILURES) instanceof Integer count ? count + 1 : 1;
        session.setAttribute(MFA_FAILURES, failures);
        return failures >= MAX_MFA_FAILURES;
    }

    /** Deletes every stored session of an account, except one that is to continue. */
    public void endAllOf(Account account, String exceptSessionId) {
        store.endAllOf(account, exceptSessionId);
    }

    /** True when a stored session with this identifier still exists. */
    public boolean exists(String sessionId) {
        return store.exists(sessionId);
    }
}
