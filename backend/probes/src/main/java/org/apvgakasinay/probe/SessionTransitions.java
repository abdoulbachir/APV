package org.apvgakasinay.probe;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
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
 * The one place a session changes privilege. Every transition does the same four things:
 * replace the session identifier, replace the CSRF token, set this session's own idle timeout
 * and absolute deadline, and store the new authentication.
 *
 * The identifier and the token are replaced with Spring Security's own strategies, the ones
 * its built-in login uses, called here by hand because the contract's sign-in, second-factor
 * and password-change operations are JSON endpoints, not the framework's login filter.
 */
@Component
class SessionTransitions {

    private final SessionAuthenticationStrategy rotateIdentifierThenToken;
    private final SecurityContextRepository contexts;

    SessionTransitions(CsrfTokenRepository csrfTokens, SecurityContextRepository contexts) {
        this.rotateIdentifierThenToken = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokens)));
        this.contexts = contexts;
    }

    void establish(HttpServletRequest request, HttpServletResponse response, Authentication authentication,
                   Duration idle, Instant signedInAt, Instant absoluteDeadline) {
        // The identifier can only be changed on a session that exists.
        request.getSession(true);
        rotateIdentifierThenToken.onAuthentication(authentication, request, response);

        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval((int) idle.toSeconds());
        session.setAttribute(SessionKeys.SIGNED_IN_AT, signedInAt.getEpochSecond());
        session.setAttribute(SessionKeys.ABSOLUTE_DEADLINE, absoluteDeadline.getEpochSecond());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }
}
