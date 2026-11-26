package org.apvgakasinay.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** Scenario 9.8: the safeguard that keeps session identifiers and hashes out of the log at any level. */
@ExtendWith({org.apvgakasinay.support.RestoreLogLevels.class, OutputCaptureExtension.class})
class SensitiveLogFilterTest {

    @Configuration
    static class Nothing {
    }

    @Test
    void a9_8_identifierShapesAndHashesAreReplaced() {
        String id = UUID.randomUUID().toString();
        String cookie = Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.US_ASCII));
        String urlCookie = Base64.getUrlEncoder().encodeToString(id.getBytes(StandardCharsets.US_ASCII));
        assertThat(cookie).hasSize(48);

        assertThat(SensitiveLogFilter.redact("Changed session id from " + id)).isEqualTo("Changed session id from [redacted-id]");
        assertThat(SensitiveLogFilter.redact("Cookie: apv_session=" + cookie + "; other=1")).isEqualTo("Cookie: apv_session=[redacted-id]; other=1");
        assertThat(SensitiveLogFilter.redact("value [" + urlCookie + "]")).doesNotContain(urlCookie);
        assertThat(SensitiveLogFilter.redact("ids " + id + ", " + id.toUpperCase() + " and (" + id + ")")).doesNotContainIgnoringCase(id);
        assertThat(SensitiveLogFilter.redact("parameter value [{argon2id}$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNo], class String"))
                .isEqualTo("parameter value [[redacted-hash]], class String");
        assertThat(SensitiveLogFilter.redact("hash $argon2id$v=19$m=65536,t=3,p=1$abc$def end")).isEqualTo("hash [redacted-hash] end");

        // What is not an identifier is left alone: request identifiers, other Base64 of that length, ordinary text.
        String requestId = "request 5f0c1c9e2b7a4d1f answered 401 for /api/me in 12 ms";
        assertThat(SensitiveLogFilter.redact(requestId)).isEqualTo(requestId);
        String other = Base64.getEncoder().encodeToString("thirty-six characters, not an id....".getBytes(StandardCharsets.US_ASCII));
        assertThat(other).hasSize(48);
        assertThat(SensitiveLogFilter.redact("token " + other)).isEqualTo("token " + other);
        assertThat(SensitiveLogFilter.redact(null)).isNull();
    }

    @Test
    void a9_8_rawInputLoggersAreSilencedBelowInfo() {
        for (String logger : new String[] {"org.apache.coyote.http11.Http11InputBuffer", "org.apache.tomcat.util.http.Rfc6265CookieProcessor",
                "org.apache.catalina.connector.CoyoteAdapter", "org.postgresql.core.v3.QueryExecutorImpl",
                "org.springframework.jdbc.core.StatementCreatorUtils",
                "org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy"}) {
            assertThat(SensitiveLogFilter.neverBelowInfo(logger)).as(logger).isTrue();
        }
        assertThat(SensitiveLogFilter.neverBelowInfo("org.springframework.security.web.FilterChainProxy")).isFalse();
        assertThat(SensitiveLogFilter.neverBelowInfo("org.apvgakasinay.identity.internal.IdentityService")).isFalse();
        assertThat(SensitiveLogFilter.neverBelowInfo("org.apache.coyotes")).isFalse();
    }

    /** The filter as the application installs it: through logback-spring.xml, with every level turned up to TRACE. */
    @Test
    void a9_8_theInstalledConfigurationAppliesItAtEveryLevel(CapturedOutput output) {
        String id = UUID.randomUUID().toString();
        String cookie = Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.US_ASCII));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Nothing.class)
                .web(WebApplicationType.NONE).bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .properties("logging.level.root=TRACE", "logging.level.org.springframework=INFO",
                        "logging.level.org.apache.coyote=TRACE", "logging.level.org.postgresql=TRACE",
                        "logging.level.org.springframework.jdbc.core.StatementCreatorUtils=TRACE",
                        "logging.level.org.springframework.security.web.authentication.session=TRACE")
                .run()) {
            Logger application = LoggerFactory.getLogger("org.apvgakasinay.test.Application");
            application.trace("trace line with session {} and cookie {}", id, cookie);
            application.debug("debug line with session " + id);
            application.info("info line with session [{}]", id);
            application.error("error line with cookie " + cookie, new IllegalStateException("kept"));
            application.info("plain line 7f3a");

            LoggerFactory.getLogger("org.apache.coyote.http11.Http11InputBuffer").debug("Received [POST /api/auth/login marker-raw-request]");
            LoggerFactory.getLogger("org.postgresql.core.v3.QueryExecutorImpl").trace("FE=> Bind marker-raw-bind");
            LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils").trace("Setting SQL statement parameter value marker-raw-parameter");
            LoggerFactory.getLogger("org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy")
                    .debug("Changed session id from marker-raw-rotation");
            // INFO and above from those loggers still passes, redacted like everything else.
            LoggerFactory.getLogger("org.apache.coyote.http11.Http11NioProtocol").warn("warning kept for session " + id);
        }
        String log = output.getAll();
        assertThat(log).contains("trace line with session [redacted-id] and cookie [redacted-id]")
                .contains("debug line with session [redacted-id]").contains("info line with session [[redacted-id]]")
                .contains("error line with cookie [redacted-id]").contains("IllegalStateException: kept")
                .contains("plain line 7f3a").contains("warning kept for session [redacted-id]");
        assertThat(log.contains(id) || log.contains(cookie)).as("an identifier reached the log").isFalse();
        assertThat(log).doesNotContain("marker-raw-request").doesNotContain("marker-raw-bind")
                .doesNotContain("marker-raw-parameter").doesNotContain("marker-raw-rotation");
    }

    /** Finding 4 of the joint acceptance run: the exception attached to a log line is redacted like its message. */
    @Test
    void a9_8_exceptionMessagesAndTheirCausesAreRedacted() {
        String id = UUID.randomUUID().toString();
        String cookie = Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.US_ASCII));
        IllegalStateException cause = new IllegalStateException("ERROR: duplicate key value. Detail: Key (session_id)=(" + id + ") already exists; cookie " + cookie);
        RuntimeException suppressed = new RuntimeException("while closing session " + id);
        RuntimeException thrown = new RuntimeException("PreparedStatementCallback; Key (session_primary_id, attribute_name)=(" + id + ", NAME) and " + cookie, cause);
        thrown.addSuppressed(suppressed);

        Throwable safe = SensitiveLogFilter.redactThrown(thrown);
        assertThat(safe).isNotSameAs(thrown);
        assertThat(safe.getMessage()).isEqualTo("java.lang.RuntimeException: PreparedStatementCallback; Key (session_primary_id, attribute_name)=([redacted-id], NAME) and [redacted-id]");
        assertThat(safe.getCause().getMessage())
                .isEqualTo("java.lang.IllegalStateException: ERROR: duplicate key value. Detail: Key (session_id)=([redacted-id]) already exists; cookie [redacted-id]");
        assertThat(safe.getSuppressed()).hasSize(1);
        assertThat(safe.getSuppressed()[0].getMessage()).isEqualTo("java.lang.RuntimeException: while closing session [redacted-id]");
        assertThat(safe.getStackTrace()).isEqualTo(thrown.getStackTrace());
        assertThat(safe.getCause().getStackTrace()).isEqualTo(cause.getStackTrace());

        // Only a cause, deep down, holds something: the whole chain is still copied.
        Throwable deep = new RuntimeException("outer", new RuntimeException("middle", new IllegalArgumentException("inner " + cookie)));
        Throwable safeDeep = SensitiveLogFilter.redactThrown(deep);
        assertThat(safeDeep).isNotSameAs(deep);
        assertThat(safeDeep.getMessage()).isEqualTo("java.lang.RuntimeException: outer");
        assertThat(safeDeep.getCause().getCause().getMessage()).isEqualTo("java.lang.IllegalArgumentException: inner [redacted-id]");

        // Nothing to replace: the exception itself, untouched. No message, and a cause that is its own cause, are handled.
        RuntimeException plain = new RuntimeException("nothing here", new IllegalStateException());
        assertThat(SensitiveLogFilter.redactThrown(plain)).isSameAs(plain);
        assertThat(SensitiveLogFilter.redactThrown(null)).isNull();
        RuntimeException first = new RuntimeException("first " + id);
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        Throwable loop = SensitiveLogFilter.redactThrown(first);
        assertThat(loop.getMessage()).isEqualTo("java.lang.RuntimeException: first [redacted-id]");
        assertThat(loop.getCause().getCause()).isSameAs(loop);
    }

    /** The same through the installed configuration, as printed: every way an exception can reach a log line. */
    @Test
    void a9_8_noIdentifierInAPrintedStackTrace(CapturedOutput output) {
        String id = UUID.randomUUID().toString();
        String cookie = Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.US_ASCII));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Nothing.class)
                .web(WebApplicationType.NONE).bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .properties("logging.level.org.apvgakasinay.test=TRACE").run()) {
            Logger application = LoggerFactory.getLogger("org.apvgakasinay.test.Application");
            Throwable databaseError = new IllegalStateException("marker-outer Key (session_primary_id, attribute_name)=(" + id + ", NAME)",
                    new IllegalArgumentException("marker-cause Key (session_id)=(" + id + ") for cookie " + cookie));
            application.error("marker-plain-message", databaseError);
            application.warn("marker-with-arguments {} and {}", "one", "two", databaseError);
            application.debug("marker-message-too " + id, databaseError);
            application.error(null, databaseError);
            application.error("marker-harmless", new IllegalStateException("marker-kept-as-it-is"));
        }
        String log = output.getAll();
        assertThat(log.contains(id) || log.contains(cookie)).as("an identifier reached the log inside an exception").isFalse();
        assertThat(log).contains("marker-plain-message").contains("marker-with-arguments one and two").contains("marker-message-too [redacted-id]")
                .contains("java.lang.IllegalStateException: marker-outer Key (session_primary_id, attribute_name)=([redacted-id], NAME)")
                .contains("java.lang.IllegalArgumentException: marker-cause Key (session_id)=([redacted-id]) for cookie [redacted-id]")
                .contains("java.lang.IllegalStateException: marker-kept-as-it-is")
                // The stack trace is still there to debug with.
                .contains("at org.apvgakasinay.shared.SensitiveLogFilterTest.a9_8_noIdentifierInAPrintedStackTrace");
    }

    private static String hex(String text) {
        return java.util.HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Independent review, D6. When a write of session attributes fails, the database driver
     * quotes the statement with its values, and a stored attribute is printed as a hexadecimal
     * literal. Decoded, it can hold a session identifier or a CSRF token. No text pattern sees
     * that, so the literal is replaced whole, in the exception's message and in its causes.
     */
    @Test
    void a9_8_hexadecimalByteLiteralsInExceptionTextAreReplaced(CapturedOutput output) {
        String id = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        // What Java serialization of a String attribute looks like, and of a token object, in hex.
        String boundId = "aced00057400" + hex("\u0024" + id);
        String tokenObject = "aced0005737200" + hex("org.springframework.security.web.csrf.DefaultCsrfToken") + hex(token) + hex("X-CSRF-TOKEN");
        String statement = "Batch entry 0 INSERT INTO SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) "
                + "VALUES (('PRIMARY'), ('apv.boundSessionId'), ('\\x" + boundId + "'::bytea)) was aborted";
        String other = "Batch entry 1 INSERT INTO SPRING_SESSION_ATTRIBUTES VALUES ('PRIMARY', 'CSRF_TOKEN', '\\x" + tokenObject.toUpperCase()
                + "') failed; Key (attribute_bytes)=(\\x" + tokenObject + "); empty E'\\x' and '\\x'::bytea";

        assertThat(SensitiveLogFilter.redact(statement))
                .isEqualTo("Batch entry 0 INSERT INTO SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) "
                        + "VALUES (('PRIMARY'), ('apv.boundSessionId'), ([redacted-bytes])) was aborted");
        assertThat(SensitiveLogFilter.redact(other)).doesNotContainIgnoringCase(tokenObject).doesNotContainIgnoringCase(hex(token))
                .contains("VALUES ('PRIMARY', 'CSRF_TOKEN', [redacted-bytes]) failed").contains("=([redacted-bytes]);")
                .endsWith("empty [redacted-bytes] and [redacted-bytes]");
        // Short hexadecimal that is not a byte literal stays: request identifiers, colours, escapes in ordinary text.
        String ordinary = "request 5f0c1c9e2b7a4d1f took 12 ms; char \\x41; colour 0xdeadbeefdeadbeefdeadbeef";
        assertThat(SensitiveLogFilter.redact(ordinary)).isEqualTo(ordinary);

        RuntimeException cause = new RuntimeException(other);
        RuntimeException thrown = new RuntimeException(statement, cause);
        Throwable safe = SensitiveLogFilter.redactThrown(thrown);
        assertThat(safe).isNotSameAs(thrown);
        assertThat(safe.getMessage() + safe.getCause().getMessage()).doesNotContainIgnoringCase(hex(id)).doesNotContainIgnoringCase(hex(token))
                .contains("[redacted-bytes]");

        // And as it is printed, with its stack trace, by the installed configuration.
        LoggerFactory.getLogger("org.apvgakasinay.test.Application").error("marker-hex: could not save the session", thrown);
        LoggerFactory.getLogger("org.apvgakasinay.test.Application").error("marker-hex-message: " + statement);
        String printed = output.getAll();
        assertThat(printed).contains("marker-hex: could not save the session").contains("marker-hex-message").contains("[redacted-bytes]");
        assertThat(printed.toLowerCase().contains(hex(id)) || printed.toLowerCase().contains(hex(token)))
                .as("a hexadecimal byte literal reached the log").isFalse();
    }
}
