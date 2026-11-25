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
        String text = body(code, message, RequestIdFilter.idOf(request, response));
        try {
            response.getWriter().write(text);
        } catch (IllegalStateException streamInUse) {
            // Something had already asked for the byte stream before it failed.
            response.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The envelope as text. Code, message and identifier are fixed text or hex digits: nothing needs escaping. */
    public static String body(String code, String message, String requestId) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\"" + requestId + "\"}}";
    }

    /** The contract's code for a status that something other than this application's own code chose. */
    public static String codeFor(int status) {
        return switch (status) {
            case 400 -> "validation_error";
            case 401 -> "unauthenticated";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 413 -> "payload_too_large";
            case 415 -> "unsupported_media_type";
            case 429 -> "rate_limited";
            case 503 -> "unavailable";
            default -> status >= 500 ? "internal_error" : "validation_error";
        };
    }

    /** Fixed developer text for each of those codes. */
    public static String messageFor(String code) {
        return switch (code) {
            case "validation_error" -> "Malformed request";
            case "unauthenticated" -> "Sign-in required";
            case "forbidden" -> "Not allowed";
            case "not_found" -> "Not found";
            case "method_not_allowed" -> "Method not allowed";
            case "payload_too_large" -> "Request body too large";
            case "unsupported_media_type" -> "Unsupported media type";
            case "rate_limited" -> "Too many requests";
            case "unavailable" -> "Temporarily unavailable";
            default -> "Unexpected error";
        };
    }
}
