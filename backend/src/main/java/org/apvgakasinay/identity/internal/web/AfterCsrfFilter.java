package org.apvgakasinay.identity.internal.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalLong;
import org.apvgakasinay.audit.AuditService;
import org.apvgakasinay.audit.AuditService.AuditEvent;
import org.apvgakasinay.audit.AuditService.Outcome;
import org.apvgakasinay.identity.internal.Account;
import org.apvgakasinay.identity.internal.AccountRepository;
import org.apvgakasinay.identity.internal.Caller;
import org.apvgakasinay.identity.internal.IdentityProperties;
import org.apvgakasinay.identity.internal.Permissions;
import org.apvgakasinay.identity.internal.SessionState;
import org.apvgakasinay.identity.internal.Sessions;
import org.apvgakasinay.identity.internal.Throttle;
import org.apvgakasinay.identity.internal.IdentityClock;
import org.apvgakasinay.shared.AddressLimiter;
import org.apvgakasinay.shared.ApiCaller;
import org.apvgakasinay.shared.ApiOperation;
import org.apvgakasinay.shared.ErrorWriter;
import org.apvgakasinay.shared.RequestIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Steps 5 and 6 of the request order, after the CSRF check (step 4), for identity operations
 * and for the operations other modules declare with {@link ApiOperation}:
 *
 *   5. rate limits by client address: 429
 *   6. session state: is there a live session, is its identity still valid, is it in a state
 *      this operation accepts: 401, 409 already_signed_in, 403 forbidden
 *
 * Step 6 is where a revoked identity is caught: the account row is read on every request, so
 * a disabled account or a changed security version ends the session at once, before any
 * password or code in the request is looked at.
 */
class AfterCsrfFilter extends OncePerRequestFilter {

    private static final Map<String, Throttle.Limit> LIMITS = Map.of(
            "signin", Throttle.SIGN_IN_BY_ADDRESS,
            "signup", Throttle.SIGN_UP_BY_ADDRESS,
            "credential", Throttle.CREDENTIAL_BY_ADDRESS,
            "token", Throttle.TOKEN_BY_ADDRESS,
            "protected", Throttle.PROTECTED_BY_ADDRESS);

    private final Throttle throttle;
    private final AccountRepository accounts;
    private final IdentityProperties properties;
    private final AuditService audit;
    private final IdentityClock clock;
    private final ObjectProvider<AddressLimiter> limiters;

    AfterCsrfFilter(Throttle throttle, AccountRepository accounts, IdentityProperties properties, AuditService audit, IdentityClock clock,
                    ObjectProvider<AddressLimiter> limiters) {
        this.throttle = throttle;
        this.accounts = accounts;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
        this.limiters = limiters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Routes.Route route = (Routes.Route) request.getAttribute(BeforeCsrfFilter.ROUTE);
        if (route == null || !route.checked()) {
            // Public read endpoints do not depend on the session and never look at it.
            chain.doFilter(request, response);
            return;
        }
        IdentityOperation operation = route.operation();
        Caller caller = new Caller(ClientAddress.of(request, properties.trustedProxies()),
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));

        // Step 5.
        long wait = operation != null ? identityLimit(operation.limit(), caller) : moduleLimit(route.api().limit(), caller);
        if (wait > 0) {
            response.setHeader("Retry-After", String.valueOf(wait));
            ErrorWriter.write(request, response, 429, "rate_limited", "Too many requests");
            return;
        }

        // Step 6.
        HttpSession session = request.getSession(false);
        Sessions.Identity identity = Sessions.identity(session);
        Account account = null;
        if (identity != null) {
            account = accounts.findById(identity.accountId()).orElse(null);
            boolean revoked = account == null || !account.enabled() || account.securityVersion() != identity.securityVersion()
                    || !identity.boundToThisIdentifier();
            if (revoked) {
                Sessions.end(request);
                audit.record(AuditEvent.of("session.revoked", Outcome.REFUSED).account(identity.accountId())
                        .request(caller.address(), caller.requestId()));
                identity = null;
                account = null;
            }
        }
        if (identity != null && identity.state().pending()) {
            // A pending sign-in that has been completed, exhausted or has expired does not exist,
            // whatever a session row may still say.
            byte[] pendingId = Sessions.pendingId(session);
            AccountRepository.Pending pending = pendingId == null ? null : accounts.pending(pendingId).orElse(null);
            // Completed is different: this request began before the same sign-in was completed by
            // another one. It keeps the pending view it started with and ends nothing.
            if (pending == null || (!pending.completed() && !pending.open(clock.now()))) {
                Sessions.end(request);
                identity = null;
                account = null;
            }
        }
        SessionState state = identity == null ? SessionState.ANONYMOUS : identity.state();

        if (operation == null) {
            moduleOperation(request, response, chain, route, caller, identity, account, state);
            return;
        }

        boolean accepted = Arrays.asList(operation.states()).contains(state);
        if (accepted && state == SessionState.AUTHENTICATED && operation.staffOnly() && !account.isStaff()) {
            ErrorWriter.write(request, response, 403, "forbidden", "Not allowed for this account");
            return;
        }
        if (!accepted) {
            if (identity != null) {
                audit.record(AuditEvent.of("session.state_conflict", Outcome.REFUSED).account(identity.accountId())
                        .request(caller.address(), caller.requestId())
                        .details(java.util.Map.of("operation", route.pattern().getPatternString(), "state", state.code())));
            }
            refuse(request, response, operation, state);
            return;
        }
        if (route.pattern().getPatternString().equals("/api/auth/register") && !properties.registrationOpen()) {
            audit.record(AuditEvent.of("account.sign_up_closed", Outcome.REFUSED).request(caller.address(), caller.requestId()));
            ErrorWriter.write(request, response, 403, "registration_closed", "Sign-up is closed");
            return;
        }
        request.setAttribute(Current.ATTRIBUTE, new Current(account, state, caller));
        chain.doFilter(request, response);
    }

    private long identityLimit(String name, Caller caller) {
        Throttle.Limit limit = LIMITS.get(name);
        return limit == null ? 0 : throttle.count(limit, caller.address());
    }

    /** The per-address limit of another module's operation, counted by the limiter that module provides. */
    private long moduleLimit(String name, Caller caller) {
        if (name.isEmpty()) {
            return 0;
        }
        for (AddressLimiter limiter : limiters) {
            OptionalLong wait = limiter.count(name, caller.address());
            if (wait.isPresent()) {
                return wait.getAsLong();
            }
        }
        // A limit nobody provides would be a limit silently not applied.
        throw new IllegalStateException("no rate limiter provides the limit an operation names");
    }

    /**
     * Step 6 for an operation another module declared. The session was judged above exactly as
     * for an identity operation; what remains is whether its state and its account's
     * permissions allow this operation. A session that is not fully signed in is a guest to an
     * operation open to everyone, and is answered 401 with its state's code by one that is not.
     * The permission is checked here, before any parameter of the request is looked at.
     */
    private void moduleOperation(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Routes.Route route,
                                 Caller caller, Sessions.Identity identity, Account account, SessionState state)
            throws ServletException, IOException {
        ApiOperation api = route.api();
        boolean signedIn = state == SessionState.AUTHENTICATED;
        if (api.access() == ApiOperation.Access.SIGNED_IN) {
            if (!signedIn) {
                if (identity != null) {
                    audit.record(AuditEvent.of("session.state_conflict", Outcome.REFUSED).account(identity.accountId())
                            .request(caller.address(), caller.requestId())
                            .details(Map.of("operation", route.pattern().getPatternString(), "state", state.code())));
                    ErrorWriter.write(request, response, 401, state.code(), "Second factor required");
                } else {
                    ErrorWriter.write(request, response, 401, "unauthenticated", "Sign-in required");
                }
                return;
            }
            if (!api.permission().isEmpty()
                    && !Permissions.operational(account.role(), account.canDisburse()).contains(api.permission())) {
                audit.record(AuditEvent.of("staff.permission_refused", Outcome.REFUSED).account(account.id())
                        .request(caller.address(), caller.requestId())
                        .details(Map.of("operation", route.method() + " " + route.pattern().getPatternString(), "permission", api.permission())));
                ErrorWriter.write(request, response, 403, "forbidden", "Not allowed for this account");
                return;
            }
        }
        request.setAttribute(ApiCaller.ATTRIBUTE, new ApiCaller(signedIn ? account.id() : null,
                signedIn ? identity.securityVersion() : 0, caller.address(), caller.requestId()));
        chain.doFilter(request, response);
    }

    /** One answer for each wrong state, always the same. */
    private static void refuse(HttpServletRequest request, HttpServletResponse response, IdentityOperation operation, SessionState state)
            throws IOException {
        if (state == SessionState.AUTHENTICATED) {
            // The operation is for a session that is not fully signed in. Nothing is changed.
            ErrorWriter.write(request, response, 409, "already_signed_in", "Already signed in");
        } else if (state == SessionState.ANONYMOUS) {
            ErrorWriter.write(request, response, 401, "unauthenticated", "Sign-in required");
        } else if (Arrays.asList(operation.states()).contains(SessionState.AUTHENTICATED)) {
            // More sign-in is owed: the code names the pending state.
            ErrorWriter.write(request, response, 401, state.code(), "Second factor required");
        } else {
            // A pending session calling the other pending state's operation.
            ErrorWriter.write(request, response, 403, "forbidden", "Not allowed in this session state");
        }
    }
}
