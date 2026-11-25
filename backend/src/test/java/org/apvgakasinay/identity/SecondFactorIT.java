package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apvgakasinay.identity.internal.Totp;
import org.junit.jupiter.api.Test;

/** Security acceptance group 6: the second factor for staff. */
class SecondFactorIT extends IdentityIntegration {

    private static final String VERIFY = "/api/auth/mfa/verify";
    private static final String ENROLL = "/api/auth/mfa/enroll";
    private static final String CONFIRM = "/api/auth/mfa/confirm";

    private String totp(String code) {
        return json("method", "totp", "code", code);
    }

    @Test
    void a6_1_staffWithoutASecondFactorCanOnlyEnroll() {
        Browser browser = activatedStaff("sample.admin@example.org", "admin");
        Browser.Answer login = browser.login("sample.admin@example.org", PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertValid("SecondFactorPending", login);
        assertValid("LoginResult", login);
        assertThat(login.json().get("status").asText()).isEqualTo("mfa_enrollment_required");
        assertThat(login.json().has("account")).as("a pending result carries no account").isFalse();
        assertThat(login.json().get("pendingExpiresAt").asText()).isEqualTo("2026-11-24T12:10:00Z");

        assertError(browser.get("/api/me"), 401, "mfa_enrollment_required");
        assertError(browser.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)), 401, "mfa_enrollment_required");
        assertThat(browser().login("sample.admin@example.org", PASSWORD).status()).as("the password was not changed").isEqualTo(200);
    }

    @Test
    void a6_2_enrollmentMakesTheSessionFullySignedIn() {
        Browser browser = activatedStaff("sample.admin@example.org", "admin");
        browser.login("sample.admin@example.org", PASSWORD);
        String pendingSession = browser.sessionId;

        Browser.Answer enrollment = browser.postEmpty(ENROLL);
        assertThat(enrollment.status()).isEqualTo(200);
        assertValid("MfaEnrollment", enrollment);
        String secret = enrollment.json().get("secret").asText();
        assertThat(enrollment.json().get("otpauthUri").asText())
                .startsWith("otpauth://totp/APV-GAKASINAY:sample.admin%40example.org?secret=" + secret)
                .contains("algorithm=SHA1").contains("digits=6").contains("period=30");

        Browser.Answer confirmed = browser.post(CONFIRM, json("code", code(base32Decode(secret))));
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertValid("MfaRecoveryCodes", confirmed);
        assertThat(confirmed.json().get("codes")).hasSize(10);
        assertThat(browser.sessionId).as("identifier replaced").isNotEqualTo(pendingSession);

        Browser.Answer me = browser.get("/api/me");
        assertThat(me.status()).isEqualTo(200);
        assertValid("Account", me);
        assertThat(me.json().at("/mfa/enrolled").asBoolean()).isTrue();
        assertThat(me.json().at("/mfa/recoveryCodesRemaining").asInt()).isEqualTo(10);
        // The old token died with the rotation.
        assertError(browser.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)), 403, "csrf_invalid");
    }

    @Test
    void a6_3_and_6_5b_wrongStaleAndReplacedCodesKeepThePendingSession() {
        Browser browser = activatedStaff("sample.admin@example.org", "admin");
        browser.login("sample.admin@example.org", PASSWORD);
        byte[] first = base32Decode(browser.postEmpty(ENROLL).json().get("secret").asText());

        assertError(browser.post(CONFIRM, json("code", "000000")), 401, "mfa_code_invalid");
        assertError(browser.get("/api/me"), 401, "mfa_enrollment_required");

        // Starting again replaces the pending secret: a code from the first one is refused.
        byte[] second = base32Decode(browser.postEmpty(ENROLL).json().get("secret").asText());
        assertThat(second).isNotEqualTo(first);
        assertError(browser.post(CONFIRM, json("code", code(first))), 401, "mfa_code_invalid");

        // A pending secret that has expired is refused too.
        jdbc.sql("update account_totp set pending_expires_at = now() - interval '1 year'").update();
        assertError(browser.post(CONFIRM, json("code", code(second))), 401, "mfa_code_invalid");
        assertThat(count("select count(*) from account_totp where state = 'active'")).isZero();

        // Still pending: start again and succeed.
        byte[] third = base32Decode(browser.postEmpty(ENROLL).json().get("secret").asText());
        assertThat(browser.post(CONFIRM, json("code", code(third))).status()).isEqualTo(200);
    }

    @Test
    void a6_4_onlyTheLatestPendingSecretCounts() {
        Browser tabA = activatedStaff("sample.admin@example.org", "admin");
        tabA.login("sample.admin@example.org", PASSWORD);
        Browser tabB = browser();
        tabB.login("sample.admin@example.org", PASSWORD);
        byte[] secretA = base32Decode(tabA.postEmpty(ENROLL).json().get("secret").asText());
        byte[] secretB = base32Decode(tabB.postEmpty(ENROLL).json().get("secret").asText());

        assertError(tabA.post(CONFIRM, json("code", code(secretA))), 401, "mfa_code_invalid");
        assertThat(tabB.post(CONFIRM, json("code", code(secretB))).status()).isEqualTo(200);
        // The other pending session of the same account: its code is no good, and it learns nothing.
        Browser.Answer late = tabA.post(CONFIRM, json("code", code(secretB)));
        assertError(late, 401, "mfa_code_invalid");
        assertThat(late.body()).doesNotContain("codes");
        assertThat(count("select count(*) from account_recovery_code")).isEqualTo(10);
    }

    @Test
    void a6_5_simultaneousConfirmationsHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 3; round++) {
            String email = "sample.admin" + round + "@example.org";
            Browser browser = activatedStaff(email, "admin");
            browser.login(email, PASSWORD);
            byte[] secret = base32Decode(browser.postEmpty(ENROLL).json().get("secret").asText());
            String body = json("code", code(secret));
            List<Browser.Answer> answers = java.util.Collections.synchronizedList(new ArrayList<>());
            // Three requests on the same session, at the same instant, with the same token.
            String cookie = browser.sessionId;
            String token = browser.token;
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                calls.add(() -> {
                    Browser same = browser.copyOfCookie();
                    same.sessionId = cookie;
                    Browser.Answer answer = same.post(CONFIRM, body, token);
                    answers.add(answer);
                    return answer.status();
                });
            }
            List<Integer> statuses = together(calls);
            assertThat(statuses.stream().filter(status -> status == 200).count()).as("round %d: %s", round, statuses).isEqualTo(1);
            for (Browser.Answer answer : answers) {
                if (answer.status() != 200) {
                    // Each loser gets one of the answers its situation allows, and never the codes.
                    assertThat(answer.code()).isIn("already_signed_in", "csrf_invalid", "unauthenticated", "mfa_code_invalid");
                    assertThat(answer.body()).doesNotContain("codes");
                }
            }
            assertThat(count("select count(*) from account_totp t join account a on a.id = t.account_id where a.email = '" + email + "' and t.state = 'active'")).isEqualTo(1);
            assertThat(count("select count(*) from account_recovery_code c join account a on a.id = c.account_id where a.email = '" + email + "'")).isEqualTo(10);
        }
    }

    @Test
    void a6_5a_theEnrollmentWindowIsCheckedAgainAtConfirmation() {
        Browser browser = activatedStaff("sample.admin@example.org", "admin");
        browser.login("sample.admin@example.org", PASSWORD);
        byte[] secret = base32Decode(browser.postEmpty(ENROLL).json().get("secret").asText());
        jdbc.sql("update account set enrollment_window_ends_at = :past").param("past", java.sql.Timestamp.from(START.minusSeconds(60))).update();

        assertError(browser.post(CONFIRM, json("code", code(secret))), 401, "unauthenticated");
        assertThat(count("select count(*) from account_totp where state = 'active'")).isZero();
        assertThat(count("select count(*) from spring_session where principal_name is not null")).as("the pending session is ended").isZero();
    }

    @Test
    void a6_6_verificationCompletesAPendingSignIn() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = pending(admin);
        String pendingSession = browser.sessionId;
        assertError(browser.get("/api/me"), 401, "mfa_required");

        Browser.Answer verified = browser.post(VERIFY, totp(code(admin.secret())));
        assertThat(verified.status()).as(verified.body()).isEqualTo(200);
        assertValid("SignedIn", verified);
        assertThat(verified.json().at("/account/role").asText()).isEqualTo("admin");
        assertThat(browser.sessionId).isNotEqualTo(pendingSession);
        assertThat(browser.get("/api/me").status()).isEqualTo(200);
    }

    @Test
    void a6_7_and_6_8_aCodeWorksOnceAlsoAtTheSameInstant() throws Exception {
        Staff admin = staff("sample.admin@example.org", "admin");
        for (int round = 0; round < 3; round++) {
            Browser one = pending(admin);
            Browser two = pending(admin);
            String body = totp(code(admin.secret()));
            List<Integer> statuses = together(List.of(() -> one.post(VERIFY, body).status(), () -> two.post(VERIFY, body).status()));
            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 401);
            // 6.8: the same code again, in a new sign-in, within its thirty seconds.
            Browser three = pending(admin);
            assertError(three.post(VERIFY, body), 401, "mfa_code_invalid");
            nextCode();
        }
    }

    @Test
    void a6_9_oneStepEitherSideIsAcceptedAndNoFurther() {
        Staff admin = staff("sample.admin@example.org", "admin");
        advance(Duration.ofSeconds(150));
        long now = Totp.step(clock.now());
        assertThat(pending(admin).post(VERIFY, totp(Totp.code(admin.secret(), now - 1))).status()).as("previous step").isEqualTo(200);
        assertThat(pending(admin).post(VERIFY, totp(Totp.code(admin.secret(), now + 1))).status()).as("next step").isEqualTo(200);
        advance(Duration.ofSeconds(150));
        now = Totp.step(clock.now());
        assertError(pending(admin).post(VERIFY, totp(Totp.code(admin.secret(), now + 2))), 401, "mfa_code_invalid");
        assertError(pending(admin).post(VERIFY, totp(Totp.code(admin.secret(), now - 2))), 401, "mfa_code_invalid");
    }

    @Test
    void a6_10_fourWrongCodesKeepThePendingSignInTheFifthEndsIt() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = pending(admin);
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertError(browser.post(VERIFY, totp("000000")), 401, "mfa_code_invalid");
            // About the code only: the sign-in is still pending.
            assertError(browser.get("/api/me"), 401, "mfa_required");
        }
        String heldToken = browser.token;
        Browser heldCookie = browser.copyOfCookie();
        assertError(browser.post(VERIFY, totp("000000")), 401, "unauthenticated");
        assertError(browser.get("/api/me"), 401, "unauthenticated");

        // A correct code now, with what the page still holds: the dead session's token is refused first.
        assertError(heldCookie.post(VERIFY, totp(code(admin.secret())), heldToken), 403, "csrf_invalid");
        // With a fresh anonymous token: 401. Either way it is never accepted, never checked.
        browser.csrf();
        assertError(browser.post(VERIFY, totp(code(admin.secret()))), 401, "unauthenticated");
        // The code was not spent: a new sign-in can still use it.
        Browser again = pending(admin);
        assertThat(again.post(VERIFY, totp(code(admin.secret()))).status()).isEqualTo(200);
    }

    @Test
    void a6_11_and_6_12_recoveryCodesWorkOnce() throws Exception {
        Staff admin = staff("sample.admin@example.org", "admin");
        String recovery = admin.recoveryCodes().get(0);
        Browser browser = pending(admin);
        Browser.Answer verified = browser.post(VERIFY, json("method", "recovery_code", "code", recovery));
        assertThat(verified.status()).as(verified.body()).isEqualTo(200);
        assertThat(verified.json().at("/account/mfa/recoveryCodesRemaining").asInt()).isEqualTo(9);
        assertError(pending(admin).post(VERIFY, json("method", "recovery_code", "code", recovery)), 401, "mfa_code_invalid");

        // Without its dashes it is the same code.
        String second = admin.recoveryCodes().get(1);
        assertThat(pending(admin).post(VERIFY, json("method", "recovery_code", "code", second.replace("-", ""))).status()).isEqualTo(200);

        // 6.12: one code in two requests at the same instant.
        String third = admin.recoveryCodes().get(2);
        Browser one = pending(admin);
        Browser two = pending(admin);
        String body = json("method", "recovery_code", "code", third);
        assertThat(together(List.of(() -> one.post(VERIFY, body).status(), () -> two.post(VERIFY, body).status())))
                .containsExactlyInAnyOrder(200, 401);
        assertThat(count("select count(*) from account_recovery_code where used_at is null")).isEqualTo(7);
    }

    @Test
    void a6_13_aCodeMustHaveTheShapeOfItsMethod() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = pending(admin);
        for (String body : List.of(
                json("method", "totp", "code", admin.recoveryCodes().get(0)),
                json("method", "recovery_code", "code", "123456"),
                json("method", "recovery_code", "code", admin.recoveryCodes().get(0).toLowerCase()),
                json("method", "sms", "code", "123456"),
                "{\"method\":\"totp\",\"code\":\"123456\",\"trustThisDevice\":true}")) {
            assertError(browser.post(VERIFY, body), 400, "validation_error");
        }
        // None of those was counted as a wrong code: four wrong ones still leave the sign-in pending.
        for (int i = 0; i < 4; i++) {
            assertError(browser.post(VERIFY, totp("000000")), 401, "mfa_code_invalid");
        }
        assertThat(browser.post(VERIFY, totp(code(admin.secret()))).status()).isEqualTo(200);
    }

    @Test
    void a6_14_and_6_15_replacingRecoveryCodes() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = admin.browser();
        String path = "/api/auth/mfa/recovery-codes";

        // 6.15: a wrong password, a wrong code, and a recovery code in place of the authenticator code.
        assertError(browser.post(path, json("currentPassword", OTHER_PASSWORD, "code", code(admin.secret()))), 401, "invalid_credentials");
        assertError(browser.post(path, json("currentPassword", PASSWORD, "code", "000000")), 401, "mfa_code_invalid");
        assertError(browser.post(path, json("currentPassword", PASSWORD, "code", admin.recoveryCodes().get(0))), 400, "validation_error");
        // About the secrets only: still signed in, and the old codes still stand.
        assertThat(browser.get("/api/me").json().at("/mfa/recoveryCodesRemaining").asInt()).isEqualTo(10);

        Browser.Answer replaced = browser.post(path, json("currentPassword", PASSWORD, "code", code(admin.secret())));
        assertThat(replaced.status()).as(replaced.body()).isEqualTo(200);
        assertValid("MfaRecoveryCodes", replaced);
        JsonNode codes = replaced.json().get("codes");
        assertThat(codes).hasSize(10);
        assertThat(codes.toString()).doesNotContain(admin.recoveryCodes().get(0));
        nextCode();
        assertError(pending(admin).post(VERIFY, json("method", "recovery_code", "code", admin.recoveryCodes().get(0))), 401, "mfa_code_invalid");
        assertThat(pending(admin).post(VERIFY, json("method", "recovery_code", "code", codes.get(0).asText())).status()).isEqualTo(200);
    }

    @Test
    void a6_16_aDonorHasNoSecondFactorSteps() {
        Browser donor = donor("sample.donor@example.org");
        assertError(donor.post(VERIFY, totp("123456")), 409, "already_signed_in");
        assertError(donor.postEmpty(ENROLL), 409, "already_signed_in");
        assertError(donor.post(CONFIRM, json("code", "123456")), 409, "already_signed_in");
        assertError(donor.post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", "123456")), 403, "forbidden");
        assertThat(count("select count(*) from account_totp")).isZero();
        assertThat(donor.get("/api/me").json().at("/mfa/required").asBoolean()).isFalse();
    }

    @Test
    void a6_18_noSecretAndNoRecoveryCodeIsStoredInTheClear() {
        Staff admin = staff("sample.admin@example.org", "admin");
        byte[] stored = jdbc.sql("select secret_encrypted from account_totp").query(byte[].class).single();
        assertThat(stored.length).isEqualTo(12 + 20 + 16);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(new String(admin.secret(), StandardCharsets.ISO_8859_1));
        List<byte[]> hashes = jdbc.sql("select code_hash from account_recovery_code").query(byte[].class).list();
        assertThat(hashes).hasSize(10).allMatch(hash -> hash.length == 32);
        String everything = jdbc.sql("""
                select coalesce(string_agg(encode(code_hash, 'escape'), ' '), '') from account_recovery_code
                """).query(String.class).single();
        for (String code : admin.recoveryCodes()) {
            assertThat(everything).doesNotContain(code).doesNotContain(code.replace("-", ""));
        }
    }

    @Test
    void a6_19_aPasswordResetDoesNotRemoveTheSecondFactor() {
        Staff admin = staff("sample.admin@example.org", "admin");
        String credential = staffAdministration.issuePasswordReset(admin.email(), "test-operator", "called a number already on file");
        Browser browser = browser();
        browser.csrf();
        assertThat(browser.post("/api/auth/set-password", json("credential", credential, "newPassword", OTHER_PASSWORD)).status()).isEqualTo(204);
        assertError(admin.browser().get("/api/me"), 401, "unauthenticated");

        Browser.Answer login = browser.login(admin.email(), OTHER_PASSWORD);
        assertThat(login.json().get("status").asText()).isEqualTo("mfa_required");
        assertError(browser.get("/api/me"), 401, "mfa_required");
        assertError(browser.postEmpty(ENROLL), 403, "forbidden");
        assertThat(browser.post(VERIFY, totp(code(admin.secret()))).status()).isEqualTo(200);
    }

    @Test
    void a6_20_and_6_21_staffRecoveryMakesTheOldPasswordUseless() {
        Staff admin = staff("sample.admin@example.org", "admin");
        String credential = staffAdministration.recoverStaffAccess(admin.email(), "test-operator", "met in person");

        // 6.20: the old password can neither sign in nor reach enrollment.
        assertError(admin.browser().get("/api/me"), 401, "unauthenticated");
        assertError(browser().login(admin.email(), PASSWORD), 401, "invalid_credentials");
        assertThat(count("select count(*) from account_totp")).isZero();
        assertThat(count("select count(*) from account_recovery_code")).isZero();
        assertThat(count("select count(*) from account_password")).isZero();

        // 6.21: only the fresh credential leads anywhere, and it leads to enrollment.
        Browser browser = browser();
        browser.csrf();
        assertThat(browser.post("/api/auth/set-password", json("credential", credential, "newPassword", OTHER_PASSWORD)).status()).isEqualTo(204);
        Browser.Answer login = browser.login(admin.email(), OTHER_PASSWORD);
        assertThat(login.json().get("status").asText()).isEqualTo("mfa_enrollment_required");
        byte[] secret = base32Decode(browser.postEmpty(ENROLL).json().get("secret").asText());
        assertThat(secret).isNotEqualTo(admin.secret());
        assertThat(browser.post(CONFIRM, json("code", code(secret))).status()).isEqualTo(200);
        // The old authenticator and the old recovery codes are worthless.
        nextCode();
        assertError(pending(new Staff(admin.email(), null, secret, List.of()), OTHER_PASSWORD).post(VERIFY, totp(code(admin.secret()))), 401, "mfa_code_invalid");
    }

    private Browser pending(Staff staff, String password) {
        Browser browser = browser();
        assertThat(browser.login(staff.email(), password).json().get("status").asText()).isEqualTo("mfa_required");
        return browser;
    }

    @Test
    void a6_22_aPasswordAloneNeverOpensEnrollmentOnceTheWindowHasClosed() {
        Browser browser = activatedStaff("sample.admin@example.org", "admin");
        jdbc.sql("update account set enrollment_window_ends_at = :past").param("past", java.sql.Timestamp.from(START.minusSeconds(60))).update();
        assertError(browser.login("sample.admin@example.org", PASSWORD), 401, "invalid_credentials");
        // The account is not activated any more: it has no password, and needs a new credential.
        assertThat(count("select count(*) from account where activated")).isZero();
        assertThat(count("select count(*) from account_password")).isZero();
        assertError(browser().login("sample.admin@example.org", PASSWORD), 401, "invalid_credentials");
    }

    @Test
    void a6_1_theEnrollmentWindowIsOneHourFromTheCredential() {
        activatedStaff("sample.admin@example.org", "admin");
        advance(Duration.ofMinutes(59));
        assertThat(browser().login("sample.admin@example.org", PASSWORD).json().get("status").asText()).isEqualTo("mfa_enrollment_required");
        advance(Duration.ofMinutes(1));
        assertError(browser().login("sample.admin@example.org", PASSWORD), 401, "invalid_credentials");
    }
}
