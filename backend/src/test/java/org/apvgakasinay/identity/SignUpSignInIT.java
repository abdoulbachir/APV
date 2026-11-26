package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Security acceptance groups 1 and 2: sign-up, sign-in and passwords, over real HTTP and PostgreSQL. */
class SignUpSignInIT extends IdentityIntegration {

    private static String withoutRequestId(String body) {
        return body.replaceAll("\"requestId\":\"[0-9a-f]+\"", "\"requestId\":\"\"");
    }

    @Test
    void a1_1_signUpThenSignIn() {
        Browser browser = browser();
        Browser.Answer accepted = register(browser, "sample.donor@example.org", PASSWORD);
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(accepted.body()).isEqualTo("{\"status\":\"accepted\"}");
        assertValid("RegistrationAccepted", accepted);
        // Sign-up signs nobody in.
        assertError(browser.get("/api/me"), 401, "unauthenticated");

        Browser.Answer login = browser.login("sample.donor@example.org", PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertValid("SignedIn", login);
        assertValid("LoginResult", login);
        assertThat(login.json().get("status").asText()).isEqualTo("authenticated");

        Browser.Answer me = browser.get("/api/me");
        assertThat(me.status()).isEqualTo(200);
        assertValid("Account", me);
        JsonNode account = me.json();
        assertThat(account.get("role").asText()).isEqualTo("donor");
        assertThat(account.get("emailVerified").asBoolean()).isFalse();
        assertThat(account.get("email").asText()).isEqualTo("sample.donor@example.org");
        assertThat(account.get("id").asText()).matches("acc_[A-Za-z0-9]{22}");
        assertThat(account.at("/mfa/required").asBoolean()).isFalse();
        assertThat(account.at("/mfa/recoveryCodesRemaining").isNull()).isTrue();
        assertThat(account.get("permissions").toString()).isEqualTo("[\"account.read_own\",\"account.change_password\"]");
    }

    @Test
    void a1_2_and_1_8_signUpForAnExistingAddressGivesTheSameAnswerAndChangesNothing() {
        Browser first = browser();
        Browser.Answer created = register(first, "sample.donor@example.org", PASSWORD);
        Browser second = browser();
        Browser.Answer again = register(second, "sample.donor@example.org", OTHER_PASSWORD);
        assertThat(again.status()).isEqualTo(202);
        assertThat(again.body()).isEqualTo(created.body());
        assertThat(count("select count(*) from account")).isEqualTo(1);

        assertThat(browser().login("sample.donor@example.org", PASSWORD).status()).isEqualTo(200);
        assertError(browser().login("sample.donor@example.org", OTHER_PASSWORD), 401, "invalid_credentials");
    }

    @Test
    void a1_3_and_1_9_lettersCaseDoesNotMakeASecondAccount() {
        register(browser(), "sample.donor@example.org", PASSWORD);
        assertThat(register(browser(), "Sample.Donor@Example.ORG", OTHER_PASSWORD).status()).isEqualTo(202);
        assertThat(count("select count(*) from account")).isEqualTo(1);
        assertThat(browser().login("SAMPLE.DONOR@example.org", PASSWORD).status()).isEqualTo(200);
        // The address is shown back as it was first typed.
        Browser donor = browser();
        donor.login("sample.donor@example.org", PASSWORD);
        assertThat(donor.get("/api/me").json().get("email").asText()).isEqualTo("sample.donor@example.org");
    }

    @Test
    void a1_4_theServerDoesNotTrimAnAddress() {
        Browser browser = browser();
        Browser.Answer padded = register(browser, " sample.donor@example.org", PASSWORD);
        assertError(padded, 400, "validation_error");
        assertThat(padded.fieldCode()).isEqualTo("invalid_format");
        assertThat(count("select count(*) from account")).isZero();

        register(browser(), "sample.donor@example.org", PASSWORD);
        Browser.Answer login = browser().login("sample.donor@example.org ", PASSWORD);
        assertError(login, 400, "validation_error");
        assertThat(login.fieldCode()).isEqualTo("invalid_format");
    }

    @Test
    void a1_5_twentySimultaneousSignUpsForOneAddressMakeOneAccount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                // Each from its own client address, so the per-address sign-up limit is not what is measured.
                Browser browser = browser();
                calls.add(() -> register(browser, "sample.donor@example.org", PASSWORD).status());
            }
            for (Future<Integer> result : pool.invokeAll(calls)) {
                assertThat(result.get()).isEqualTo(202);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from account")).isEqualTo(1);
        assertThat(count("select count(*) from account_password")).isEqualTo(1);
        assertThat(browser().login("sample.donor@example.org", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a1_6_everyKindOfRefusedSignInLooksTheSame() {
        register(browser(), "sample.donor@example.org", PASSWORD);
        register(browser(), "disabled.donor@example.org", PASSWORD);
        jdbc.sql("update account set enabled = false where email_normalized = 'disabled.donor@example.org'").update();
        createStaff("not.activated@example.org", "admin");

        String wrongPassword = withoutRequestId(browser().login("sample.donor@example.org", OTHER_PASSWORD).body());
        Browser.Answer unknown = browser().login("nobody@example.org", PASSWORD);
        Browser.Answer disabled = browser().login("disabled.donor@example.org", PASSWORD);
        Browser.Answer notActivated = browser().login("not.activated@example.org", PASSWORD);
        for (Browser.Answer answer : List.of(unknown, disabled, notActivated)) {
            assertError(answer, 401, "invalid_credentials");
            assertThat(withoutRequestId(answer.body())).isEqualTo(wrongPassword);
        }
    }

    @Test
    void a1_12_anUnverifiedAddressGrantsNothing() {
        Browser donor = donor("someone.else@example.org");
        assertThat(donor.get("/api/me").json().get("emailVerified").asBoolean()).isFalse();
        assertThat(count("select count(*) from account where email_verified")).isZero();
        assertThat(count("select count(*) from account where role <> 'donor'")).isZero();
    }

    @Test
    void a2_1_passwordLengthBoundsOverTheApi() {
        Browser browser = browser();
        Browser.Answer tooShort = register(browser, "sample.donor@example.org", "fourteen chars");
        assertError(tooShort, 400, "validation_error");
        assertThat(tooShort.fieldCode()).isEqualTo("too_short");
        Browser.Answer tooLong = register(browser, "sample.donor@example.org", "kj".repeat(64) + "k");
        assertError(tooLong, 400, "validation_error");
        assertThat(tooLong.fieldCode()).isEqualTo("too_long");
        Browser.Answer blocked = register(browser, "sample.donor@example.org", "passwordpassword");
        assertThat(blocked.fieldCode()).isEqualTo("compromised");
        Browser.Answer contextual = register(browser, "sample.donor@example.org", "sample.donor-12345");
        assertThat(contextual.fieldCode()).isEqualTo("compromised");
        assertThat(count("select count(*) from account")).isZero();
        assertThat(register(browser, "sample.donor@example.org", "kj".repeat(64)).status()).isEqualTo(202);
    }

    @Test
    void a2_3_and_2_8_nothingIsTrimmedOrCut() {
        String longest = "kj".repeat(63) + "k ";
        assertThat(longest.length()).isEqualTo(128);
        register(browser(), "sample.donor@example.org", longest);
        assertThat(browser().login("sample.donor@example.org", longest).status()).isEqualTo(200);
        // Differs only in the last character, which is a space.
        assertError(browser().login("sample.donor@example.org", "kj".repeat(63) + "kx"), 401, "invalid_credentials");
        assertError(browser().login("sample.donor@example.org", "kj".repeat(63) + "k"), 401, "invalid_credentials");
    }

    @Test
    void a2_4_composedAndDecomposedPasswordsAreTheSame() {
        register(browser(), "sample.donor@example.org", "café au lait s'il vous plaît");
        assertThat(browser().login("sample.donor@example.org", "café au lait s'il vous plaît").status()).isEqualTo(200);
    }

    @Test
    void a2_13_storedHashesAreArgon2idWithUniqueSalts() {
        register(browser(), "one@example.org", PASSWORD);
        register(browser(), "two@example.org", PASSWORD);
        List<String> hashes = jdbc.sql("select password_hash from account_password order by account_id").query(String.class).list();
        assertThat(hashes).hasSize(2);
        assertThat(hashes).allMatch(hash -> hash.startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$"));
        assertThat(hashes.get(0)).isNotEqualTo(hashes.get(1));
        assertThat(hashes).noneMatch(hash -> hash.contains(PASSWORD));
    }

    @Test
    void a2_15_and_2_16_changingThePassword() {
        Browser donor = donor("sample.donor@example.org");

        Browser.Answer wrong = donor.post("/api/auth/change-password", json("currentPassword", "not the right one at all", "newPassword", OTHER_PASSWORD));
        assertError(wrong, 401, "invalid_credentials");
        // About the secret only: still signed in, and the same token still works.
        assertThat(donor.get("/api/me").status()).isEqualTo(200);

        Browser.Answer same = donor.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", PASSWORD));
        assertError(same, 400, "validation_error");
        assertThat(same.fieldCode()).isEqualTo("same_as_current");

        Browser.Answer changed = donor.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD));
        assertThat(changed.status()).as(changed.body()).isEqualTo(204);
        assertThat(changed.header("Cache-Control")).isEqualTo("no-store");
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        assertError(browser().login("sample.donor@example.org", PASSWORD), 401, "invalid_credentials");
        assertThat(browser().login("sample.donor@example.org", OTHER_PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a5_5_and_5_6_signUpCannotChooseARoleAndUnknownPropertiesAreRefused() {
        Browser browser = browser();
        browser.csrf();
        for (String extra : List.of("\"role\":\"super_admin\"", "\"permissions\":[\"staff.manage\"]", "\"canDisburse\":true",
                "\"emailVerified\":true", "\"id\":\"acc_7Yk2mQ9xT1bLw4RmN8cYp3\"")) {
            Browser.Answer answer = browser.post("/api/auth/register",
                    "{\"email\":\"sample.donor@example.org\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Sample\"," + extra + "}");
            assertError(answer, 400, "validation_error");
            assertThat(answer.fieldCode()).as(extra).isEqualTo("unknown_field");
        }
        assertThat(count("select count(*) from account")).isZero();
        Browser.Answer login = browser.post("/api/auth/login", "{\"email\":\"a@example.org\",\"password\":\"x\",\"rememberMe\":true}");
        assertThat(login.fieldCode()).isEqualTo("unknown_field");
    }
}
