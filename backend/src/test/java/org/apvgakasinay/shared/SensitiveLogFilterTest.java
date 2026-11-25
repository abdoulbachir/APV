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
@ExtendWith(OutputCaptureExtension.class)
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
}
