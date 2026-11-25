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

    @Test
    void a9_8_noSecretIsWrittenToTheApplicationLog(CapturedOutput output) {
        List<String> secrets = new ArrayList<>(List.of(PASSWORD, OTHER_PASSWORD, "not the right one at all"));
        // Identifiers that a rotation replaced during the journey. They stopped working the moment
        // they were replaced, so they are reported separately and not counted as leaked secrets.
        List<String> replaced = new ArrayList<>();

        Browser donor = browser();
        register(donor, "sample.donor@example.org", PASSWORD);
        secrets.add(donor.token);
        donor.login("sample.donor@example.org", "not the right one at all");
        replaced.add(donor.storedSessionId());
        donor.login("sample.donor@example.org", PASSWORD);
        secrets.add(donor.token);
        replaced.add(donor.storedSessionId());
        donor.post("/api/auth/change-password", json("currentPassword", "not the right one at all", "newPassword", OTHER_PASSWORD));
        donor.post("/api/auth/change-password", "this is not json but it holds " + PASSWORD);
        assertThat(donor.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)).status()).isEqualTo(204);
        secrets.add(donor.sessionId);
        secrets.add(donor.storedSessionId());
        donor.csrf();
        secrets.add(donor.token);

        String activation = createStaff("sample.owner@example.org", "super_admin");
        secrets.add(activation);
        Browser owner = browser();
        owner.csrf();
        owner.post("/api/auth/set-password", json("credential", activation, "newPassword", PASSWORD));
        owner.post("/api/auth/set-password", json("credential", activation, "newPassword", PASSWORD));
        owner.login("sample.owner@example.org", PASSWORD);
        replaced.add(owner.storedSessionId());
        String shown = owner.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText();
        secrets.add(shown);
        byte[] secret = base32Decode(shown);
        Browser.Answer confirmed = owner.post("/api/auth/mfa/confirm", json("code", code(secret)));
        assertThat(confirmed.status()).isEqualTo(200);
        confirmed.json().get("codes").forEach(node -> secrets.add(node.asText()));
        secrets.add(owner.sessionId);
        secrets.add(owner.storedSessionId());
        nextCode();
        Browser second = pending(new Staff("sample.owner@example.org", owner, secret, List.of()));
        replaced.add(second.storedSessionId());
        second.post("/api/auth/mfa/verify", json("method", "recovery_code", "code", "AAAA-BBBB-CCCC-DDDD"));
        assertThat(second.post("/api/auth/mfa/verify", json("method", "recovery_code", "code", confirmed.json().get("codes").get(0).asText())).status()).isEqualTo(200);
        secrets.add(second.sessionId);
        secrets.add(second.storedSessionId());
        second.csrf();
        secrets.add(second.token);
        Browser.Answer regenerated = second.post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", code(secret)));
        assertThat(regenerated.status()).as(regenerated.body()).isEqualTo(200);
        regenerated.json().get("codes").forEach(node -> secrets.add(node.asText()));
        String reset = staffAdministration.issuePasswordReset("sample.donor@example.org", "test-operator", "in person");
        secrets.add(reset);
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
        int found = 0;
        for (String value : secrets) {
            // The value itself is never put in a failure message: only its position in the list.
            if (value != null && log.contains(value)) {
                found++;
            }
        }
        long replacedLogged = replaced.stream().filter(log::contains).count();
        assertThat(found).as("secrets found in %d characters of log, out of %d searched for", log.length(), secrets.size()).isZero();
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
