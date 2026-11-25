package org.apvgakasinay.identity.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * The address a request came from, for rate limits and the audit log. It is the address of the
 * connection. A forwarded-for header is believed only when the connection itself comes from a
 * configured reverse proxy, and then only its right-most entry, the one that proxy added.
 */
final class ClientAddress {

    private ClientAddress() {
    }

    static String of(HttpServletRequest request, List<String> trustedProxies) {
        String connection = request.getRemoteAddr();
        if (trustedProxies.contains(connection)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Whatever follows the last comma, which is nothing at all when the header ends with one.
                String last = forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
                if (last.matches("[0-9A-Fa-f:.]{2,45}")) {
                    return last;
                }
            }
        }
        return connection;
    }
}
