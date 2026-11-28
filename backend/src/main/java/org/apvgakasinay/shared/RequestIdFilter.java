package org.apvgakasinay.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The outermost filter. It gives every request an identifier (quoted in error bodies and logs)
 * and sets the caching rule: public API responses are reusable only after checking with the
 * server, identity responses are never stored.
 *
 * It is also where a failure that no controller saw ends: an exception raised inside a filter
 * (the security filters, the session store) never reaches the controller advice. It is logged
 * here and answered with the contract's generic 500, so the framework's own error body is
 * never sent (identity-and-security.md, 9.7).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "apv.requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new identifier: 16 hexadecimal digits. */
    public static String newId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * The identifier of this request. A request this filter never saw (one answered on the
     * error path of the server itself) is given one here, so every error body carries one.
     */
    public static String idOf(HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute(ATTRIBUTE) instanceof String known) {
            return known;
        }
        String requestId = newId();
        request.setAttribute(ATTRIBUTE, requestId);
        if (response != null && !response.isCommitted()) {
            response.setHeader("X-Request-Id", requestId);
        }
        return requestId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = newId();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        String path = request.getRequestURI();
        if (ApiPaths.isNeverStored(path)) {
            // Every identity and donation answer, errors included: never stored by any cache.
            response.setHeader("Cache-Control", "no-store");
        } else if (path.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-cache");
        }
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            if (response.isCommitted()) {
                // The answer has already left; all that can still be done is to end it.
                throw failure;
            }
            log.error("Unexpected error outside a controller, request {}", requestId, failure);
            // What was buffered for the answer that failed is dropped; the headers stay.
            response.resetBuffer();
            response.setHeader("Cache-Control", "no-store");
            ErrorWriter.write(request, response, 500, "internal_error", "Unexpected error");
        }
    }
}
