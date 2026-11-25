package org.apvgakasinay.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every scenario again, with sessions stored by Spring Session JDBC in a real PostgreSQL started
 * by Testcontainers, plus the parts of probe 2 that can only be shown against the database:
 * the per-session idle timeout in the row, and expiry while the row is still there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresProbeIT extends ProbeScenarios {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("apv.postgres.image", "postgres:18.7"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> sessions;

    /** Every test starts with no stored session, so counts of an account's sessions are exact. */
    @BeforeEach
    void noStoredSessions() {
        jdbc.sql("delete from SPRING_SESSION").update();
    }

    /** The cookie carries the session identifier in Base64. */
    private static String sessionIdOf(Browser browser) {
        return new String(Base64.getDecoder().decode(browser.sessionId), StandardCharsets.UTF_8);
    }

    private Map<String, Object> row(Browser browser) {
        return jdbc.sql("select MAX_INACTIVE_INTERVAL, LAST_ACCESS_TIME, EXPIRY_TIME, PRINCIPAL_NAME from SPRING_SESSION where SESSION_ID = :id")
                .param("id", sessionIdOf(browser)).query().singleRow();
    }

    private long rows(Browser browser) {
        return jdbc.sql("select count(*) from SPRING_SESSION where SESSION_ID = :id").param("id", sessionIdOf(browser)).query(Long.class).single();
    }

    /** Makes a session look as if nothing had touched it for the given time. The row stays. */
    private void lastUsed(Browser browser, Duration ago) {
        int updated = jdbc.sql("""
                        update SPRING_SESSION
                           set LAST_ACCESS_TIME = :lastAccess,
                               EXPIRY_TIME = :lastAccess + MAX_INACTIVE_INTERVAL * 1000
                         where SESSION_ID = :id
                        """)
                .param("lastAccess", System.currentTimeMillis() - ago.toMillis())
                .param("id", sessionIdOf(browser))
                .update();
        assertThat(updated).isEqualTo(1);
    }

    @Test
    void probe2_sessionsAreStoredInPostgres18BySpringSessionJdbc() {
        assertThat(jdbc.sql("select version()").query(String.class).single()).startsWith("PostgreSQL 18.");
        assertThat(sessions.getClass().getName()).isEqualTo("org.springframework.session.jdbc.JdbcIndexedSessionRepository");
        Browser donor = signedInDonor();
        assertThat(donor.cookieName).isEqualTo("apv_session");
        assertThat(rows(donor)).isEqualTo(1);
        long attributes = jdbc.sql("""
                select count(*) from SPRING_SESSION_ATTRIBUTES a join SPRING_SESSION s on s.PRIMARY_ID = a.SESSION_PRIMARY_ID
                 where s.SESSION_ID = :id
                """).param("id", sessionIdOf(donor)).query(Long.class).single();
        // The authentication, the deadlines and the CSRF token all live in the database row.
        assertThat(attributes).isGreaterThanOrEqualTo(4);
    }

    @Test
    void probe2_eachRowCarriesItsOwnIdleTimeoutByRoleAndState() {
        Browser visitor = browser();
        visitor.fetchToken();
        assertThat(row(visitor).get("max_inactive_interval")).as("anonymous").isEqualTo(1800);
        assertThat(row(visitor).get("principal_name")).isNull();

        Browser pending = browser();
        pending.login(ProbeAccounts.ADMIN, PASSWORD);
        assertThat(row(pending).get("max_inactive_interval")).as("pending second factor").isEqualTo(600);

        Browser donor = signedInDonor();
        assertThat(row(donor).get("max_inactive_interval")).as("donor").isEqualTo(7200);
        assertThat(row(donor).get("principal_name")).isEqualTo(ProbeAccounts.DONOR);

        Browser admin = signedInAdmin();
        assertThat(row(admin).get("max_inactive_interval")).as("staff").isEqualTo(1800);
    }

    @Test
    void probe2_idleTimeoutIsEnforcedPerSessionAndWhileTheRowStillExists() {
        Browser donor = signedInDonor();
        Browser admin = signedInAdmin();

        // 31 minutes of silence: past the staff limit, well inside the donor limit.
        lastUsed(donor, Duration.ofMinutes(31));
        lastUsed(admin, Duration.ofMinutes(31));
        assertThat(rows(admin)).as("the expired row has not been cleaned up").isEqualTo(1);
        assertThat(((Number) row(admin).get("expiry_time")).longValue()).isLessThan(System.currentTimeMillis());

        assertThat(donor.get("/probe/me").status()).as("donor at 31 minutes").isEqualTo(200);
        Browser.Answer staffGet = admin.get("/probe/me");
        assertThat(staffGet.status()).as("staff at 31 minutes").isEqualTo(401);
        assertThat(staffGet.code()).isEqualTo("unauthenticated");
    }

    @Test
    void probe2_anExpiredRowIsRefusedOnAPostBeforeAnythingElse() {
        Browser donor = signedInDonor();
        String token = donor.token;
        lastUsed(donor, Duration.ofHours(2).plusSeconds(5));
        assertThat(rows(donor)).as("row present, expired, not cleaned up").isEqualTo(1);

        // The page's next POST still carries the dead session's cookie and token.
        Browser.Answer post = donor.post("/probe/change-password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"another long passphrase 2027\"}", token);
        assertThat(post.status()).isEqualTo(403);
        assertThat(post.code()).isEqualTo("csrf_invalid");
        // The password was not changed.
        assertThat(browser().login(ProbeAccounts.DONOR, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void probe2_activityMovesTheIdleDeadlineInTheRowButNeverTheAbsoluteOne() {
        Browser donor = signedInDonor();
        String deadline = donor.get("/probe/me").field("absoluteDeadline");
        lastUsed(donor, Duration.ofMinutes(90));
        long before = ((Number) row(donor).get("last_access_time")).longValue();
        assertThat(donor.get("/probe/me").status()).isEqualTo(200);
        long after = ((Number) row(donor).get("last_access_time")).longValue();
        assertThat(after).as("activity refreshed the idle clock").isGreaterThan(before + Duration.ofMinutes(80).toMillis());
        assertThat(donor.get("/probe/me").field("absoluteDeadline")).isEqualTo(deadline);

        // Past the absolute deadline the session is refused although it was just used and its row is fresh.
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        assertThat(((Number) row(donor).get("expiry_time")).longValue()).as("not idle-expired").isGreaterThan(System.currentTimeMillis());
        assertThat(donor.get("/probe/me").code()).isEqualTo("unauthenticated");
    }

    @Test
    void probe2_pendingSessionRowExpiresByIdleTimeToo() {
        Browser admin = browser();
        admin.login(ProbeAccounts.ADMIN, PASSWORD);
        lastUsed(admin, Duration.ofMinutes(10).plusSeconds(5));
        assertThat(rows(admin)).isEqualTo(1);
        assertThat(admin.get("/probe/me").code()).isEqualTo("unauthenticated");
        Browser.Answer late = admin.post("/probe/mfa/verify", "{\"code\":\"" + adminCode() + "\"}");
        assertThat(late.code()).isEqualTo("csrf_invalid");
    }

    @Test
    void probe2_allSessionsOfAnAccountCanBeFoundAndDeleted() {
        Browser first = signedInDonor();
        Browser second = signedInDonor();
        Browser other = signedInAdmin();
        Map<String, ? extends Session> found = sessions.findByPrincipalName(ProbeAccounts.DONOR);
        assertThat(found).hasSize(2);
        found.keySet().forEach(sessions::deleteById);

        assertThat(first.get("/probe/me").code()).isEqualTo("unauthenticated");
        assertThat(second.post("/probe/change-password", "{}").code()).isEqualTo("csrf_invalid");
        assertThat(other.get("/probe/me").status()).as("another account is untouched").isEqualTo(200);
    }
}
