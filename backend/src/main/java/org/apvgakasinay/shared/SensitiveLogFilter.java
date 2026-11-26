package org.apvgakasinay.shared;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

/**
 * Keeps session identifiers, password hashes and raw requests out of the log, at every level
 * and whatever the logging configuration says. Installed by logback-spring.xml, so it applies
 * to the server and to the operator command alike, before any appender or layout.
 *
 * Three mechanisms, because none is enough alone:
 *
 * 1. Some loggers print raw input and output when turned up: the HTTP connector prints
 *    request bytes (cookies, tokens, bodies with passwords), the database driver and the SQL
 *    parameter logger print bound values (session identifiers, hashes), and the session
 *    fixation strategy prints the identifier it has just replaced. Nothing below INFO from
 *    these is ever logged; setting their level to DEBUG or TRACE has no effect.
 *
 * 2. Every other message is searched for the shapes a session identifier and a password hash
 *    have, and they are replaced before the message is logged: a UUID (the form the session
 *    store uses), the Base64 of a UUID (the form the cookie carries), and an Argon2 hash. A
 *    retired or expired identifier has the same shape as a live one, so it is covered equally.
 *    Bytes written as a hexadecimal literal are replaced whole, whatever they hold: that is
 *    how the database driver quotes a stored session attribute when a write fails, and such a
 *    value can be a serialized identifier or CSRF token that no text pattern would recognise.
 *
 * 3. The exception attached to a log line is treated like the message: its own message and
 *    those of its causes and suppressed exceptions are searched for the same shapes. A
 *    database error quotes the row it failed on, and that row can be a session's. When
 *    anything is replaced the exception is printed as a redacted copy with the same stack trace.
 *
 * What this cannot do: recognise a password, a code or a one-time credential in free text;
 * they have no shape. Those are kept out of the log by never being handed to a logger: request
 * bodies are read by the identity module itself, and the objects that carry secrets print
 * nothing of their contents.
 */
public final class SensitiveLogFilter extends TurboFilter {

    /** Loggers that print raw requests, bound SQL values or session identifiers below INFO. */
    static final List<String> NEVER_BELOW_INFO = List.of(
            "org.apache.coyote",
            "org.apache.tomcat",
            "org.apache.catalina",
            "org.postgresql",
            "com.zaxxer.hikari.pool.ProxyConnection",
            "org.springframework.jdbc.core.StatementCreatorUtils",
            "org.springframework.security.web.authentication.session");

    static final String ID = "[redacted-id]";
    static final String HASH = "[redacted-hash]";
    static final String BYTES = "[redacted-bytes]";

    private static final Pattern UUID = Pattern.compile(
            "(?<![0-9A-Za-z])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9A-Za-z])");
    /** A UUID is 36 characters, so its Base64 is exactly 48 with no padding. Checked by decoding. */
    private static final Pattern BASE64_OF_36 = Pattern.compile("(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{48}(?![A-Za-z0-9+/_=-])");
    private static final Pattern ARGON2 = Pattern.compile("(\\{argon2[a-z]*\\})?\\$argon2[a-z]*\\$[A-Za-z0-9+/=$,]+");
    /**
     * Bytes in hexadecimal: the quoted literal PostgreSQL and its driver print ('\x…', with or
     * without the ::bytea cast, empty included), and any other \x followed by eight bytes or more.
     */
    private static final Pattern HEX_BYTES = Pattern.compile("E?'\\\\x[0-9a-fA-F]*'(?:::bytea)?|\\\\x[0-9a-fA-F]{16,}");
    private static final Marker REDACTED = MarkerFactory.getMarker("APV_REDACTED");
    private static final String SELF = SensitiveLogFilter.class.getName();

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (level == null || logger == null) {
            return FilterReply.NEUTRAL;
        }
        if (level.toInt() < Level.INFO_INT && neverBelowInfo(logger.getName())) {
            return FilterReply.DENY;
        }
        // Calls that only ask "is this level enabled" carry nothing, and nothing is formatted for a level that is off.
        if ((format == null && t == null) || marker == REDACTED || !level.isGreaterOrEqual(logger.getEffectiveLevel())) {
            return FilterReply.NEUTRAL;
        }
        String message = format;
        Throwable thrown = t;
        if (format != null && params != null && params.length > 0) {
            FormattingTuple formatted = MessageFormatter.arrayFormat(format, params);
            message = formatted.getMessage();
            if (thrown == null) {
                // The convention of the logging interface: a last argument that is an exception is the exception.
                thrown = formatted.getThrowable();
            }
        }
        String safe = redact(message);
        Throwable safeThrown = redactThrown(thrown);
        if (safeThrown == thrown && (safe == null || safe.equals(message))) {
            return FilterReply.NEUTRAL;
        }
        // Logged again, redacted and already formatted; the original is dropped.
        logger.log(REDACTED, SELF, Level.toLocationAwareLoggerInteger(level), safe, null, safeThrown);
        return FilterReply.DENY;
    }

    /**
     * The exception as it may be logged. An exception is printed with its message and the
     * messages of its causes and suppressed exceptions; a database error, for one, quotes the
     * values of the row it failed on. When none of those messages holds anything to replace,
     * the exception itself is returned. Otherwise a copy of the whole chain is returned in
     * which every message is redacted; each copy keeps the original's stack trace and names
     * the original's class at the start of its message.
     */
    static Throwable redactThrown(Throwable thrown) {
        if (thrown == null || !anyMessageChanges(thrown, newIdentitySet())) {
            return thrown;
        }
        return copy(thrown, new IdentityHashMap<>());
    }

    private static Set<Throwable> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static boolean anyMessageChanges(Throwable thrown, Set<Throwable> seen) {
        if (thrown == null || !seen.add(thrown)) {
            return false;
        }
        String message = thrown.getMessage();
        if (message != null && !message.equals(redact(message))) {
            return true;
        }
        if (anyMessageChanges(thrown.getCause(), seen)) {
            return true;
        }
        for (Throwable suppressed : thrown.getSuppressed()) {
            if (anyMessageChanges(suppressed, seen)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable copy(Throwable thrown, Map<Throwable, Throwable> copies) {
        if (thrown == null) {
            return null;
        }
        Throwable known = copies.get(thrown);
        if (known != null) {
            return known;
        }
        String message = thrown.getMessage();
        Redacted copy = new Redacted(thrown.getClass().getName() + (message == null ? "" : ": " + redact(message)));
        copies.put(thrown, copy);
        copy.setStackTrace(thrown.getStackTrace());
        Throwable cause = copy(thrown.getCause(), copies);
        if (cause != null && cause != copy) {
            copy.initCause(cause);
        }
        for (Throwable suppressed : thrown.getSuppressed()) {
            Throwable safe = copy(suppressed, copies);
            if (safe != copy) {
                copy.addSuppressed(safe);
            }
        }
        return copy;
    }

    /** Stands in the log for an exception whose message held an identifier or a hash. */
    static final class Redacted extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Redacted(String message) {
            super(message);
        }
    }

    static boolean neverBelowInfo(String logger) {
        for (String prefix : NEVER_BELOW_INFO) {
            if (logger.equals(prefix) || logger.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /** The message with every session-identifier shape, password hash and hexadecimal byte literal replaced. */
    static String redact(String message) {
        if (message == null || message.length() < 4) {
            return message;
        }
        String result = message;
        if (result.indexOf("\\x") >= 0) {
            // First, so that nothing later can match inside a literal and leave the rest of it.
            result = HEX_BYTES.matcher(result).replaceAll(Matcher.quoteReplacement(BYTES));
        }
        if (result.indexOf('-') >= 0) {
            result = UUID.matcher(result).replaceAll(Matcher.quoteReplacement(ID));
        }
        if (result.indexOf("$argon2") >= 0) {
            result = ARGON2.matcher(result).replaceAll(Matcher.quoteReplacement(HASH));
        }
        if (result.length() >= 48) {
            Matcher candidate = BASE64_OF_36.matcher(result);
            StringBuilder out = null;
            while (candidate.find()) {
                if (encodesUuid(candidate.group())) {
                    if (out == null) {
                        out = new StringBuilder();
                    }
                    candidate.appendReplacement(out, Matcher.quoteReplacement(ID));
                }
            }
            if (out != null) {
                candidate.appendTail(out);
                result = out.toString();
            }
        }
        return result;
    }

    private static boolean encodesUuid(String text) {
        try {
            byte[] decoded = text.indexOf('-') >= 0 || text.indexOf('_') >= 0
                    ? Base64.getUrlDecoder().decode(text) : Base64.getDecoder().decode(text);
            return decoded.length == 36 && UUID.matcher(new String(decoded, StandardCharsets.ISO_8859_1)).matches();
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }
}
