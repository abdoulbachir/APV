package org.apvgakasinay.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
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
    static final String PENDING_ID = "apv.pendingSignIn";
    static final String BOUND_ID = "apv.boundSessionId";

    static final Duration ANONYMOUS_IDLE = Duration.ofMinutes(30);
    static final Duration ANONYMOUS_ABSOLUTE = Duration.ofHours(2);
    static final Duration PENDING = Duration.ofMinutes(10);
    static final Duration DONOR_IDLE = Duration.ofHours(2);
    static final Duration DONOR_ABSOLUTE = Duration.ofHours(24);
    static final Duration STAFF_IDLE = Duration.ofMinutes(30);
    static final Duration STAFF_ABSOLUTE = Duration.ofHours(12);

    /** What a session says about who it belongs to. Null fields never occur: an anonymous session has no identity at all. */
    public record Identity(long accountId, long securityVersion, SessionState state, Instant signedInAt, Instant absoluteDeadline,
                           boolean boundToThisIdentifier) {
    }

    private final SessionAuthenticationStrategy rotateIdentifier;
    private final CsrfTokenRepository csrfTokens;
    private final SecurityContextRepository contexts;
    private final SessionStore store;
    private final AccountRepository accounts;

    public Sessions(CsrfTokenRepository csrfTokens, SecurityContextRepository contexts, SessionStore store, AccountRepository accounts) {
        this.accounts = accounts;
        this.rotateIdentifier = new ChangeSessionIdAuthenticationStrategy();
        this.csrfTokens = csrfTokens;
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
        rotateIdentifier.onAuthentication(authentication, request, response);
        // The new token is created and stored now, with the rest of this session change, not
        // when it is first asked for. A session that exists therefore always has its token,
        // and two requests asking for it at the same moment read the same stored value;
        // neither has anything to write, so neither can fail or receive a token the other replaces.
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);

        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval((int) idle.toSeconds());
        session.setAttribute(ACCOUNT_ID, account.id());
        session.setAttribute(SECURITY_VERSION, account.securityVersion());
        session.setAttribute(STATE, state.code());
        session.setAttribute(SIGNED_IN_AT, signedInAt.getEpochSecond());
        session.setAttribute(ABSOLUTE_DEADLINE, deadline.getEpochSecond());
        if (state.pending()) {
            // The budget of five wrong codes lives in the database, under a random identifier
            // only this session knows, so it is counted exactly across requests and instances.
            byte[] pendingId = Keys.random(32);
            accounts.insertPending(pendingId, account.id(), deadline);
            session.setAttribute(PENDING_ID, java.util.HexFormat.of().formatHex(pendingId));
        } else {
            session.removeAttribute(PENDING_ID);
        }
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

    /**
     * Writes this request's session to the store now, and says whether it is there.
     *
     * The session store normally writes a session when the answer starts to leave, which is
     * after the operation has decided what to answer and has recorded it. Sending the headers
     * of a successful JSON answer here makes that write happen first. It fails in one known
     * way: the session's row was deleted while this request was running, so its attributes
     * have nothing to belong to. Then nothing has been sent, the session is dropped from this
     * request, and the caller refuses. Any other failure is an error like any other.
     *
     * Only for operations whose successful answer is 200 with a JSON body.
     *
     * @return false when the session no longer exists
     */
    public boolean storeNow(HttpServletRequest request, HttpServletResponse response) {
        // The identifier the request arrived with: the one the stored row has until this write.
        String arrivedWith = request.getRequestedSessionId();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        try {
            response.flushBuffer();
            return true;
        } catch (DataAccessException failed) {
            // The database words this failure in more than one way, so the question is asked
            // directly: is the row still there? If it is, this was some other failure.
            if (arrivedWith != null && store.exists(arrivedWith)) {
                throw failed;
            }
            end(request);
            return false;
        } catch (IOException clientGone) {
            throw new UncheckedIOException(clientGone);
        }
    }

    /** Ends the session of this request, if there is one. */
    public static void end(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /** The identifier of the pending sign-in this session carries, or null. */
    public static byte[] pendingId(HttpSession session) {
        return session != null && session.getAttribute(PENDING_ID) instanceof String hex ? java.util.HexFormat.of().parseHex(hex) : null;
    }

    /** Deletes every stored session of an account, except one that is to continue. */
    public void endAllOf(Account account, String exceptSessionId) {
        store.endAllOf(account, exceptSessionId);
    }

    /** Ends the session that had this identifier until a sign-in, second factor or password change replaced it. */
    public void endSuccessorOf(String replacedSessionId) {
        store.endSuccessorOf(replacedSessionId);
    }

    /** True when a stored session with this identifier still exists. */
    public boolean exists(String sessionId) {
        return store.exists(sessionId);
    }
}
