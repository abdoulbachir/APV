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
 * Scenario 9.8 for application logs. The whole journey is run with the application, the
 * security framework, the web framework and the session store logging at DEBUG, far more than
 * production logs; everything written to the console is captured and searched for every
 * secret the journey used.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "logging.level.org.apvgakasinay=DEBUG",
        "logging.level.org.springframework.security=DEBUG",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.org.springframework.session=DEBUG",
        "logging.level.org.springframework.jdbc.core=DEBUG"})
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
        // Identifiers that a rotation replaced during the journey. They stopped working the moment
        // they were replaced, so they are reported separately and not counted as leaked secrets.
        List<String> replaced = new ArrayList<>();

        Browser donor = browser();
        register(donor, "sample.donor@example.org", PASSWORD);
        add(secrets, "CSRF token", donor.token);
        donor.login("sample.donor@example.org", "not the right one at all");
        replaced.add(donor.storedSessionId());
        donor.login("sample.donor@example.org", PASSWORD);
        add(secrets, "CSRF token", donor.token);
        replaced.add(donor.storedSessionId());
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
        replaced.add(owner.storedSessionId());
        String shown = owner.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText();
        add(secrets, "authenticator secret", shown);
        byte[] secret = base32Decode(shown);
        Browser.Answer confirmed = owner.post("/api/auth/mfa/confirm", json("code", code(secret)));
        assertThat(confirmed.status()).isEqualTo(200);
        confirmed.json().get("codes").forEach(node -> add(secrets, "recovery code", node.asText()));
        add(secrets, "session cookie value", owner.sessionId);
        add(secrets, "live session identifier", owner.storedSessionId());
        nextCode();
        Browser second = pending(new Staff("sample.owner@example.org", owner, secret, List.of()));
        replaced.add(second.storedSessionId());
        second.post("/api/auth/mfa/verify", json("method", "recovery_code", "code", "AAAA-BBBB-CCCC-DDDD"));
        assertThat(second.post("/api/auth/mfa/verify", json("method", "recovery_code", "code", confirmed.json().get("codes").get(0).asText())).status()).isEqualTo(200);
        add(secrets, "session cookie value", second.sessionId);
        add(secrets, "live session identifier", second.storedSessionId());
        second.csrf();
        add(secrets, "CSRF token", second.token);
        Browser.Answer regenerated = second.post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", code(secret)));
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

        String log = output.getAll();
        assertThat(log).as("the capture is working and the log is verbose").contains("/api/auth/login").contains("DEBUG");
        // Never the value in a failure message: only what kind of value it was.
        List<String> found = new ArrayList<>();
        for (String[] secret : secrets) {
            if (secret[1] != null && log.contains(secret[1])) {
                found.add(secret[0]);
            }
        }
        long replacedLogged = replaced.stream().filter(log::contains).count();
        assertThat(found).as("kinds of secret found in %d characters of log, out of %d values searched for", log.length(), secrets.size()).isEmpty();
        Evidence.write("log-inspection", String.format(
                "Captured %d characters of application log at DEBUG (application, Spring Security, Spring Web, Spring Session, JDBC).%n"
                        + "Searched for %d values used in the journey: passwords, CSRF tokens, session identifiers (cookie and stored form),%n"
                        + "one-time credentials, the authenticator secret and every recovery code. Found: 0.%n"
                        + "Session identifiers that a rotation had already replaced (dead when logged), found: %d of %d. At DEBUG the security%n"
                        + "framework logs the identifier it has just replaced; production logs at INFO, where it does not.%n"
                        + "Six-digit authenticator codes are not searched for: six digits occur by chance in timestamps and identifiers.%n",
                log.length(), secrets.size(), replacedLogged, replaced.size()));
    }
}
