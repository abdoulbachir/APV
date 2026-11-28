package org.apvgakasinay.donations.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.ApiOperation;
import tools.jackson.databind.json.JsonMapper;

/**
 * A request body as the contract defines it: a JSON object, closed at every level. A property
 * that is not in the schema is refused, never ignored, so a report cannot carry a state, a
 * confirmed amount, an account, a permission or an identifier of its own
 * (contract/donation-lifecycle.md, 3.3).
 *
 * <p>Text follows one rule everywhere: Unicode NFC, no control characters, no surrounding white
 * space, and never a payment card number, which is refused before anything else is said about
 * the value and is neither stored nor logged.
 */
final class JsonRequest {

    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{22,64}$");
    static final Pattern CASE_CODE = Pattern.compile("^[A-Za-z]{2,8}(-[0-9]{1,6})?$");
    static final Pattern DONATION_ID = Pattern.compile("^don_[A-Za-z0-9]{22}$");
    static final Pattern DETAILS_ID = Pattern.compile("^pmd_[A-Za-z0-9]{22}$");
    static final Pattern METHOD_ID = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    static final long MAX_AMOUNT = 10_000_000_000L;

    private final String prefix;
    private final Map<String, Object> values;
    private final byte[] bytes;

    private JsonRequest(String prefix, Map<String, Object> values, byte[] bytes) {
        this.prefix = prefix;
        this.values = values;
        this.bytes = bytes;
    }

    @SuppressWarnings("unchecked")
    static JsonRequest read(HttpServletRequest request, JsonMapper json, Set<String> allowed) {
        // The pipeline read the body once, at step 3, and handed its bytes over.
        byte[] bytes = request.getAttribute(ApiOperation.BODY_ATTRIBUTE) instanceof byte[] cached ? cached : new byte[0];
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
        closed("", parsed, allowed);
        return new JsonRequest("", parsed, bytes);
    }

    private static void closed(String prefix, Map<String, Object> object, Set<String> allowed) {
        for (String name : object.keySet()) {
            if (!allowed.contains(name)) {
                throw ApiException.invalid(name.length() > 64 ? "body" : prefix + name, "unknown_field", "Unknown property");
            }
        }
    }

    /** For an operation that defines no body: anything sent is refused. */
    static void requireEmpty(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0) {
            throw ApiException.invalid("body", "invalid_value", "This operation takes no request body");
        }
    }

    /** The Idempotency-Key header: present and well formed, or 400 naming the header as it is written. */
    static String idempotencyKey(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isEmpty()) {
            throw ApiException.invalid("Idempotency-Key", "required", "Idempotency-Key is required");
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiException.invalid("Idempotency-Key", "invalid_format", "Idempotency-Key has the wrong format");
        }
        return key;
    }

    /** The bytes of the body as sent: what a repeated request is recognised by. */
    byte[] bytes() {
        return bytes;
    }

    boolean has(String name) {
        return values.get(name) != null;
    }

    /** A text property exactly as sent, for a value that is judged by being looked up, not by its form. */
    String rawText(String name) {
        Object value = values.get(name);
        if (!(value instanceof String text)) {
            throw ApiException.invalid(prefix + name, "invalid_type", prefix + name + " must be text");
        }
        return text;
    }

    /** A nested object, closed like the body itself. Null when absent and not required. */
    @SuppressWarnings("unchecked")
    JsonRequest object(String name, Set<String> allowed, boolean required) {
        Object value = values.get(name);
        if (value == null) {
            if (required) {
                throw ApiException.invalid(prefix + name, "required", prefix + name + " is required");
            }
            return null;
        }
        if (!(value instanceof Map<?, ?> object)) {
            throw ApiException.invalid(prefix + name, "invalid_type", prefix + name + " must be an object");
        }
        closed(prefix + name + ".", (Map<String, Object>) object, allowed);
        return new JsonRequest(prefix + name + ".", (Map<String, Object>) object, bytes);
    }

    /**
     * A text property. Its length is counted in Unicode code points.
     *
     * @param lines true for a note, which may hold line breaks; every other control character is refused everywhere
     */
    String text(String name, int min, int max, boolean required, boolean lines) {
        String field = prefix + name;
        Object value = values.get(name);
        if (value == null) {
            if (required) {
                throw ApiException.invalid(field, "required", field + " is required");
            }
            return null;
        }
        if (!(value instanceof String text)) {
            throw ApiException.invalid(field, "invalid_type", field + " must be text");
        }
        if (isCardNumber(text.strip())) {
            // Named, never echoed: the site never holds a card number.
            throw ApiException.invalid(field, "card_number", field + " looks like a payment card number, which this site never accepts");
        }
        int length = text.codePointCount(0, text.length());
        if (length == 0 && required) {
            throw ApiException.invalid(field, "required", field + " is required");
        }
        if (!text.equals(text.strip()) || !Normalizer.isNormalized(text, Normalizer.Form.NFC) || hasControl(text, lines)) {
            throw ApiException.invalid(field, "invalid_format", field + " has surrounding white space, control characters or is not normalized");
        }
        if (length < min) {
            throw ApiException.invalid(field, "too_short", field + " is too short");
        }
        if (length > max) {
            throw ApiException.invalid(field, "too_long", field + " is too long");
        }
        return text;
    }

    String text(String name, int min, int max, boolean required) {
        return text(name, min, max, required, false);
    }

    /** A text property that must match a pattern, such as an identifier. */
    String matching(String name, Pattern pattern, boolean required) {
        String field = prefix + name;
        Object value = values.get(name);
        if (value == null) {
            if (required) {
                throw ApiException.invalid(field, "required", field + " is required");
            }
            return null;
        }
        if (!(value instanceof String text)) {
            throw ApiException.invalid(field, "invalid_type", field + " must be text");
        }
        if (text.length() > 64 || !pattern.matcher(text).matches()) {
            throw ApiException.invalid(field, "invalid_format", field + " has the wrong format");
        }
        return text;
    }

    String oneOf(String name, Set<String> choices) {
        String field = prefix + name;
        Object value = values.get(name);
        if (value == null) {
            throw ApiException.invalid(field, "required", field + " is required");
        }
        if (!(value instanceof String text)) {
            throw ApiException.invalid(field, "invalid_type", field + " must be text");
        }
        if (!choices.contains(text)) {
            throw ApiException.invalid(field, "invalid_value", field + " is not an accepted value");
        }
        return text;
    }

    /** An amount in integer US cents, 1 to 10,000,000,000. */
    long amount(String name) {
        String field = prefix + name;
        Object value = values.get(name);
        if (value == null) {
            throw ApiException.invalid(field, "required", field + " is required");
        }
        if (value instanceof java.math.BigInteger) {
            throw ApiException.invalid(field, "out_of_range", field + " must be between 1 and " + MAX_AMOUNT);
        }
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw ApiException.invalid(field, "invalid_type", field + " must be a whole number of cents");
        }
        long amount = ((Number) value).longValue();
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw ApiException.invalid(field, "out_of_range", field + " must be between 1 and " + MAX_AMOUNT);
        }
        return amount;
    }

    /** A calendar day written YYYY-MM-DD. Whether the day is plausible is the operation's rule. */
    LocalDate date(String name) {
        String field = prefix + name;
        Object value = values.get(name);
        if (value == null) {
            throw ApiException.invalid(field, "required", field + " is required");
        }
        if (!(value instanceof String text)) {
            throw ApiException.invalid(field, "invalid_type", field + " must be a date written YYYY-MM-DD");
        }
        try {
            if (text.length() != 10) {
                throw new DateTimeParseException("length", text, 0);
            }
            return LocalDate.parse(text);
        } catch (DateTimeParseException notADate) {
            throw ApiException.invalid(field, "invalid_format", field + " must be a date written YYYY-MM-DD");
        }
    }

    private static boolean hasControl(String text, boolean lines) {
        return text.codePoints().anyMatch(point -> {
            if (lines && point == '\n') {
                return false;
            }
            int type = Character.getType(point);
            return type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE || type == Character.UNASSIGNED;
        });
    }

    /** 13 to 19 digits once spaces and dashes are removed, with a valid check digit (3.3). */
    static boolean isCardNumber(String text) {
        String digits = text.replace(" ", "").replace("-", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int digit = c - '0';
            if (twice) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            twice = !twice;
        }
        return sum % 10 == 0;
    }
}
