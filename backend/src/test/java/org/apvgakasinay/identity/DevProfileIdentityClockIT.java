package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.apvgakasinay.identity.internal.StaffAdministration;
import org.apvgakasinay.identity.internal.Totp;
import org.apvgakasinay.shared.AppClock;
import org.apvgakasinay.support.Repo;
import org.apvgakasinay.support.SharedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Finding 5 of the first real-browser run (docs/m1b-acceptance-evidence.md of 2026-11-25).
 *
 * The application exactly as a developer starts it: the dev profile, fictional fixtures loaded
 * at start-up, and no time source of the tests. The fixture loader pins the clock of the
 * public read responses to 2026-11-24 12:00 UTC. Identity used that clock too, so the code a
 * real authenticator shows was refused, rate-limit windows never ended and session deadlines
 * were in the past. Identity runs on the real time; the public responses stay as they were.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevProfileIdentityClockIT {

    private static final String FIXTURE_CLOCK = "2026-11-24T12:00:00Z";
    private static final String PASSWORD = "a long harmless passphrase 2026";

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("apv.fixtures.path", () -> Repo.contract("fixtures/example.json").toString());
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AppClock publicClock;

    @Autowired
    StaffAdministration staffAdministration;

    @Autowired
    ApplicationContext context;

    private static String body(String... pairs) {
        return IdentityIntegration.json(pairs);
    }

    private static void assertSoon(String timestamp, Instant from, Duration atMost) {
        Instant instant = Instant.parse(timestamp);
        assertThat(instant).isAfter(from);
        assertThat(instant).isBeforeOrEqualTo(Instant.now().plus(atMost).plusSeconds(5));
    }

    @Test
    void a13_7_inTheDevProfileIdentityRunsOnTheRealTimeAndPublicResponsesKeepTheFixtureClock() {
        Instant started = Instant.now().minusSeconds(1);
        // The premise: the loader has pinned the public clock, and it is not the present.
        assertThat(publicClock.now()).isEqualTo(Instant.parse(FIXTURE_CLOCK));
        assertThat(Duration.between(publicClock.now(), started).abs()).isGreaterThan(Duration.ofHours(1));
        // Nothing in the application supplies a time source for identity; without one it is the system's.
        assertThat(context.getBeanProvider(InstantSource.class).getIfAvailable()).isNull();

        jdbc.sql("""
                truncate table spring_session_retired, spring_session_attributes, spring_session, pending_sign_in, auth_throttle, audit_event, one_time_credential,
                               account_recovery_code, account_totp, account_password, account, identity_guard
                restart identity cascade
                """).update();
        jdbc.sql("insert into identity_guard default values").update();

        Browser visitor = new Browser(port);
        String statsBefore = visitor.get("/api/stats").body();
        assertThat(visitor.get("/api/stats").json().get("asOf").asText()).isEqualTo(FIXTURE_CLOCK);

        // A staff member, created as the operator creates one, chooses a password and enrolls with a real authenticator.
        String email = "sample.owner@example.org";
        String credential = staffAdministration.createStaff(email, "Sample Staff", "super_admin", "test-operator");
        Browser browser = new Browser(port);
        browser.csrf();
        Browser.Answer set = browser.post("/api/auth/set-password", body("credential", credential, "newPassword", PASSWORD));
        assertThat(set.status()).as(set.body()).isEqualTo(204);
        Browser.Answer login = browser.login(email, PASSWORD);
        assertThat(login.json().get("status").asText()).as(login.body()).isEqualTo("mfa_enrollment_required");
        assertSoon(login.json().get("pendingExpiresAt").asText(), started, Duration.ofMinutes(10));
        Browser.Answer enrollment = browser.postEmpty("/api/auth/mfa/enroll");
        assertThat(enrollment.status()).as(enrollment.body()).isEqualTo(200);
        byte[] secret = IdentityIntegration.base32Decode(enrollment.json().get("secret").asText());

        // The code for the fixture's instant is what the pinned clock used to demand. It is wrong now.
        String forTheFixtureClock = Totp.code(secret, Totp.step(Instant.parse(FIXTURE_CLOCK)));
        long realStep = Totp.step(Instant.now());
        boolean coincidence = false;
        for (long step = realStep - 2; step <= realStep + 2; step++) {
            coincidence |= Totp.code(secret, step).equals(forTheFixtureClock);
        }
        if (!coincidence) {
            assertThat(browser.post("/api/auth/mfa/confirm", body("code", forTheFixtureClock)).status()).isEqualTo(401);
        }
        // The code an authenticator application shows at this moment is accepted.
        Browser.Answer confirmed = browser.post("/api/auth/mfa/confirm", body("code", Totp.code(secret, Totp.step(Instant.now()))));
        assertThat(confirmed.status()).as("a code for the real time, status %s", confirmed.status()).isEqualTo(200);
        assertThat(Duration.between(Instant.parse(confirmed.json().get("generatedAt").asText()), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        browser.csrf();

        // Session deadlines are in the future, measured from now.
        Browser.Answer me = browser.get("/api/me");
        assertThat(me.status()).isEqualTo(200);
        assertSoon(me.json().at("/session/idleExpiresAt").asText(), started.plus(Duration.ofMinutes(29)), Duration.ofMinutes(30));
        assertSoon(me.json().at("/session/absoluteExpiresAt").asText(), started.plus(Duration.ofHours(11)), Duration.ofHours(12));
        assertThat(Instant.parse(me.json().get("createdAt").asText())).isAfter(started.minusSeconds(60));

        // And a later sign-in works as well: the next time step's code, as an authenticator would show within half a minute.
        Browser second = new Browser(port);
        Browser.Answer again = second.login(email, PASSWORD);
        assertThat(again.json().get("status").asText()).as(again.body()).isEqualTo("mfa_required");
        Browser.Answer verified = second.post("/api/auth/mfa/verify",
                body("method", "totp", "code", Totp.code(secret, Totp.step(Instant.now()) + 1)));
        assertThat(verified.status()).as("a second sign-in, status %s", verified.status()).isEqualTo(200);

        // Rate-limit windows are windows of the real time, so they end.
        Instant earliestWindow = jdbc.sql("select min(window_start) from auth_throttle").query(java.sql.Timestamp.class).single().toInstant();
        assertThat(earliestWindow).isAfter(started.minus(Duration.ofHours(1)).minusSeconds(1));
        assertThat(jdbc.sql("select count(*) from auth_throttle where window_start > :now").param("now", java.sql.Timestamp.from(Instant.now()))
                .query(Long.class).single()).isZero();
        Instant pendingDeadline = jdbc.sql("select max(expires_at) from pending_sign_in").query(java.sql.Timestamp.class).single().toInstant();
        assertThat(pendingDeadline).isAfter(started);

        // The public read responses did not move: byte for byte what they were, on the fixture's clock.
        assertThat(visitor.get("/api/stats").body()).isEqualTo(statsBefore);
        assertThat(browser.get("/api/stats").body()).isEqualTo(statsBefore);
        assertThat(publicClock.now()).isEqualTo(Instant.parse(FIXTURE_CLOCK));
        Evidence.write("dev-profile-identity-clock", "dev profile with the fixture clock at " + FIXTURE_CLOCK + ": authenticator codes for the real time "
                + "accepted at enrollment and at a second sign-in, session deadlines and rate-limit windows on the real time, public responses unchanged");
    }
}
