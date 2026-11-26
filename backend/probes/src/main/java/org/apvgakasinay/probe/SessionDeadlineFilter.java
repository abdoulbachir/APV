package org.apvgakasinay.probe;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the fixed absolute deadline. It runs before the CSRF check, and simply ends a
 * session whose deadline has passed: from then on the request is handled exactly as one with
 * no session, which is the "a passed deadline means the session does not exist" rule of the
 * contract. Activity never moves this deadline; only a new sign-in sets a new one.
 */
class SessionDeadlineFilter extends OncePerRequestFilter {

    private final ProbeClock clock;

    SessionDeadlineFilter(ProbeClock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object deadline = session.getAttribute(SessionKeys.ABSOLUTE_DEADLINE);
            if (deadline instanceof Long epochSecond && clock.now().getEpochSecond() >= epochSecond) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
