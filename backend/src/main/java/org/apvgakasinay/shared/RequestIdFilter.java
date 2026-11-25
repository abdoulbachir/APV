package org.apvgakasinay.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an identifier (quoted in error bodies and logs) and sets the caching
 * rule of public API responses: reusable only after checking with the server.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "apv.requestId";

    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        String requestId = HexFormat.of().formatHex(bytes);
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
