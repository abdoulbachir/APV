package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apvgakasinay.fixtures.FixtureException;
import org.apvgakasinay.fixtures.FixtureImporter;
import org.apvgakasinay.identity.internal.OperatorCommands;
import org.apvgakasinay.identity.internal.StaffAdministration;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.Repo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** Security acceptance groups 7 and 13: one-time credentials, staff administration, the super administrator invariant, and real databases. */
class StaffAdministrationIT extends IdentityIntegration {

    private static final String SET_PASSWORD = "/api/auth/set-password";

    @Autowired
    OperatorCommands operator;

    @Autowired
    DatabasePurpose purpose;

    @Autowired
    FixtureImporter importer;

    private Browser.Answer redeem(Browser browser, String credential, String password) {
        browser.csrf();
        return browser.post(SET_PASSWORD, json("credential", credential, "newPassword", password));
    }

    private static String withoutRequestId(String body) {
        return body.replaceAll("\"requestId\":\"[0-9a-f]+\"", "\"requestId\":\"\"");
    }

    private long usable() {
        return count("select count(*) from usable_super_administrator");
    }

    private boolean bootstrapped() {
        return jdbc.sql("select bootstrapped from identity_guard").query(Boolean.class).single();
    }

    @Test
    void a7_1_anEmptyDatabaseHasNoAccountAndNothingCanSignIn() {
        assertThat(count("select count(*) from account")).isZero();
        assertThat(bootstrapped()).isFalse();
        assertError(browser().login("admin@example.org", "admin admin admin"), 401, "invalid_credentials");
        assertError(browser().login("root@localhost.example", PASSWORD), 401, "invalid_credentials");
    }

    @Test
    void a7_2_and_7_12_aNewStaffAccountHasNoPasswordAndItsCredentialIsStoredHashed() {
        String credential = createStaff("sample.owner@example.org", "super_admin");
        assertThat(credential).matches("[A-Za-z0-9_-]{43}");
        assertThat(count("select count(*) from account where not activated and role = 'super_admin'")).isEqualTo(1);
        assertThat(count("select count(*) from account_password")).isZero();
        byte[] stored = jdbc.sql("select credential_hash from one_time_credential").query(byte[].class).single();
        assertThat(stored).hasSize(32);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(credential);
        String audit = jdbc.sql("select coalesce(string_agg(row_to_json(a)::text, ' '), '') from audit_event a").query(String.class).single();
        assertThat(audit).contains("account.created").contains("test-operator").doesNotContain(credential);
        assertError(browser().login("sample.owner@example.org", PASSWORD), 401, "invalid_credentials");
    }

    @Test
    void a7_3_theFirstUsableSuperAdministratorBootstrapsTheSiteOnce() {
        assertThat(bootstrapped()).isFalse();
        Browser browser = activatedStaff("sample.owner@example.org", "super_admin");
        // Activated but not enrolled: not usable yet, so not bootstrapped.
        assertThat(usable()).isZero();
        assertThat(bootstrapped()).isFalse();
        browser.login("sample.owner@example.org", PASSWORD);
        byte[] secret = base32Decode(browser.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText());
        assertThat(browser.post("/api/auth/mfa/confirm", json("code", code(secret))).status()).isEqualTo(200);
        assertThat(usable()).isEqualTo(1);
        assertThat(bootstrapped()).isTrue();

        nextCode();
        staff("second.owner@example.org", "super_admin");
        assertThat(usable()).isEqualTo(2);
        assertThat(count("select count(*) from audit_event where event_type = 'site.bootstrapped'")).isEqualTo(1);
    }

    @Test
    void a7_4_twoSuperAdministratorsCreatedAndEnrolledAtTheSameTime() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> created = pool.invokeAll(List.<Callable<String>>of(
                    () -> createStaff("first.owner@example.org", "super_admin"),
                    () -> createStaff("second.owner@example.org", "super_admin")));
            String first = created.get(0).get();
            String second = created.get(1).get();
            Browser one = browser();
            Browser two = browser();
            assertThat(redeem(one, first, PASSWORD).status()).isEqualTo(204);
            assertThat(redeem(two, second, PASSWORD).status()).isEqualTo(204);
            one.login("first.owner@example.org", PASSWORD);
            two.login("second.owner@example.org", PASSWORD);
            byte[] secretOne = base32Decode(one.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText());
            byte[] secretTwo = base32Decode(two.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText());
            List<Future<Integer>> confirmed = pool.invokeAll(List.<Callable<Integer>>of(
                    () -> one.post("/api/auth/mfa/confirm", json("code", code(secretOne))).status(),
                    () -> two.post("/api/auth/mfa/confirm", json("code", code(secretTwo))).status()));
            assertThat(confirmed.get(0).get()).isEqualTo(200);
            assertThat(confirmed.get(1).get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(usable()).isEqualTo(2);
        assertThat(bootstrapped()).isTrue();
        assertThat(count("select count(*) from audit_event where event_type = 'site.bootstrapped'")).isEqualTo(1);
    }

    @Test
    void a7_5_to_7_8_aCredentialWorksOnceAndEveryRefusalLooksTheSame() {
        String first = createStaff("sample.admin@example.org", "admin");
        Browser browser = browser();
        assertThat(redeem(browser, first, PASSWORD).status()).isEqualTo(204);
        // 7.10: setting a password signs nobody in.
        assertError(browser.get("/api/me"), 401, "unauthenticated");

        // 7.5 and 7.6: used, unknown, malformed and expired all answer the same.
        String used = withoutRequestId(redeem(browser(), first, OTHER_PASSWORD).body());
        String expiring = staffAdministration.issuePasswordReset("sample.admin@example.org", "test-operator", "in person");
        advance(Duration.ofMinutes(30));
        for (String credential : List.of("q3Zr8vT1nK5xW0bM7cJ2fH9dL4sP6gY1aE8uR3tV0oI", "x", "not a credential at all !", expiring)) {
            Browser.Answer answer = redeem(browser(), credential, OTHER_PASSWORD);
            assertError(answer, 400, "credential_invalid");
            assertThat(withoutRequestId(answer.body())).isEqualTo(used);
        }
        assertThat(browser().login("sample.admin@example.org", PASSWORD).status()).as("the password set first still stands").isEqualTo(200);

        // 7.7: a credential that is not text at all is a malformed request.
        Browser other = browser();
        other.csrf();
        assertError(other.post(SET_PASSWORD, "{\"credential\":12345,\"newPassword\":\"" + PASSWORD + "\"}"), 400, "validation_error");
        assertError(other.post(SET_PASSWORD, "{\"newPassword\":\"" + PASSWORD + "\"}"), 400, "validation_error");

        // 7.8: issuing a new credential cancels the earlier one.
        String older = staffAdministration.issuePasswordReset("sample.admin@example.org", "test-operator", "in person");
        String newer = staffAdministration.issuePasswordReset("sample.admin@example.org", "test-operator", "in person");
        assertError(redeem(browser(), older, OTHER_PASSWORD), 400, "credential_invalid");
        assertThat(redeem(browser(), newer, OTHER_PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void a7_5_aWeakPasswordIsRefusedWithoutSpendingTheCredential() {
        String credential = createStaff("sample.admin@example.org", "admin");
        Browser browser = browser();
        Browser.Answer weak = redeem(browser, credential, "sample.admin-2026");
        assertError(weak, 400, "validation_error");
        assertThat(weak.fieldCode()).isEqualTo("compromised");
        assertThat(redeem(browser, credential, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void a7_9_twoSimultaneousRedemptionsOfOneCredential() throws Exception {
        String credential = createStaff("sample.admin@example.org", "admin");
        Browser one = browser();
        Browser two = browser();
        one.csrf();
        two.csrf();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(List.<Callable<Integer>>of(
                    () -> one.post(SET_PASSWORD, json("credential", credential, "newPassword", PASSWORD)).status(),
                    () -> two.post(SET_PASSWORD, json("credential", credential, "newPassword", OTHER_PASSWORD)).status()))) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(204, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from one_time_credential where used_at is not null")).isEqualTo(1);
    }

    @Test
    void a7_11_aResetEndsEverySessionOfTheAccount() {
        Browser donor = donor("sample.donor@example.org");
        String credential = staffAdministration.issuePasswordReset("sample.donor@example.org", "test-operator", "called a number already on file");
        assertThat(donor.get("/api/me").status()).as("issuing alone changes nothing").isEqualTo(200);
        assertThat(redeem(browser(), credential, OTHER_PASSWORD).status()).isEqualTo(204);
        assertError(donor.get("/api/me"), 401, "unauthenticated");
        assertError(browser().login("sample.donor@example.org", PASSWORD), 401, "invalid_credentials");
        assertThat(browser().login("sample.donor@example.org", OTHER_PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a7_13_theOnlyUsableSuperAdministratorCannotBeRemoved() {
        Staff owner = staff("sample.owner@example.org", "super_admin");
        assertThatThrownBy(() -> staffAdministration.setEnabled(owner.email(), false, "test-operator"))
                .isInstanceOf(StaffAdministration.Refused.class).hasMessageContaining("no usable super administrator");
        assertThatThrownBy(() -> staffAdministration.recoverStaffAccess(owner.email(), "test-operator", "in person"))
                .isInstanceOf(StaffAdministration.Refused.class);
        // Nothing was changed by the refused attempts: still enabled, still enrolled, still signed in.
        assertThat(usable()).isEqualTo(1);
        assertThat(owner.browser().get("/api/me").status()).isEqualTo(200);
        assertThat(count("select count(*) from audit_event where event_type = 'account.last_super_administrator_refused'")).isEqualTo(2);
    }

    @Test
    void a7_17_aChangeThatSkipsTheGuardIsRefusedAtCommit() {
        staff("sample.owner@example.org", "super_admin");
        // Plain statements, as a future code path or a manual fix might issue them.
        for (String statement : List.of(
                "update account set enabled = false where role = 'super_admin'",
                "update account set role = 'admin' where role = 'super_admin'",
                "update account set activated = false where role = 'super_admin'",
                "delete from account_totp",
                "delete from account where role = 'super_admin'")) {
            assertThatThrownBy(() -> jdbc.sql(statement).update()).as(statement).isInstanceOf(DataAccessException.class);
        }
        assertThat(usable()).isEqualTo(1);
    }

    @Test
    void a7_14_and_7_16_onlyUsableSuperAdministratorsCount() {
        Staff first = staff("first.owner@example.org", "super_admin");
        // 7.16: a second one that is activated but has no second factor is not usable.
        activatedStaff("second.owner@example.org", "super_admin");
        assertThat(usable()).isEqualTo(1);
        assertThatThrownBy(() -> staffAdministration.setEnabled(first.email(), false, "test-operator")).isInstanceOf(StaffAdministration.Refused.class);

        // 7.14: with two usable, one can go, then the other is refused.
        Staff third = staff("third.owner@example.org", "super_admin");
        assertThat(usable()).isEqualTo(2);
        staffAdministration.setEnabled(first.email(), false, "test-operator");
        assertThat(usable()).isEqualTo(1);
        assertThatThrownBy(() -> staffAdministration.setEnabled(third.email(), false, "test-operator")).isInstanceOf(StaffAdministration.Refused.class);
        assertError(first.browser().get("/api/me"), 401, "unauthenticated");
        assertThat(third.browser().get("/api/me").status()).isEqualTo(200);
    }

    /** What one attempt to remove a super administrator did. */
    private String attempt(Runnable removal) {
        try {
            removal.run();
            return "removed";
        } catch (StaffAdministration.Refused refused) {
            return "refused";
        }
    }

    @Test
    void a7_15_twoRemovalsAtTheSameTimeNeverLeaveNone() throws Exception {
        Staff first = staff("first.owner@example.org", "super_admin");
        Staff second = staff("second.owner@example.org", "super_admin");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                assertThat(usable()).isEqualTo(2);
                // Round by round: disable against disable, then disable against staff recovery.
                boolean recovery = round >= 3;
                List<Future<String>> results = pool.invokeAll(List.<Callable<String>>of(
                        () -> attempt(() -> staffAdministration.setEnabled(first.email(), false, "test-operator")),
                        () -> attempt(() -> {
                            if (recovery) {
                                staffAdministration.recoverStaffAccess(second.email(), "test-operator", "in person");
                            } else {
                                staffAdministration.setEnabled(second.email(), false, "test-operator");
                            }
                        })));
                List<String> outcomes = List.of(results.get(0).get(), results.get(1).get());
                assertThat(outcomes).as("round %d", round).containsExactlyInAnyOrder("removed", "refused");
                assertThat(usable()).as("round %d: never none", round).isEqualTo(1);

                // Put things back for the next round.
                if (outcomes.get(0).equals("removed")) {
                    staffAdministration.setEnabled(first.email(), true, "test-operator");
                } else if (recovery) {
                    // The second one was recovered: it must be activated and enrolled again.
                    jdbc.sql("update account set enabled = true where email = :email").param("email", second.email()).update();
                    reEnroll(second.email());
                } else {
                    staffAdministration.setEnabled(second.email(), true, "test-operator");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void reEnroll(String email) {
        String credential = staffAdministration.recoverStaffAccess(email, "test-operator", "in person");
        Browser browser = browser();
        assertThat(redeem(browser, credential, PASSWORD).status()).isEqualTo(204);
        browser.login(email, PASSWORD);
        byte[] secret = base32Decode(browser.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText());
        assertThat(browser.post("/api/auth/mfa/confirm", json("code", code(secret))).status()).isEqualTo(200);
        nextCode();
    }

    @Test
    void a7_18_recoveryWhenTheOnlySuperAdministratorLostEverything() {
        Staff lost = staff("lost.owner@example.org", "super_admin");
        assertThatThrownBy(() -> staffAdministration.recoverStaffAccess(lost.email(), "test-operator", "in person")).isInstanceOf(StaffAdministration.Refused.class);
        // Adding is always allowed.
        staff("new.owner@example.org", "super_admin");
        String credential = staffAdministration.recoverStaffAccess(lost.email(), "test-operator", "in person");
        assertThat(credential).matches("[A-Za-z0-9_-]{43}");
        assertThat(usable()).isEqualTo(1);
    }

    @Test
    void a13_5_theOperatorCommandRefusesADisposableDatabase() throws Exception {
        assertThat(purpose.disposable()).isTrue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = operator.run("create-super-admin", new BufferedReader(new StringReader("x@example.org\nX\n")), new PrintStream(out, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Refused").contains("disposable");
        assertThat(count("select count(*) from account")).isZero();
        assertThat(jdbc.sql("select database_real from identity_guard").query(Boolean.class).single()).isFalse();
    }

    @Test
    void a13_3_and_13_6_aRealDatabaseNeverAcceptsFictionalDataAndCannotBeMadeDisposableAgain() {
        jdbc.sql("update identity_guard set database_real = true").update();
        assertThat(purpose.disposable()).isFalse();
        // 13.3: the fixture loader refuses, although the disposable marker is still there and the database has no cases of its own.
        assertThatThrownBy(() -> importer.replaceAllData(Json.readMap(Repo.contract("fixtures/example.json"))))
                .isInstanceOf(FixtureException.class).hasMessageContaining("disposable");
        // 13.6: the flags are one-way.
        assertThatThrownBy(() -> jdbc.sql("update identity_guard set database_real = false").update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from identity_guard").update()).isInstanceOf(DataAccessException.class);
        jdbc.sql("update identity_guard set bootstrapped = true").update();
        assertThatThrownBy(() -> jdbc.sql("update identity_guard set bootstrapped = false").update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void a13_4_theFixtureLoaderLeavesIdentityDataAlone() {
        Browser donor = donor("sample.donor@example.org");
        Staff admin = staff("sample.admin@example.org", "admin");
        long accounts = count("select count(*) from account");
        long audit = count("select count(*) from audit_event");
        importer.replaceAllData(Json.readMap(Repo.contract("fixtures/example.json")));
        // The loader pins the clock to the fixture's own; these tests use the same instant as their start.
        clock.pin(START.plusSeconds(30));
        assertThat(count("select count(*) from account")).isEqualTo(accounts);
        assertThat(count("select count(*) from audit_event")).isEqualTo(audit);
        assertThat(count("select count(*) from account_totp where state = 'active'")).isEqualTo(1);
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        assertThat(admin.browser().get("/api/me").status()).isEqualTo(200);
        assertThat(donor.get("/api/cases/HELP-901").status()).isEqualTo(200);
    }
}
