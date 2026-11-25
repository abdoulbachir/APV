package org.apvgakasinay.identity.internal.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.apvgakasinay.identity.internal.IdentityProperties;
import org.apvgakasinay.identity.internal.Sessions;
import org.apvgakasinay.shared.AppClock;
import org.apvgakasinay.shared.ErrorWriter;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Steps 1 to 3 of the request order (identity-and-security.md, 4.3), in one filter so their
 * order is fixed by this code and not by filter registration:
 *
 *   1. path and method: 404, 405
 *   2. origin and fetch metadata, on methods other than GET, HEAD and OPTIONS: 403 forbidden
 *   3. body size and declared type: 413, 415
 *
 * It then ends a session that has passed its absolute deadline, so that the CSRF check that
 * follows sees a session that does not exist. Public read requests never touch a session.
 */
class BeforeCsrfFilter extends OncePerRequestFilter {

    static final String ROUTE = "apv.identity.route";
    static final String BODY = "apv.identity.body";
    static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Routes routes;
    private final IdentityProperties properties;
    private final AppClock clock;

    BeforeCsrfFilter(Routes routes, IdentityProperties properties, AppClock clock) {
        this.routes = routes;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();

        // Step 1: does this path exist, and does it support this method?
        Routes.Match match = routes.match(request.getRequestURI().substring(request.getContextPath().length()), method);
        if (!match.pathKnown()) {
            ErrorWriter.write(request, response, 404, "not_found", "Not found");
            return;
        }
        if (match.route() == null) {
            response.setHeader("Allow", String.join(", ", match.allowedMethods()));
            ErrorWriter.write(request, response, 405, "method_not_allowed", "Method not allowed");
            return;
        }
        Routes.Route route = match.route();
        request.setAttribute(ROUTE, route);

        // Step 2: a request that changes something must come from the site's own pages.
        if (!SAFE_METHODS.contains(method) && !sameOrigin(request)) {
            ErrorWriter.write(request, response, 403, "forbidden", "Request did not come from this site");
            return;
        }

        HttpServletRequest forwarded = request;
        if (route.operation() != null) {
            // Step 3: size and declared type of the body, before anything is parsed.
            if (route.operation().body()) {
                if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                    ErrorWriter.write(request, response, 413, "payload_too_large", "Request body too large");
                    return;
                }
                if (!isJson(request.getContentType())) {
                    ErrorWriter.write(request, response, 415, "unsupported_media_type", "Content-Type must be application/json");
                    return;
                }
                byte[] body;
                try (InputStream in = request.getInputStream()) {
                    body = in.readNBytes(MAX_BODY_BYTES + 1);
                }
                if (body.length > MAX_BODY_BYTES) {
                    ErrorWriter.write(request, response, 413, "payload_too_large", "Request body too large");
                    return;
                }
                request.setAttribute(BODY, body);
                forwarded = new CachedBodyRequest(request, body);
            }

            // A session past its absolute deadline no longer exists, from this point on.
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(Sessions.ABSOLUTE_DEADLINE) instanceof Long deadline
                    && clock.now().getEpochSecond() >= deadline) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(forwarded, response);
    }

    /**
     * Refused when an Origin header is present and is not exactly this site's origin, or when a
     * Sec-Fetch-Site header is present and is not "same-origin". Either is enough. Neither
     * present passes: older browsers and tools send neither, and the CSRF token still applies.
     */
    private boolean sameOrigin(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && !fetchSite.equals("same-origin")) {
            return false;
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        if (properties.allowedOrigins().contains(origin)) {
            return true;
        }
        return properties.allowedOrigins().isEmpty() && origin.equals(ownOrigin(request));
    }

    private static String ownOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return type.getType().equals("application") && type.getSubtype().equals("json");
        } catch (RuntimeException unparseable) {
            return false;
        }
    }
}
