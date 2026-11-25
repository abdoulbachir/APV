package org.apvgakasinay.identity.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apvgakasinay.shared.ApiException;
import tools.jackson.databind.json.JsonMapper;

/**
 * A request body as the contract defines it: a JSON object, closed. A property that is not in
 * the schema is refused, never ignored, which is what stops a request from smuggling in a
 * role or a permission.
 */
final class JsonBody {

    static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
    static final Pattern TOTP_CODE = Pattern.compile("^[0-9]{6}$");
    static final Pattern RECOVERY_CODE = Pattern.compile("^[A-Z2-7]{4}(-?[A-Z2-7]{4}){3}$");

    private final Map<String, Object> values;

    private JsonBody(Map<String, Object> values) {
        this.values = values;
    }

    @SuppressWarnings("unchecked")
    static JsonBody read(HttpServletRequest request, JsonMapper json, Set<String> allowed) {
        byte[] bytes = request instanceof CachedBodyRequest cached ? cached.body() : new byte[0];
        Map<String, Object> parsed;
        try {
            Object value = json.readValue(bytes, Object.class);
            if (!(value instanceof Map<?, ?> object)) {
                throw new IllegalArgumentException();
            }
            parsed = (Map<String, Object>) object;
        } catch (RuntimeException notAnObject) {
            throw ApiException.invalid("body", "invalid_type", "The request body must be a JSON object");
        }
        for (String name : parsed.keySet()) {
            if (!allowed.contains(name)) {
                throw ApiException.invalid(name.length() > 64 ? "body" : name, "unknown_field", "Unknown property");
            }
        }
        return new JsonBody(parsed);
    }

    /** For an operation that defines no body: anything sent is refused. */
    static void requireEmpty(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0) {
            throw ApiException.invalid("body", "invalid_value", "This operation takes no request body");
        }
    }

    boolean has(String name) {
        return values.get(name) != null;
    }

    /** A required text property, with its length counted in Unicode code points. */
    String text(String name, int min, int max) {
        Object value = values.get(name);
        if (value == null) {
            throw ApiException.invalid(name, "required", name + " is required");
        }
        if (!(value instanceof String text)) {
            throw ApiException.invalid(name, "invalid_type", name + " must be text");
        }
        int length = text.codePointCount(0, text.length());
        if (length < min) {
            throw ApiException.invalid(name, length == 0 ? "required" : "too_short", name + " is too short");
        }
        if (length > max) {
            throw ApiException.invalid(name, "too_long", name + " is too long");
        }
        return text;
    }

    String matching(String name, int min, int max, Pattern pattern) {
        String text = text(name, min, max);
        if (!pattern.matcher(text).matches()) {
            throw ApiException.invalid(name, "invalid_format", name + " has the wrong format");
        }
        return text;
    }

    /** The server does not trim: an address with surrounding white space fails the pattern. */
    String email(String name) {
        return matching(name, 6, 254, EMAIL);
    }

    String oneOf(String name, Set<String> choices) {
        String text = text(name, 1, 64);
        if (!choices.contains(text)) {
            throw ApiException.invalid(name, "invalid_value", name + " is not an accepted value");
        }
        return text;
    }
}
