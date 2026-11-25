package org.apvgakasinay.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes the contract's error envelope from a servlet filter, where no controller advice runs.
 * The message is fixed text chosen by the caller, never data from the request.
 */
public final class ErrorWriter {

    private ErrorWriter() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String requestId = String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
        response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\"" + requestId + "\"}}");
    }
}
