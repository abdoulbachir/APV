package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apvgakasinay.identity.internal.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Corrective review of milestone 1B, findings 1 to 3 and 5: what happens when requests and
 * account changes overlap. Two techniques. Where order does not matter for the rule, requests
 * are simply sent at the same moment, several rounds. Where a precise interleaving is the
 * point (a request that verified a secret, then finds the account changed), the test holds
 * the account's row lock, lets the request run into it, changes the account, and commits.
 */
class ConcurrencyCorrectionsIT extends IdentityIntegration {

    private static final String VERIFY = "/api/auth/mfa/verify";

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    PasswordHasher hasher;

    private String totp(String code) {
        return json("method", "totp", "code", code);
    }

    private long principalSessions(String email) {
        return jdbc.sql("""
                select count(*) from spring_session s join account a on a.public_id = s.principal_name where a.email = :email
                """).param("email", email).query(Long.class).single();
    }

    /**
     * Holds the account's row lock, starts the request, waits until it is blocked on that lock,
     * applies the change, commits, and returns the request's answer.
     */
    private Browser.Answer whileAccountIsBeingChanged(String email, Callable<Browser.Answer> request, Runnable change) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Browser.Answer> answer = new TransactionTemplate(transactions).execute(status -> {
                jdbc.sql("select id from account where email = :email for update").param("email", email).query(Long.class).single();
                Future<Browser.Answer> running = pool.submit(request);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                // The request has verified what it verifies and now waits for the row.
                while (count("select count(*) from pg_locks where not granted") == 0) {
                    assertThat(running.isDone()).as("the request finished without waiting for the account row").isFalse();
                    assertThat(System.nanoTime()).as("the request never reached the account row").isLessThan(deadline);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                }
                change.run();
                return running;
            });
            return answer.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ finding 1: the five-attempt budget

    @Test
    void a6_10_wrongCodesSentTogetherAreCountedExactly() {
        for (int round = 0; round < 2; round++) {
            Staff admin = staff("sample.admin" + round + "@example.org", "admin");
            Browser browser = pending(admin);
            long step = count("select max(last_accepted_step) from account_totp");
            List<Callable<Browser.Answer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Browser tab = sameSession(browser);
                calls.add(() -> tab.post(VERIFY, totp("000000")));
            }
            List<Browser.Answer> answers = together(calls);
            // Exactly four are told the code is wrong. The fifth ends the sign-in; whatever comes after finds no session.
            assertThat(answers.stream().filter(a -> "mfa_code_invalid".equals(a.code())).count()).as("round %d", round).isEqualTo(4);
            for (Browser.Answer answer : answers) {
                assertThat(answer.code()).isIn("mfa_code_invalid", "unauthenticated", "csrf_invalid");
            }
            assertThat(answers.stream().filter(a -> "unauthenticated".equals(a.code())).count()).isGreaterThanOrEqualTo(1);
            assertThat(count("select count(*) from pending_sign_in where outcome = 'exhausted' and failures = 5")).isEqualTo(round + 1);

            // The sign-in is over for good: a correct code is never accepted, never consumed, and no session comes back.
            Browser.Answer late = sameSession(browser).post(VERIFY, totp(code(admin.secret())));
            assertThat(late.code()).isIn("csrf_invalid", "unauthenticated");
            assertError(browser.get("/api/me"), 401, "unauthenticated");
            browser.csrf();
            assertError(browser.post(VERIFY, totp(code(admin.secret()))), 401, "unauthenticated");
            assertThat(count("select max(last_accepted_step) from account_totp")).isEqualTo(step);
            assertThat(principalSessions(admin.email())).as("only the session that enrolled remains").isEqualTo(1);
        }
    }

    @Test
    void a6_10_aCorrectCodeAmongFourWrongOnesSignsInAndStaysSignedIn() {
        Staff admin = staff("sample.admin@example.org", "admin");
        for (int round = 0; round < 3; round++) {
            Browser browser = pending(admin);
            String oldCookie = browser.sessionId;
            Browser winner = sameSession(browser);
            String correct = totp(code(admin.secret()));
            List<Callable<Browser.Answer>> calls = new ArrayList<>();
            calls.add(() -> winner.post(VERIFY, correct));
            for (int i = 0; i < 4; i++) {
                Browser tab = sameSession(browser);
                calls.add(() -> tab.post(VERIFY, totp("000000")));
            }
            List<Browser.Answer> answers = together(calls);
            // Four wrong codes never exhaust the budget, so the correct one is accepted wherever it lands among them.
            assertThat(answers.get(0).status()).as("round %d: %s", round, answers.get(0).body()).isEqualTo(200);
            for (Browser.Answer wrong : answers.subList(1, 5)) {
                assertThat(wrong.status()).isNotEqualTo(200);
                assertThat(wrong.code()).isIn("mfa_code_invalid", "already_signed_in", "csrf_invalid");
            }
            // The requests that lost ended after the rotation. They did not bring the old identifier back,
            // and they did not take the new session away.
            assertThat(winner.sessionId).isNotEqualTo(oldCookie);
            assertThat(winner.get("/api/me").status()).as("round %d: the winner stays signed in", round).isEqualTo(200);
            Browser old = browser();
            old.sessionId = oldCookie;
            assertError(old.get("/api/me"), 401, "unauthenticated");
            assertThat(count("select count(*) from spring_session where session_id = '" + winner.storedSessionId() + "'")).isEqualTo(1);
            // Past the hour, so the account's own counter starts again, and onto a new time step.
            advance(Duration.ofMinutes(61));
        }
    }

    @Test
    void a6_10_theFifthWrongCodeAndACorrectCodeTogetherHaveOneConsistentOutcome() {
        Staff admin = staff("sample.admin@example.org", "admin");
        int signedIn = 0;
        for (int round = 0; round < 4; round++) {
            Browser browser = pending(admin);
            for (int i = 0; i < 4; i++) {
                assertError(browser.post(VERIFY, totp("000000")), 401, "mfa_code_invalid");
            }
            long step = count("select last_accepted_step from account_totp");
            Browser right = sameSession(browser);
            Browser wrong = sameSession(browser);
            String correct = totp(code(admin.secret()));
            List<Browser.Answer> answers = together(List.of(() -> right.post(VERIFY, correct), () -> wrong.post(VERIFY, totp("000000"))));
            if (answers.get(0).status() == 200) {
                // The correct code was first: it is signed in, and the wrong one changed nothing.
                signedIn++;
                assertThat(answers.get(1).code()).isIn("already_signed_in", "csrf_invalid");
                assertThat(right.get("/api/me").status()).isEqualTo(200);
                assertThat(count("select count(*) from pending_sign_in where outcome = 'completed' and failures = 4")).isGreaterThanOrEqualTo(1);
            } else {
                // The fifth wrong code was first: nobody is signed in and the correct code was not spent.
                assertThat(answers.get(1).code()).isEqualTo("unauthenticated");
                assertThat(answers.get(0).code()).isIn("unauthenticated", "csrf_invalid");
                assertError(right.get("/api/me"), 401, "unauthenticated");
                assertThat(count("select last_accepted_step from account_totp")).isEqualTo(step);
            }
            assertError(browser.get("/api/me"), 401, "unauthenticated");
            advance(Duration.ofMinutes(61));
        }
        assertThat(signedIn).isBetween(0, 4);
    }

    @Test
    void a6_10_aPendingSignInThatIsOverDoesNotExistEvenIfItsSessionRowDoes() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = pending(admin);
        // As if a session row had outlived the end of its sign-in on another instance.
        jdbc.sql("update pending_sign_in set failures = 5, outcome = 'exhausted' where outcome is null").update();
        assertThat(count("select count(*) from spring_session where session_id = '" + browser.storedSessionId() + "'")).isEqualTo(1);
        assertError(browser.get("/api/me"), 401, "unauthenticated");
        assertThat(count("select count(*) from spring_session where principal_name is not null and max_inactive_interval = 600")).isZero();
    }

    @Test
    void a3_2_aReplacedSessionIdentifierCannotBeWrittenBack() {
        Browser donor = donor("sample.donor@example.org");
        String current = donor.storedSessionId();
        String retired = jdbc.sql("select session_id from spring_session_retired").query(String.class).list().get(0).trim();
        // What a request that began before the rotation writes when it ends: the old identifier and its own stale values.
        int touched = jdbc.sql("""
                update spring_session set session_id = :old, max_inactive_interval = 600, principal_name = null where session_id = :current
                """).param("old", retired).param("current", current).update();
        assertThat(touched).isEqualTo(1);
        assertThat(count("select count(*) from spring_session where session_id = '" + retired + "'")).as("the old identifier did not come back").isZero();
        assertThat(count("select count(*) from spring_session where session_id = '" + current + "' and max_inactive_interval = 7200 and principal_name is not null"))
                .as("the stale write changed nothing").isEqualTo(1);
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ finding 2: sign-in against a changed credential

    @Test
    void a2_14_aSignInThatVerifiedAnOldPasswordCannotWriteItBackOrSignIn() throws Exception {
        register(browser(), "sample.donor@example.org", PASSWORD);
        // A hash made with weaker parameters, which a sign-in upgrades.
        String weak = "{argon2id}" + new Argon2PasswordEncoder(16, 32, 1, 4096, 1).encode(PASSWORD);
        jdbc.sql("update account_password set password_hash = :hash").param("hash", weak).update();
        String replacement = hasher.hash(OTHER_PASSWORD);

        Browser browser = browser();
        browser.csrf();
        Browser.Answer answer = whileAccountIsBeingChanged("sample.donor@example.org",
                () -> browser.post("/api/auth/login", json("email", "sample.donor@example.org", "password", PASSWORD)),
                () -> {
                    // What a password reset does, committed while the sign-in holds a verified old password.
                    jdbc.sql("update account_password set password_hash = :hash").param("hash", replacement).update();
                    jdbc.sql("update account set security_version = security_version + 1").update();
                });

        assertError(answer, 401, "invalid_credentials");
        assertThat(jdbc.sql("select password_hash from account_password").query(String.class).single())
                .as("neither the old password nor an upgrade of it was written over the new one").isEqualTo(replacement);
        assertError(browser.get("/api/me"), 401, "unauthenticated");
        assertThat(principalSessions("sample.donor@example.org")).isZero();
        assertError(browser().login("sample.donor@example.org", PASSWORD), 401, "invalid_credentials");
        assertThat(browser().login("sample.donor@example.org", OTHER_PASSWORD).status()).isEqualTo(200);

        // Without a race the upgrade still happens.
        jdbc.sql("update account_password set password_hash = :hash").param("hash", weak).update();
        assertThat(browser().login("sample.donor@example.org", PASSWORD).status()).isEqualTo(200);
        assertThat(jdbc.sql("select password_hash from account_password").query(String.class).single()).startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$");
    }

    @Test
    void a6_22_anExpiredEnrollmentWindowDoesNotRemoveACredentialIssuedMeanwhile() throws Exception {
        activatedStaff("sample.admin@example.org", "admin");
        jdbc.sql("update account set enrollment_window_ends_at = :past").param("past", Timestamp.from(START.minusSeconds(60))).update();
        String replacement = hasher.hash(OTHER_PASSWORD);

        Browser browser = browser();
        browser.csrf();
        Browser.Answer answer = whileAccountIsBeingChanged("sample.admin@example.org",
                () -> browser.post("/api/auth/login", json("email", "sample.admin@example.org", "password", PASSWORD)),
                () -> {
                    // What redeeming a fresh recovery credential does: a new password and a new window.
                    jdbc.sql("update account_password set password_hash = :hash").param("hash", replacement).update();
                    jdbc.sql("update account set security_version = security_version + 1, enrollment_window_ends_at = :future")
                            .param("future", Timestamp.from(START.plusSeconds(3600))).update();
                });

        assertError(answer, 401, "invalid_credentials");
        // The stale sign-in saw a closed window. It must not delete the new password or deactivate the account.
        assertThat(jdbc.sql("select password_hash from account_password").query(String.class).single()).isEqualTo(replacement);
        assertThat(count("select count(*) from account where activated")).isEqualTo(1);
        assertThat(browser().login("sample.admin@example.org", OTHER_PASSWORD).json().get("status").asText()).isEqualTo("mfa_enrollment_required");
    }

    @Test
    void a5_12_aPasswordChangeThatVerifiedAnOldPasswordChangesNothing() throws Exception {
        Browser donor = donor("sample.donor@example.org");
        String replacement = hasher.hash("a third harmless passphrase 2028");
        Browser.Answer answer = whileAccountIsBeingChanged("sample.donor@example.org",
                () -> donor.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)),
                () -> {
                    jdbc.sql("update account_password set password_hash = :hash").param("hash", replacement).update();
                    jdbc.sql("update account set security_version = security_version + 1").update();
                });
        assertError(answer, 401, "unauthenticated");
        assertThat(jdbc.sql("select password_hash from account_password").query(String.class).single()).isEqualTo(replacement);
        assertError(donor.get("/api/me"), 401, "unauthenticated");
    }

    // ------------------------------------------------------------------ finding 3: second-factor operations against a changed account

    @Test
    void a5_12_verificationThatRacesARevocationNeitherSignsInNorSpendsTheCode() throws Exception {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = pending(admin);
        long step = count("select last_accepted_step from account_totp");
        Browser.Answer answer = whileAccountIsBeingChanged(admin.email(),
                () -> browser.post(VERIFY, totp(code(admin.secret()))),
                () -> jdbc.sql("update account set security_version = security_version + 1 where email = :email").param("email", admin.email()).update());
        assertError(answer, 401, "unauthenticated");
        assertThat(count("select last_accepted_step from account_totp")).as("the code was not consumed").isEqualTo(step);
        assertError(browser.get("/api/me"), 401, "unauthenticated");
        assertThat(count("select count(*) from audit_event where event_type = 'session.second_factor' and outcome = 'success'")).isZero();

        // The same with a recovery code: it is not spent.
        Browser second = pending(admin);
        Browser.Answer recovery = whileAccountIsBeingChanged(admin.email(),
                () -> second.post(VERIFY, json("method", "recovery_code", "code", admin.recoveryCodes().get(0))),
                () -> jdbc.sql("update account set enabled = false where email = :email").param("email", admin.email()).update());
        assertError(recovery, 401, "unauthenticated");
        assertThat(count("select count(*) from account_recovery_code where used_at is null")).isEqualTo(10);
    }

    @Test
    void a5_12_regenerationThatRacesAChangeTouchesNeitherTheNewCodesNorTheFactor() throws Exception {
        Staff admin = staff("sample.admin@example.org", "admin");
        long step = count("select last_accepted_step from account_totp");
        Browser.Answer answer = whileAccountIsBeingChanged(admin.email(),
                () -> admin.browser().post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", code(admin.secret()))),
                () -> {
                    // The account's credentials are replaced while the request holds a verified password.
                    jdbc.sql("delete from account_recovery_code").update();
                    jdbc.sql("insert into account_recovery_code (account_id, code_hash) select id, '\\x00'::bytea from account where email = :email")
                            .param("email", admin.email()).update();
                    jdbc.sql("update account set security_version = security_version + 1 where email = :email").param("email", admin.email()).update();
                });
        assertError(answer, 401, "unauthenticated");
        assertThat(answer.body()).doesNotContain("codes");
        assertThat(count("select count(*) from account_recovery_code")).as("the replacement codes are untouched").isEqualTo(1);
        assertThat(count("select last_accepted_step from account_totp")).as("the authenticator code was not consumed").isEqualTo(step);
        assertError(admin.browser().get("/api/me"), 401, "unauthenticated");
    }

    @Test
    void a5_12_enrollmentStepsThatRaceAChangeDoNothing() throws Exception {
        Browser browser = activatedStaff("sample.admin@example.org", "admin");
        browser.login("sample.admin@example.org", PASSWORD);
        Runnable revoke = () -> jdbc.sql("update account set security_version = security_version + 1").update();

        Browser.Answer enroll = whileAccountIsBeingChanged("sample.admin@example.org", () -> browser.postEmpty("/api/auth/mfa/enroll"), revoke);
        assertError(enroll, 401, "unauthenticated");
        assertThat(count("select count(*) from account_totp")).as("no secret was stored for a revoked session").isZero();

        Browser again = browser();
        again.login("sample.admin@example.org", PASSWORD);
        byte[] secret = base32Decode(again.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText());
        Browser.Answer confirm = whileAccountIsBeingChanged("sample.admin@example.org",
                () -> again.post("/api/auth/mfa/confirm", json("code", code(secret))), revoke);
        assertError(confirm, 401, "unauthenticated");
        assertThat(confirm.body()).doesNotContain("codes");
        assertThat(count("select count(*) from account_totp where state = 'active'")).isZero();
        assertThat(count("select count(*) from account_recovery_code")).isZero();
        assertError(again.get("/api/me"), 401, "unauthenticated");
    }

    @Test
    void a5_12_verificationAndStaffRecoveryTogetherNeverLeaveASession() {
        for (int round = 0; round < 5; round++) {
            String email = "sample.admin" + round + "@example.org";
            Staff admin = staff(email, "admin");
            Browser browser = pending(admin);
            String correct = totp(code(admin.secret()));
            List<Object> results = together(List.of(
                    () -> browser.post(VERIFY, correct),
                    () -> staffAdministration.recoverStaffAccess(email, "test-operator", "in person")));
            Browser.Answer answer = (Browser.Answer) results.get(0);
            // Verification either finished first or was refused. Afterwards nothing of the old access remains either way.
            assertThat(answer.status()).as("round %d: %s", round, answer.body()).isIn(200, 401, 403);
            assertError(browser.get("/api/me"), 401, "unauthenticated");
            assertError(admin.browser().get("/api/me"), 401, "unauthenticated");
            assertThat(count("select count(*) from account_totp t join account a on a.id = t.account_id where a.email = '" + email + "'")).isZero();
            assertThat(count("select count(*) from account_recovery_code c join account a on a.id = c.account_id where a.email = '" + email + "'")).isZero();
        }
    }

    // ------------------------------------------------------------------ finding 5: a pending sign-in and a new sign-in

    @Test
    void a3_15_aFailedSignInEndsThePendingSignInAndARefusedRequestDoesNot() {
        Staff admin = staff("sample.admin@example.org", "admin");
        register(browser(), "sample.donor@example.org", PASSWORD);

        // Refused at validation (step 7): the operation did not proceed, the pending sign-in stands.
        Browser browser = pending(admin);
        assertError(browser.post("/api/auth/login", "{\"email\":\"sample.donor@example.org\"}"), 400, "validation_error");
        assertError(browser.get("/api/me"), 401, "mfa_required");

        // A valid request with a wrong password (step 8): it proceeded as from anonymous, so the pending sign-in is over.
        String pendingCookie = browser.sessionId;
        assertError(browser.post("/api/auth/login", json("email", "sample.donor@example.org", "password", OTHER_PASSWORD)), 401, "invalid_credentials");
        assertError(browser.get("/api/me"), 401, "unauthenticated");
        Browser old = browser();
        old.sessionId = pendingCookie;
        assertError(old.get("/api/me"), 401, "unauthenticated");
        browser.csrf();
        assertError(browser.post(VERIFY, totp(code(admin.secret()))), 401, "unauthenticated");
        assertThat(count("select count(*) from spring_session where max_inactive_interval = 600")).isZero();

        // The same for an unknown address, and for set-password with a credential that is no good.
        Browser second = pending(admin);
        assertError(second.post("/api/auth/login", json("email", "nobody@example.org", "password", PASSWORD)), 401, "invalid_credentials");
        assertError(second.get("/api/me"), 401, "unauthenticated");
        Browser third = pending(admin);
        assertError(third.post("/api/auth/set-password", json("credential", "not-a-credential", "newPassword", PASSWORD)), 400, "credential_invalid");
        assertError(third.get("/api/me"), 401, "unauthenticated");
        // Refused by the client-address limit (step 5), before the state is looked at: the pending sign-in stands.
        Browser fourth = pending(admin);
        jdbc.sql("update auth_throttle set attempts = 30 where scope = 'signin.address'").update();
        assertError(fourth.post("/api/auth/login", json("email", "sample.donor@example.org", "password", OTHER_PASSWORD)), 429, "rate_limited");
        assertError(fourth.get("/api/me"), 401, "mfa_required");
    }
}
