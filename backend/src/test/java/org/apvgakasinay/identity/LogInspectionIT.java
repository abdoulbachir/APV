package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * Scenario 9.8 for application logs. The whole journey is run with everything that could print
 * a request, a session or a statement turned up to TRACE, far beyond what production logs:
 * the application, Spring Security, Spring Session, Spring Web, Spring JDBC and transactions,
 * the embedded server, the connection pool and the database driver. Everything written to the
 * console is captured and searched for every secret the journey used. There is no exemption:
 * a session identifier that was replaced or deleted is searched for exactly like a live one,
 * in the form the store keeps and in the form the cookie carries.
 */
@ExtendWith({org.apvgakasinay.support.RestoreLogLevels.class, OutputCaptureExtension.class})
@TestPropertySource(properties = {
        "logging.level.org.apvgakasinay=TRACE",
        "logging.level.org.springframework.security=TRACE",
        "logging.level.org.springframework.session=TRACE",
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.springframework.jdbc=TRACE",
        "logging.level.org.springframework.transaction=TRACE",
        "logging.level.org.apache.coyote=TRACE",
        "logging.level.org.apache.tomcat=TRACE",
        "logging.level.org.apache.catalina=TRACE",
        "logging.level.org.postgresql=TRACE",
        "logging.level.com.zaxxer.hikari=TRACE"})
class LogInspectionIT extends IdentityIntegration {

    private static void add(List<String[]> secrets, String kind, String value) {
        secrets.add(new String[] {kind, value});
    }

    @Test
    void a9_8_noSecretIsWrittenToTheApplicationLog(CapturedOutput output) {
        List<String[]> secrets = new ArrayList<>();
        add(secrets, "password", PASSWORD);
        add(secrets, "password", OTHER_PASSWORD);
        add(secrets, "wrong password", "not the right one at all");
        List<String> codes = new ArrayList<>();

        Browser donor = browser();
        register(donor, "sample.donor@example.org", PASSWORD);
        add(secrets, "CSRF token", donor.token);
        add(secrets, "anonymous session identifier", donor.storedSessionId());
        add(secrets, "anonymous session cookie value", donor.sessionId);
        donor.login("sample.donor@example.org", "not the right one at all");
        add(secrets, "retired session identifier", donor.storedSessionId());
        add(secrets, "retired session cookie value", donor.sessionId);
        donor.login("sample.donor@example.org", PASSWORD);
        add(secrets, "CSRF token", donor.token);
        add(secrets, "retired session identifier", donor.storedSessionId());
        add(secrets, "retired session cookie value", donor.sessionId);
        donor.post("/api/auth/change-password", json("currentPassword", "not the right one at all", "newPassword", OTHER_PASSWORD));
        donor.post("/api/auth/change-password", "this is not json but it holds " + PASSWORD);
        assertThat(donor.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)).status()).isEqualTo(204);
        add(secrets, "session cookie value", donor.sessionId);
        add(secrets, "live session identifier", donor.storedSessionId());
        donor.csrf();
        add(secrets, "CSRF token", donor.token);

        String activation = createStaff("sample.owner@example.org", "super_admin");
        add(secrets, "one-time credential", activation);
        Browser owner = browser();
        owner.csrf();
        owner.post("/api/auth/set-password", json("credential", activation, "newPassword", PASSWORD));
        owner.post("/api/auth/set-password", json("credential", activation, "newPassword", PASSWORD));
        owner.login("sample.owner@example.org", PASSWORD);
        add(secrets, "retired session identifier", owner.storedSessionId());
        add(secrets, "retired session cookie value", owner.sessionId);
        String shown = owner.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText();
        add(secrets, "authenticator secret", shown);
        byte[] secret = base32Decode(shown);
        codes.add(code(secret));
        Browser.Answer confirmed = owner.post("/api/auth/mfa/confirm", json("code", codes.get(0)));
        assertThat(confirmed.status()).isEqualTo(200);
        confirmed.json().get("codes").forEach(node -> add(secrets, "recovery code", node.asText()));
        add(secrets, "session cookie value", owner.sessionId);
        add(secrets, "live session identifier", owner.storedSessionId());
        nextCode();
        Browser second = pending(new Staff("sample.owner@example.org", owner, secret, List.of()));
        add(secrets, "retired session identifier", second.storedSessionId());
        add(secrets, "retired session cookie value", second.sessionId);
        second.post("/api/auth/mfa/verify", json("method", "recovery_code", "code", "AAAA-BBBB-CCCC-DDDD"));
        // A wrong authenticator code that is not all zeros, so it can be told from padding.
        codes.add(code(secret).equals("481516") ? "481517" : "481516");
        second.post("/api/auth/mfa/verify", json("method", "totp", "code", codes.get(1)));
        assertThat(second.post("/api/auth/mfa/verify", json("method", "recovery_code", "code", confirmed.json().get("codes").get(0).asText())).status()).isEqualTo(200);
        add(secrets, "session cookie value", second.sessionId);
        add(secrets, "live session identifier", second.storedSessionId());
        second.csrf();
        add(secrets, "CSRF token", second.token);
        codes.add(code(secret));
        Browser.Answer regenerated = second.post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", codes.get(2)));
        assertThat(regenerated.status()).as(regenerated.body()).isEqualTo(200);
        regenerated.json().get("codes").forEach(node -> add(secrets, "recovery code", node.asText()));
        String reset = staffAdministration.issuePasswordReset("sample.donor@example.org", "test-operator", "in person");
        add(secrets, "one-time credential", reset);
        // A forced server error, which is where a careless log would print a request.
        jdbc.sql("alter table account_password rename to account_password_hidden").update();
        try {
            Browser failing = browser();
            failing.csrf();
            assertThat(failing.post("/api/auth/login", json("email", "sample.donor@example.org", "password", OTHER_PASSWORD)).status()).isEqualTo(500);
        } finally {
            jdbc.sql("alter table account_password_hidden rename to account_password").update();
        }
        donor.postEmpty("/api/auth/logout");

        // The sessions that were signed out or left behind are searched for as well.
        add(secrets, "ended session identifier", donor.sessionId == null ? null : donor.storedSessionId());

        String log = output.getAll();
        assertThat(log).as("the capture is working and the log is verbose").contains("/api/auth/login").contains("TRACE").contains("DEBUG");
        // Never the value in a failure message: only what kind of value it was.
        List<String> found = new ArrayList<>();
        for (String[] entry : secrets) {
            if (entry[1] != null && log.contains(entry[1])) {
                found.add(entry[0]);
            }
        }
        // Authenticator codes are six digits, and six digits occur by chance inside timestamps, identifiers and
        // numbers. A code counts as logged only when it stands alone as a value: not part of a longer run of
        // letters or digits, and not the fraction of a timestamp or part of a dotted or dashed number.
        int codesLogged = 0;
        int incidental = 0;
        for (String code : codes) {
            if (java.util.regex.Pattern.compile("(?<![0-9A-Za-z.:,_+-])" + code + "(?![0-9A-Za-z_+-])").matcher(log).find()) {
                codesLogged++;
            } else if (log.contains(code)) {
                incidental++;
            }
        }
        if (codesLogged > 0) {
            found.add(codesLogged + " authenticator code(s) as a standalone value");
        }
        assertThat(found).as("kinds of secret found in %d characters of log, out of %d values and %d codes searched for",
                log.length(), secrets.size(), codes.size()).isEmpty();
        // The safeguard was at work, not idle: identifiers were written by the frameworks and replaced.
        assertThat(log).contains("[redacted-id]");
        Evidence.write("log-inspection", String.format(
                "Captured %d characters of log at TRACE: application, Spring Security, Session, Web, JDBC and transactions, embedded server,%n"
                        + "connection pool and database driver.%n"
                        + "Searched for %d values used in the journey: passwords, CSRF tokens, every session identifier the journey ever had%n"
                        + "(anonymous, pending, signed-in; live, replaced by rotation, ended), each in stored form and in cookie form,%n"
                        + "one-time credentials, the authenticator secret and every recovery code. Found: 0. No exemption for retired identifiers.%n"
                        + "Authenticator codes: %d searched for as standalone values, found: 0. Occurrences inside longer numbers (not a logged code): %d.%n"
                        + "Lines in which an identifier shape or a hash was replaced before logging: %d.%n",
                log.length(), secrets.size(), codes.size(), incidental, log.lines().filter(line -> line.contains("[redacted-")).count()));
    }
}
