package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.apvgakasinay.identity.internal.IdentityMaintenance;
import org.apvgakasinay.identity.internal.Throttle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Corrective review of milestone 1B, findings 4 and 6: cooldown transitions, shared counters, address budgets, housekeeping. */
class RateLimitCorrectionsIT extends IdentityIntegration {

    private static final String EMAIL = "sample.donor@example.org";

    @Autowired
    Throttle throttle;

    @Autowired
    IdentityMaintenance maintenance;

    private List<Long> simultaneously(int requests, Throttle.Limit limit, String key) {
        List<Callable<Long>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            calls.add(() -> throttle.count(limit, key));
        }
        return together(calls);
    }

    @Test
    void a8_3_theCooldownStartsOnceWhenManyRequestsArriveAtTheBoundary() {
        Throttle.Limit limit = Throttle.PASSWORD_BY_ACCOUNT;
        for (int round = 0; round < 3; round++) {
            String key = "boundary-" + round;
            // One short of the limit.
            for (int i = 0; i < limit.max() - 1; i++) {
                assertThat(throttle.count(limit, key)).isZero();
            }
            List<Long> waits = simultaneously(30, limit, key);
            // Exactly one more attempt fits. Every other one is refused, and none slips through the reset of the counter.
            assertThat(waits.stream().filter(wait -> wait == 0).count()).as("round %d: %s", round, waits).isEqualTo(1);
            assertThat(waits.stream().filter(wait -> wait > 0)).allMatch(wait -> wait <= 900);
            assertThat(count("select count(*) from auth_throttle where scope = 'password.account#blocked'")).as("one cooldown per key").isEqualTo(round + 1);

            // During the cooldown everything is refused, also simultaneously.
            advance(Duration.ofMinutes(14));
            assertThat(simultaneously(10, limit, key)).allMatch(wait -> wait > 0 && wait <= 60);
            assertThat(count("select count(*) from auth_throttle where scope = 'password.account#blocked'")).isEqualTo(round + 1);
        }
        // When it has passed, counting starts again from one.
        advance(Duration.ofMinutes(2));
        assertThat(throttle.count(limit, "boundary-2")).isZero();
        assertThat(simultaneously(19, limit, "boundary-2")).allMatch(wait -> wait == 0);
        assertThat(throttle.count(limit, "boundary-2")).isEqualTo(900);
    }

    @Test
    void a8_5_aWindowLimitAdmitsExactlyItsNumberOfSimultaneousAttempts() {
        Throttle.Limit limit = Throttle.PASSWORD_BY_EMAIL_AND_ADDRESS;
        List<Long> waits = simultaneously(40, limit, "pair-key");
        assertThat(waits.stream().filter(wait -> wait == 0).count()).isEqualTo(limit.max());
        assertThat(count("select attempts from auth_throttle where scope = 'password.pair'")).isEqualTo(40);
    }

    @Test
    void a8_9_onePasswordCounterPerAccountAcrossSignInPasswordChangeAndRecoveryCodes() {
        Staff admin = staff("sample.admin@example.org", "admin");
        // Seven wrong passwords at sign-in, from seven addresses, in different letter case.
        for (int i = 0; i < 7; i++) {
            Browser browser = browser();
            browser.csrf();
            assertError(browser.post("/api/auth/login", json("email", "Sample.Admin@Example.org", "password", OTHER_PASSWORD)), 401, "invalid_credentials");
        }
        // Seven on recovery-code regeneration.
        for (int i = 0; i < 7; i++) {
            assertError(admin.browser().post("/api/auth/mfa/recovery-codes", json("currentPassword", OTHER_PASSWORD, "code", "000000")), 401, "invalid_credentials");
        }
        // Six on password change. Twenty in all, on one counter.
        for (int i = 0; i < 6; i++) {
            assertError(admin.browser().post("/api/auth/change-password", json("currentPassword", OTHER_PASSWORD, "newPassword", "a third harmless passphrase 2028")), 401, "invalid_credentials");
        }
        assertThat(count("select count(*) from auth_throttle where scope = 'password.account'")).as("one key").isEqualTo(1);
        assertThat(count("select attempts from auth_throttle where scope = 'password.account'")).isEqualTo(20);

        // The twenty-first, in any of the three, starts the pause for all three.
        Browser.Answer change = admin.browser().post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", "a third harmless passphrase 2028"));
        assertError(change, 429, "rate_limited");
        assertThat(change.header("Retry-After")).isEqualTo("900");
        assertError(admin.browser().post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", code(admin.secret()))), 429, "rate_limited");
        assertError(browser().login(admin.email(), PASSWORD), 429, "rate_limited");
        // Nothing was changed, the session is intact, and an address that never existed is counted the same way.
        assertThat(admin.browser().get("/api/me").status()).isEqualTo(200);
        assertThat(count("select count(*) from account_recovery_code where used_at is null")).isEqualTo(10);
        for (int i = 0; i < 20; i++) {
            Browser browser = browser();
            browser.csrf();
            assertError(browser.post("/api/auth/login", json("email", "nobody@example.org", "password", OTHER_PASSWORD)), 401, "invalid_credentials");
        }
        Browser.Answer unknown = browser().login("nobody@example.org", PASSWORD);
        assertError(unknown, 429, "rate_limited");
        assertThat(unknown.header("Retry-After")).isEqualTo("900");
    }

    @Test
    void a8_9_protectedPasswordOperationsHaveAClientAddressBudgetCountedBeforeTheBodyIsRead() {
        Browser donor = donor(EMAIL);
        jdbc.sql("delete from auth_throttle").update();
        String change = "/api/auth/change-password";
        // Thirty requests from one address in fifteen minutes, whatever they contain.
        for (int i = 0; i < 30; i++) {
            assertError(donor.post(change, "{}"), 400, "validation_error");
        }
        assertThat(count("select attempts from auth_throttle where scope = 'protected.address'")).isEqualTo(30);
        assertThat(count("select count(*) from auth_throttle where scope like 'password.%'")).as("no password was checked or counted").isZero();
        // The thirty-first is refused at step 5: before the state, the body and the password.
        Browser.Answer refused = donor.post(change, json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD));
        assertError(refused, 429, "rate_limited");
        assertThat(Long.parseLong(refused.header("Retry-After"))).isBetween(1L, 900L);
        assertThat(count("select count(*) from auth_throttle where scope like 'password.%'")).isZero();
        // A missing token is refused before the limit and counts toward nothing.
        assertError(donor.post(change, "{}", null), 403, "csrf_invalid");
        assertThat(count("select attempts from auth_throttle where scope = 'protected.address'")).isEqualTo(31);
        // It is this address's budget only, and it does not touch sign-in.
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        Browser elsewhere = browser();
        assertThat(elsewhere.login(EMAIL, PASSWORD).status()).isEqualTo(200);
        assertThat(elsewhere.post(change, json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)).status()).isEqualTo(204);
        advance(Duration.ofMinutes(15));
        assertError(donor.get("/api/me"), 401, "unauthenticated");
    }

    /** Scenario 8.7, owner decision of 2026-11-25: one budget of thirty for both protected password operations, apart from sign-in. */
    @Test
    void a8_7_passwordChangeAndRecoveryCodesShareOneClientAddressBudgetSeparateFromSignIn() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = admin.browser();
        String change = "/api/auth/change-password";
        String regenerate = "/api/auth/mfa/recovery-codes";
        jdbc.sql("delete from auth_throttle").update();

        // Fifteen requests to each operation: thirty on one counter.
        for (int i = 0; i < 15; i++) {
            assertError(browser.post(change, "{}"), 400, "validation_error");
            assertError(browser.post(regenerate, "{}"), 400, "validation_error");
        }
        assertThat(count("select count(*) from auth_throttle where scope = 'protected.address'")).as("one shared counter").isEqualTo(1);
        assertThat(count("select attempts from auth_throttle where scope = 'protected.address'")).isEqualTo(30);
        assertThat(count("select count(*) from auth_throttle where scope = 'signin.address'")).as("the sign-in budget was not touched").isZero();

        // Request 31, on either operation, with correct secrets: refused at step 5, before state, body and secrets.
        advance(java.time.Duration.ofMinutes(4));
        long step = count("select last_accepted_step from account_totp");
        Browser.Answer refusedChange = browser.post(change, json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD));
        Browser.Answer refusedRegenerate = browser.post(regenerate, json("currentPassword", PASSWORD, "code", code(admin.secret())));
        for (Browser.Answer refused : List.of(refusedChange, refusedRegenerate)) {
            assertError(refused, 429, "rate_limited");
            // Exactly until the fifteen-minute window ends, so never more than fifteen minutes.
            long untilWindowEnds = 900 - clock.now().getEpochSecond() % 900;
            assertThat(Long.parseLong(refused.header("Retry-After"))).isEqualTo(untilWindowEnds).isBetween(1L, 900L);
        }
        assertThat(count("select count(*) from auth_throttle where scope like 'password.%' or scope like 'mfa.%'")).as("no secret was checked or counted").isZero();
        assertThat(count("select last_accepted_step from account_totp")).as("the code was not consumed").isEqualTo(step);
        assertThat(count("select count(*) from account_recovery_code where used_at is null")).isEqualTo(10);
        // A malformed body gets the same answer: the limit comes before validation.
        assertError(browser.post(regenerate, "not json"), 429, "rate_limited");

        // What comes before step 5 still comes before it, and counts toward nothing.
        long counted = count("select attempts from auth_throttle where scope = 'protected.address'");
        assertError(browser.post(change, "{}", null), 403, "csrf_invalid");
        assertError(browser.send("POST", change, "{}", "text/plain", browser.token), 415, "unsupported_media_type");
        assertError(browser.send("PUT", change, "{}", "application/json", browser.token), 405, "method_not_allowed");
        browser.extraHeaders.put("Origin", "https://attacker.example");
        assertError(browser.post(change, "{}"), 403, "forbidden");
        browser.extraHeaders.clear();
        assertThat(count("select attempts from auth_throttle where scope = 'protected.address'")).isEqualTo(counted);

        // The session is untouched, and the address can still sign in: that is another budget.
        assertThat(browser.get("/api/me").status()).isEqualTo(200);
        Browser sameAddress = browser();
        sameAddress.address = browser.address;
        Browser.Answer signIn = sameAddress.login(admin.email(), PASSWORD);
        assertThat(signIn.status()).as(signIn.body()).isEqualTo(200);
        // And thirty sign-in requests from an address do not use up its protected budget.
        Browser other = donor(EMAIL);
        for (int i = 0; i < 29; i++) {
            assertError(other.post("/api/auth/login", "{}"), 409, "already_signed_in");
        }
        assertError(other.post("/api/auth/login", "{}"), 429, "rate_limited");
        assertError(other.post(change, "{}"), 400, "validation_error");

        // When the window ends the budget is whole again.
        advance(java.time.Duration.ofMinutes(11));
        assertError(browser.post(change, "{}"), 400, "validation_error");
        Browser.Answer regenerated = browser.post(regenerate, json("currentPassword", PASSWORD, "code", code(admin.secret())));
        assertThat(regenerated.status()).as(regenerated.body()).isEqualTo(200);
    }

    @Test
    void housekeepingRemovesOnlyWhatCanNoLongerMatter() {
        // Every window and cooldown is far shorter than the retention the housekeeping uses.
        for (Throttle.Limit limit : Throttle.ALL) {
            assertThat(limit.window()).isLessThanOrEqualTo(Duration.ofHours(1));
            assertThat(limit.cooldown() == null ? Duration.ZERO : limit.cooldown()).isLessThanOrEqualTo(Duration.ofHours(1));
        }
        assertThat(Throttle.RETENTION).isGreaterThanOrEqualTo(Duration.ofHours(2));

        // An active cooldown, a current window, a pending sign-in in progress.
        Throttle.Limit limit = Throttle.SECOND_FACTOR_BY_ACCOUNT;
        for (int i = 0; i <= limit.max(); i++) {
            throttle.count(limit, "cooling");
        }
        assertThat(throttle.count(limit, "cooling")).isEqualTo(900);
        throttle.count(Throttle.SIGN_IN_BY_ADDRESS, "current");
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser pending = pending(admin);
        long live = count("select count(*) from auth_throttle");
        assertThat(maintenance.run()).as("nothing is old yet").isZero();

        // Old rows: windows and a cooldown marker from three hours ago, and pending sign-ins that ended long ago.
        Timestamp old = Timestamp.from(clock.now().minus(Duration.ofHours(3)));
        jdbc.sql("""
                insert into auth_throttle (scope, key_hash, window_start, attempts)
                select case when n % 2 = 0 then 'signin.address' else 'mfa.account#blocked' end, decode(md5(n::text) || md5(n::text), 'hex'), :old, 3
                  from generate_series(1, 25000) n
                """).param("old", old).update();
        jdbc.sql("""
                insert into pending_sign_in (id, account_id, expires_at)
                select decode(md5(n::text) || md5(n::text), 'hex'), (select min(id) from account), :old from generate_series(1, 50) n
                """).param("old", old).update();

        // One run is bounded; the next one finishes.
        assertThat(maintenance.run()).isEqualTo(20_000 + 50);
        assertThat(count("select count(*) from auth_throttle")).isEqualTo(live + 5_000);
        assertThat(maintenance.run()).isEqualTo(5_000);
        assertThat(maintenance.run()).isZero();

        // Everything live is still there and still decides.
        assertThat(count("select count(*) from auth_throttle")).isEqualTo(live);
        assertThat(throttle.count(limit, "cooling")).as("the active cooldown survived").isBetween(800L, 900L);
        assertThat(count("select count(*) from pending_sign_in where outcome is null")).isEqualTo(1);
        assertThat(pending.post("/api/auth/mfa/verify", json("method", "totp", "code", code(admin.secret()))).status()).isEqualTo(200);

        // Two hours on, the cooldown and the windows are history and are removed too.
        advance(Duration.ofHours(2).plusMinutes(20));
        assertThat(maintenance.run()).isGreaterThanOrEqualTo((int) live);
        assertThat(count("select count(*) from auth_throttle")).isZero();
        assertThat(count("select count(*) from pending_sign_in")).isZero();
    }
}
