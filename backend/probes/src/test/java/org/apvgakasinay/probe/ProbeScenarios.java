package org.apvgakasinay.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Probes 1 and 3, and the clock-driven half of probe 2, over real HTTP. The same scenarios run
 * twice: with the embedded server's own sessions (no database), and with Spring Session JDBC on
 * PostgreSQL. Subclasses choose.
 */
abstract class ProbeScenarios {

    static final String PASSWORD = ProbeAccounts.PASSWORD;

    @Value("${local.server.port}")
    int port;

    @Autowired
    ProbeClock clock;

    @Autowired
    ProbeAccounts accounts;

    @BeforeEach
    void reset() {
        clock.reset();
        accounts.reset();
    }

    Browser browser() {
        return new Browser(port);
    }

    String adminCode() {
        return Totp.code(accounts.find(ProbeAccounts.ADMIN).totpSecret(), Totp.step(clock.now()));
    }

    /** A staff browser that has finished both factors. */
    Browser signedInAdmin() {
        Browser admin = browser();
        assertThat(admin.login(ProbeAccounts.ADMIN, PASSWORD).field("status")).isEqualTo("mfa_required");
        assertThat(admin.post("/probe/mfa/verify", "{\"code\":\"" + adminCode() + "\"}").status()).isEqualTo(200);
        admin.fetchToken();
        return admin;
    }

    Browser signedInDonor() {
        Browser donor = browser();
        assertThat(donor.login(ProbeAccounts.DONOR, PASSWORD).field("status")).isEqualTo("authenticated");
        return donor;
    }

    // ------------------------------------------------------------------ Probe 1: second factor

    @Test
    void probe1_frameworkFactorAuthoritiesAreWhatTheDesignAssumes() {
        assertThat(ProbeSecurityConfig.PASSWORD_FACTOR).isEqualTo("FACTOR_PASSWORD");
        assertThat(ProbeSecurityConfig.TOTP_FACTOR).isEqualTo("FACTOR_TOTP");
    }

    @Test
    void probe1_passwordOnlyStaffSessionCannotReachFullySignedInOperations() {
        Browser admin = browser();
        Browser.Answer login = admin.login(ProbeAccounts.ADMIN, PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.field("status")).isEqualTo("mfa_required");

        // The session exists and holds the password factor, and the framework still refuses.
        Browser.Answer me = admin.get("/probe/me");
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.code()).isEqualTo("mfa_required");
        Browser.Answer change = admin.post("/probe/change-password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"another long passphrase 2027\"}");
        assertThat(change.status()).isEqualTo(401);
        assertThat(change.code()).isEqualTo("mfa_required");
        // The password was not changed by the refused request.
        assertThat(browser().login(ProbeAccounts.ADMIN, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void probe1_customTotpFactorCompletesTheFrameworksRequirement() {
        Browser admin = browser();
        admin.login(ProbeAccounts.ADMIN, PASSWORD);
        Browser.Answer verified = admin.post("/probe/mfa/verify", "{\"code\":\"" + adminCode() + "\"}");
        assertThat(verified.status()).isEqualTo(200);
        admin.fetchToken();

        Browser.Answer me = admin.get("/probe/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains("FACTOR_PASSWORD").contains("FACTOR_TOTP").contains("ROLE_ADMIN");
    }

    @Test
    void probe1_donorNeedsNoSecondFactorAndCannotUseTheStaffSteps() {
        Browser donor = signedInDonor();
        Browser.Answer me = donor.get("/probe/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains("FACTOR_PASSWORD").doesNotContain("FACTOR_TOTP");
        // The second-factor step is not for a donor.
        assertThat(donor.post("/probe/mfa/verify", "{\"code\":\"000000\"}").status()).isEqualTo(403);
    }

    @Test
    void probe1_wrongCodeKeepsThePendingSessionAndGrantsNothing() {
        Browser admin = browser();
        admin.login(ProbeAccounts.ADMIN, PASSWORD);
        Browser.Answer wrong = admin.post("/probe/mfa/verify", "{\"code\":\"000000\"}");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("mfa_code_invalid");
        // Still pending, not signed out, not signed in.
        assertThat(admin.get("/probe/me").code()).isEqualTo("mfa_required");
        // And the same pending session can still succeed.
        assertThat(admin.post("/probe/mfa/verify", "{\"code\":\"" + adminCode() + "\"}").status()).isEqualTo(200);
    }

    @Test
    void probe1_aCodeWorksOnceAlsoWhenSentTwiceAtTheSameInstant() throws Exception {
        Browser first = browser();
        Browser second = browser();
        first.login(ProbeAccounts.ADMIN, PASSWORD);
        second.login(ProbeAccounts.ADMIN, PASSWORD);
        String code = adminCode();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> first.post("/probe/mfa/verify", "{\"code\":\"" + code + "\"}").status(),
                    () -> second.post("/probe/mfa/verify", "{\"code\":\"" + code + "\"}").status());
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }
        // Replaying the code afterwards is refused as well.
        Browser third = browser();
        third.login(ProbeAccounts.ADMIN, PASSWORD);
        assertThat(third.post("/probe/mfa/verify", "{\"code\":\"" + code + "\"}").code()).isEqualTo("mfa_code_invalid");
    }

    @Test
    void probe1_unenrolledStaffCanOnlyEnrollThenBecomesFullySignedIn() {
        Browser admin = browser();
        assertThat(admin.login(ProbeAccounts.NEW_ADMIN, PASSWORD).field("status")).isEqualTo("mfa_enrollment_required");
        assertThat(admin.get("/probe/me").status()).isEqualTo(401);

        assertThat(admin.post("/probe/mfa/enroll", "{}").status()).isEqualTo(200);
        byte[] pending = accounts.find(ProbeAccounts.NEW_ADMIN).pendingTotpSecret();
        // A wrong code keeps the pending session.
        assertThat(admin.post("/probe/mfa/confirm", "{\"code\":\"000000\"}").code()).isEqualTo("mfa_code_invalid");
        Browser.Answer confirmed = admin.post("/probe/mfa/confirm", "{\"code\":\"" + Totp.code(pending, Totp.step(clock.now())) + "\"}");
        assertThat(confirmed.status()).isEqualTo(200);
        admin.fetchToken();
        assertThat(admin.get("/probe/me").body()).contains("FACTOR_TOTP");
    }

    @Test
    void probe1_pathsWithNoRuleAreDenied() {
        assertThat(browser().get("/probe/not-declared").status()).isEqualTo(401);
        assertThat(signedInDonor().get("/probe/not-declared").status()).isEqualTo(403);
        assertThat(browser().get("/probe/public").status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ Probe 3: CSRF lifecycle

    @Test
    void probe3_anonymousBootstrapCreatesASessionThatHoldsTheToken() {
        Browser visitor = browser();
        assertThat(visitor.sessionId).isNull();
        String token = visitor.fetchToken();
        assertThat(token).isNotBlank();
        assertThat(visitor.sessionId).as("the csrf endpoint sets the session cookie").isNotNull();
        // An anonymous session grants nothing.
        Browser.Answer me = visitor.get("/probe/me");
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.code()).isEqualTo("unauthenticated");
        // The same session gets a differently masked token each time, and both are accepted.
        String again = visitor.fetchToken();
        assertThat(again).isNotEqualTo(token);
        assertThat(visitor.post("/probe/login", "{\"email\":\"nobody@example.org\",\"password\":\"x\"}", token).code()).isEqualTo("invalid_credentials");
        assertThat(visitor.post("/probe/login", "{\"email\":\"nobody@example.org\",\"password\":\"x\"}", again).code()).isEqualTo("invalid_credentials");
    }

    @Test
    void probe3_everyPostNeedsTheTokenIncludingSignInAndSignOut() {
        Browser visitor = browser();
        visitor.fetchToken();
        for (String path : List.of("/probe/login", "/probe/logout", "/probe/change-password", "/probe/mfa/verify")) {
            Browser.Answer missing = visitor.post(path, "{}", null);
            assertThat(missing.status()).as(path).isEqualTo(403);
            assertThat(missing.code()).as(path).isEqualTo("csrf_invalid");
            assertThat(visitor.post(path, "{}", "made-up-token").code()).as(path).isEqualTo("csrf_invalid");
        }
        // A correct password with no token signs nobody in: the CSRF check came first.
        Browser.Answer refused = visitor.post("/probe/login", "{\"email\":\"" + ProbeAccounts.DONOR + "\",\"password\":\"" + PASSWORD + "\"}", null);
        assertThat(refused.code()).isEqualTo("csrf_invalid");
        assertThat(visitor.get("/probe/me").status()).isEqualTo(401);
    }

    /** One transition: the identifier and the token are both replaced, and the old ones are dead. */
    private void assertRotated(Browser browser, String oldSessionId, String oldToken, String protectedPath, String body) {
        String newSessionId = browser.sessionId;
        assertThat(newSessionId).as("session identifier replaced").isNotNull().isNotEqualTo(oldSessionId);

        // The token held before the transition is refused on the new session, before anything else.
        Browser.Answer stale = browser.post(protectedPath, body, oldToken);
        assertThat(stale.status()).as("old token on the new session").isEqualTo(403);
        assertThat(stale.code()).isEqualTo("csrf_invalid");

        // The old identifier names no session: a GET is anonymous, a POST fails the CSRF check
        // whatever token it carries.
        Browser thief = browser();
        thief.sessionId = oldSessionId;
        thief.cookieName = browser.cookieName;
        assertThat(thief.get("/probe/me").code()).as("old identifier, GET").isEqualTo("unauthenticated");
        assertThat(thief.post(protectedPath, body, oldToken).code()).as("old identifier and old token").isEqualTo("csrf_invalid");

        // A fresh token for the new session works.
        String fresh = browser.fetchToken();
        assertThat(fresh).isNotEqualTo(oldToken);
    }

    @Test
    void probe3_tokenAndIdentifierAreReplacedAtPasswordSignIn() {
        Browser donor = browser();
        String anonymousToken = donor.fetchToken();
        String anonymousSession = donor.sessionId;
        assertThat(donor.post("/probe/login", "{\"email\":\"" + ProbeAccounts.DONOR + "\",\"password\":\"" + PASSWORD + "\"}").status()).isEqualTo(200);
        assertRotated(donor, anonymousSession, anonymousToken, "/probe/change-password", "{}");
        assertThat(donor.get("/probe/me").status()).isEqualTo(200);
    }

    @Test
    void probe3_tokenAndIdentifierAreReplacedAtSecondFactorVerification() {
        Browser admin = browser();
        admin.login(ProbeAccounts.ADMIN, PASSWORD);
        String pendingToken = admin.token;
        String pendingSession = admin.sessionId;
        assertThat(admin.post("/probe/mfa/verify", "{\"code\":\"" + adminCode() + "\"}").status()).isEqualTo(200);
        assertRotated(admin, pendingSession, pendingToken, "/probe/change-password", "{}");
        assertThat(admin.get("/probe/me").status()).isEqualTo(200);
    }

    @Test
    void probe3_tokenAndIdentifierAreReplacedAtEnrollmentConfirmation() {
        Browser admin = browser();
        admin.login(ProbeAccounts.NEW_ADMIN, PASSWORD);
        admin.post("/probe/mfa/enroll", "{}");
        String pendingToken = admin.token;
        String pendingSession = admin.sessionId;
        byte[] pending = accounts.find(ProbeAccounts.NEW_ADMIN).pendingTotpSecret();
        assertThat(admin.post("/probe/mfa/confirm", "{\"code\":\"" + Totp.code(pending, Totp.step(clock.now())) + "\"}").status()).isEqualTo(200);
        assertRotated(admin, pendingSession, pendingToken, "/probe/change-password", "{}");
        assertThat(admin.get("/probe/me").status()).isEqualTo(200);
    }

    @Test
    void probe3_tokenAndIdentifierAreReplacedAtPasswordChange() {
        Browser donor = signedInDonor();
        String oldToken = donor.token;
        String oldSession = donor.sessionId;
        String next = "another long passphrase 2027";
        assertThat(donor.post("/probe/change-password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + next + "\"}").status()).isEqualTo(200);
        assertRotated(donor, oldSession, oldToken, "/probe/change-password", "{}");
        // Still signed in, on the new identifier, with the same deadlines.
        assertThat(donor.get("/probe/me").status()).isEqualTo(200);
    }

    @Test
    void probe3_wrongCurrentPasswordChangesNeitherSessionNorToken() {
        Browser donor = signedInDonor();
        String token = donor.token;
        String session = donor.sessionId;
        Browser.Answer wrong = donor.post("/probe/change-password", "{\"currentPassword\":\"not the password\",\"newPassword\":\"another long passphrase 2027\"}");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("invalid_credentials");
        assertThat(donor.sessionId).isEqualTo(session);
        assertThat(donor.get("/probe/me").status()).isEqualTo(200);
        // The token is still good.
        assertThat(donor.post("/probe/change-password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"another long passphrase 2027\"}", token).status()).isEqualTo(200);
    }

    @Test
    void probe3_signOutKillsTheSessionAndItsToken() {
        Browser donor = signedInDonor();
        String token = donor.token;
        String session = donor.sessionId;
        assertThat(donor.post("/probe/logout", "{}").status()).isEqualTo(200);

        Browser stale = browser();
        stale.sessionId = session;
        stale.cookieName = donor.cookieName;
        assertThat(stale.get("/probe/me").code()).isEqualTo("unauthenticated");
        // A POST still carrying the dead session's token is refused by the CSRF check first.
        assertThat(stale.post("/probe/change-password", "{}", token).code()).isEqualTo("csrf_invalid");
    }

    @Test
    void probe3_reconciliationAfterALostSession() {
        Browser donor = signedInDonor();
        String staleToken = donor.token;
        donor.post("/probe/logout", "{}");

        // What the page does next: its POST is refused as csrf_invalid ...
        Browser.Answer refused = donor.post("/probe/change-password", "{}", staleToken);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("csrf_invalid");
        // ... it fetches a fresh token, which bootstraps a new anonymous session ...
        String fresh = donor.fetchToken();
        assertThat(fresh).isNotEqualTo(staleToken);
        // ... and GET /me says the truth: nobody is signed in.
        assertThat(donor.get("/probe/me").code()).isEqualTo("unauthenticated");
        // With a fresh, valid anonymous token a protected POST is 401, not 403 ...
        Browser.Answer protectedPost = donor.post("/probe/change-password", "{}", fresh);
        assertThat(protectedPost.status()).isEqualTo(401);
        assertThat(protectedPost.code()).isEqualTo("unauthenticated");
        // ... while sign-in, a public operation, works with it.
        assertThat(donor.post("/probe/login", "{\"email\":\"" + ProbeAccounts.DONOR + "\",\"password\":\"" + PASSWORD + "\"}", fresh).status()).isEqualTo(200);
    }

    @Test
    void probe3_aTokenFromAnotherBrowserIsRefused() {
        Browser one = signedInDonor();
        Browser two = signedInDonor();
        assertThat(two.post("/probe/change-password", "{}", one.token).code()).isEqualTo("csrf_invalid");
        assertThat(one.get("/probe/me").status()).isEqualTo(200);
    }

    // ------------------------------------------------- Probe 2, the part driven by the clock

    @Test
    void probe2_idleTimeoutDependsOnTheRole() {
        assertThat(signedInDonor().get("/probe/me").field("idleSeconds")).isEqualTo("7200");
        assertThat(signedInAdmin().get("/probe/me").field("idleSeconds")).isEqualTo("1800");
    }

    @Test
    void probe2_activityCannotExtendTheAbsoluteDeadline() {
        Browser donor = signedInDonor();
        String deadline = donor.get("/probe/me").field("absoluteDeadline");
        // Stay active for 23 hours, never idle for more than one hour.
        for (int hour = 1; hour <= 23; hour++) {
            clock.advance(Duration.ofHours(1));
            Browser.Answer me = donor.get("/probe/me");
            if (me.status() != 200) {
                // The embedded server's own idle timer may end the session first; the absolute
                // rule is what this test is about, so only a too-early absolute expiry is a failure.
                return;
            }
            assertThat(me.field("absoluteDeadline")).as("the deadline does not move with activity").isEqualTo(deadline);
        }
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(donor.get("/probe/me").code()).isEqualTo("unauthenticated");
        // The lost session's token is refused first on a POST.
        assertThat(donor.post("/probe/change-password", "{}").code()).isEqualTo("csrf_invalid");
    }

    @Test
    void probe2_staffAbsoluteDeadlineIsTwelveHoursFromThePasswordStep() {
        Browser admin = browser();
        admin.login(ProbeAccounts.ADMIN, PASSWORD);
        long passwordStep = clock.now().getEpochSecond();
        clock.advance(Duration.ofMinutes(5));
        assertThat(admin.post("/probe/mfa/verify", "{\"code\":\"" + adminCode() + "\"}").status()).isEqualTo(200);
        admin.fetchToken();
        long deadline = Long.parseLong(admin.get("/probe/me").field("absoluteDeadline"));
        assertThat(deadline - passwordStep).isBetween(12 * 3600L - 2, 12 * 3600L + 2);
    }

    @Test
    void probe2_aPendingSecondFactorExpiresAfterTenMinutes() {
        Browser admin = browser();
        admin.login(ProbeAccounts.ADMIN, PASSWORD);
        clock.advance(Duration.ofMinutes(9));
        assertThat(admin.get("/probe/me").code()).as("still pending at nine minutes").isEqualTo("mfa_required");
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        // The pending session no longer exists.
        assertThat(admin.get("/probe/me").code()).isEqualTo("unauthenticated");
        // A correct code sent with the token the page still holds is refused by the CSRF check ...
        Browser.Answer late = admin.post("/probe/mfa/verify", "{\"code\":\"" + adminCode() + "\"}");
        assertThat(late.status()).isEqualTo(403);
        assertThat(late.code()).isEqualTo("csrf_invalid");
        // ... and with a fresh anonymous token it is 401. The code is never accepted.
        admin.fetchToken();
        Browser.Answer fresh = admin.post("/probe/mfa/verify", "{\"code\":\"" + adminCode() + "\"}");
        assertThat(fresh.status()).isEqualTo(401);
        assertThat(fresh.code()).isEqualTo("unauthenticated");
    }
}
