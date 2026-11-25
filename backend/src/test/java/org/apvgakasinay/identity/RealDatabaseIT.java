package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apvgakasinay.identity.internal.OperatorCommands;
import org.apvgakasinay.identity.internal.Totp;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The application as it runs for real: no development profile, and a database that was never
 * marked disposable. The operator creates the first super administrator, who then activates,
 * enrolls and signs in over HTTP with the production cookie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealDatabaseIT {

    private static final String PASSWORD = "a long harmless passphrase 2026";

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
        registry.add("apv.identity.mfa-encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("apv.identity.hash-key", () -> "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        registry.add("apv.identity.password-blocklist-path", () -> org.apvgakasinay.support.TestBlocklist.production().toString());
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OperatorCommands operator;

    @Autowired
    DatabasePurpose purpose;

    @Autowired
    ApplicationContext context;

    private Browser browser() {
        Browser browser = new Browser(port);
        browser.cookieName = "__Host-apv_session";
        return browser;
    }

    private String run(String command, String input, int expectedExit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = operator.run(command, new BufferedReader(new StringReader(input)), new PrintStream(out, true, StandardCharsets.UTF_8));
        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(exit).as(printed).isEqualTo(expectedExit);
        return printed;
    }

    private static String credentialIn(String printed) {
        Matcher matcher = Pattern.compile("(?m)^\\s{4}([A-Za-z0-9_-]{43})\\s*$").matcher(printed);
        assertThat(matcher.find()).as(printed).isTrue();
        return matcher.group(1);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    @Order(1)
    void a13_3_and_13_7_anUnmarkedDatabaseTakesNoFictionalDataAndNoDevelopmentAccount() {
        // Empty, never used by the operator, and still not disposable: nothing marked it so.
        assertThat(count("select count(*) from account")).isZero();
        assertThat(jdbc.sql("select database_real from identity_guard").query(Boolean.class).single()).isFalse();
        assertThat(purpose.disposable()).isFalse();
        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("devaccountseeder"));
        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("fixture"));
    }

    @Test
    @Order(2)
    void a1_10_a3_9_productionHasClosedSignUpAndTheSecureHostCookie() {
        Browser browser = browser();
        Browser.Answer options = browser.get("/api/auth/options");
        assertThat(options.json().get("registrationOpen").asBoolean()).isFalse();
        Browser.Answer csrf = browser.get("/api/auth/csrf");
        List<String> cookies = csrf.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0)).startsWith("__Host-apv_session=").contains("Secure").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/")
                .doesNotContainIgnoringCase("Domain=").doesNotContainIgnoringCase("Max-Age").doesNotContainIgnoringCase("Expires");
        browser.token = csrf.json().get("token").asText();
        Browser.Answer register = browser.post("/api/auth/register",
                "{\"email\":\"sample.donor@example.org\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Sample\"}");
        assertThat(register.status()).isEqualTo(403);
        assertThat(register.code()).isEqualTo("registration_closed");
    }

    @Test
    @Order(3)
    void a7_2_a7_3_a7_19_theOperatorCreatesTheFirstSuperAdministratorWhoThenSignsIn() throws Exception {
        // 7.19: the command takes its name only; everything else is asked for on the terminal.
        String printed = run("create-super-admin", "sample.owner@example.org\nSample Owner\n", 0);
        String credential = credentialIn(printed);
        assertThat(printed).contains("shown once").contains("60 minutes");
        assertThat(jdbc.sql("select database_real from identity_guard").query(Boolean.class).single()).isTrue();
        assertThat(count("select count(*) from account where role = 'super_admin' and not activated")).isEqualTo(1);
        assertThat(count("select count(*) from account_password")).isZero();
        assertThat(jdbc.sql("select bootstrapped from identity_guard").query(Boolean.class).single()).isFalse();

        Browser browser = browser();
        browser.csrf();
        // 2.9, over HTTP in production: a password that is in the local breached list is refused, and the credential is not spent on it.
        Browser.Answer breached = browser.post("/api/auth/set-password",
                "{\"credential\":\"" + credential + "\",\"newPassword\":\"" + org.apvgakasinay.support.TestBlocklist.BREACHED_PASSPHRASE + "\"}");
        assertThat(breached.status()).isEqualTo(400);
        assertThat(breached.fieldCode()).isEqualTo("compromised");
        assertThat(count("select count(*) from account_password")).isZero();
        assertThat(browser.post("/api/auth/set-password", "{\"credential\":\"" + credential + "\",\"newPassword\":\"" + PASSWORD + "\"}").status()).isEqualTo(204);
        Browser.Answer login = browser.login("sample.owner@example.org", PASSWORD);
        assertThat(login.json().get("status").asText()).as(login.body()).isEqualTo("mfa_enrollment_required");
        byte[] secret = IdentityIntegration.base32Decode(browser.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText());
        Browser.Answer confirmed = browser.post("/api/auth/mfa/confirm", "{\"code\":\"" + Totp.code(secret, Totp.step(Instant.now())) + "\"}");
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        Browser.Answer me = browser.get("/api/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("role").asText()).isEqualTo("super_admin");
        assertThat(jdbc.sql("select bootstrapped from identity_guard").query(Boolean.class).single()).isTrue();

        // The operator's other commands, on the same real database.
        assertThat(run("list-staff", "", 0)).contains("sample.owner@example.org").contains("second-factor=true");
        assertThat(run("disable-account", "sample.owner@example.org\n", 1)).contains("no usable super administrator");
        assertThat(run("recover-staff-access", "sample.owner@example.org\nmet in person\n", 1)).contains("no usable super administrator");
        assertThat(browser.get("/api/me").status()).as("refused commands changed nothing").isEqualTo(200);
        String admin = run("create-admin", "sample.admin@example.org\nSample Admin\n", 0);
        assertThat(credentialIn(admin)).isNotEqualTo(credential);
        assertThat(run("set-can-disburse", "sample.admin@example.org\nyes\n", 0)).contains("Done");
        // 7.20: a recovery with no identity check recorded is refused.
        assertThat(run("issue-password-reset", "sample.admin@example.org\n\n", 1)).contains("nothing was entered");
        String reset = run("issue-password-reset", "sample.admin@example.org\ncalled the number already on file\n", 0);
        assertThat(reset).contains("already had before this request").contains("30 minutes");
        assertThat(jdbc.sql("select details ->> 'identity_check' from audit_event where event_type = 'credential.issued'").query(String.class).single())
                .isEqualTo("called the number already on file");
        assertThat(run("no-such-command", "", 2)).contains("Unknown command");
        // No credential shown by the operator command is in the audit log.
        String audit = jdbc.sql("select string_agg(row_to_json(a)::text, ' ') from audit_event a").query(String.class).single();
        assertThat(audit).doesNotContain(credential).doesNotContain(credentialIn(admin)).doesNotContain(credentialIn(reset));
    }

    @Test
    @Order(4)
    void a13_6_aRealDatabaseCanNeverBecomeDisposable() {
        assertThat(jdbc.sql("select database_real from identity_guard").query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> jdbc.sql("update identity_guard set database_real = false").update()).isInstanceOf(DataAccessException.class);
        // Even with the marker added afterwards, it is not disposable.
        jdbc.sql("create schema apv_disposable_database").update();
        try {
            assertThat(purpose.disposable()).isFalse();
        } finally {
            jdbc.sql("drop schema apv_disposable_database").update();
        }
        assertThat(count("select count(*) from account")).isEqualTo(2);
    }

    /** D5 of the independent review, before the operator command was ever used: with no disposable marker, nothing is truncated. */
    @Test
    @Order(0)
    void a12_2_anUnmarkedDatabaseRefusesTruncateFromTheStart() {
        assertThat(jdbc.sql("select database_real from identity_guard").query(Boolean.class).single()).isFalse();
        for (String statement : List.of("truncate table audit_event", "truncate table ledger_entry cascade", "truncate table identity_guard",
                "truncate table account cascade")) {
            assertThatThrownBy(() -> jdbc.sql(statement).update()).as(statement)
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("never truncated on a real database");
        }
        assertThat(count("select count(*) from identity_guard")).isEqualTo(1);
    }

    /**
     * D5 of the independent review, as it was done there: on the database the operator has
     * used, with the application's own role, the audit log could be emptied with TRUNCATE
     * although update and delete were refused. Every table protected by a row trigger, the
     * ledger included, with and without the marker added afterwards.
     */
    @Test
    @Order(5)
    void a12_2_theAuditLogTheLedgerAndTheAccountsCannotBeTruncatedOnARealDatabase() {
        assertThat(jdbc.sql("select database_real from identity_guard").query(Boolean.class).single()).isTrue();
        long audit = count("select count(*) from audit_event");
        long accounts = count("select count(*) from account");
        long factors = count("select count(*) from account_totp");
        assertThat(audit).isPositive();
        List<String> statements = List.of(
                "truncate audit_event",
                "truncate table audit_event restart identity cascade",
                "truncate table ledger_entry cascade",
                "truncate table donation_listing, ledger_entry",
                "truncate table aid_case cascade",
                "truncate table ledger_platform_total",
                "truncate table identity_guard",
                "truncate table account cascade",
                "truncate table account_totp",
                "truncate table account_password, account_totp, account_recovery_code",
                "truncate table spring_session_retired",
                "truncate table spring_session cascade",
                "truncate table auth_throttle, audit_event");
        for (String statement : statements) {
            assertThatThrownBy(() -> jdbc.sql(statement).update()).as(statement)
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("never truncated on a real database");
        }
        // Marking it disposable afterwards opens nothing.
        jdbc.sql("create schema apv_disposable_database").update();
        try {
            for (String statement : statements) {
                assertThatThrownBy(() -> jdbc.sql(statement).update()).as("with the marker added: " + statement)
                        .isInstanceOf(DataAccessException.class).hasMessageContaining("never truncated on a real database");
            }
        } finally {
            jdbc.sql("drop schema apv_disposable_database").update();
        }
        assertThat(count("select count(*) from audit_event")).isEqualTo(audit);
        assertThat(count("select count(*) from account")).isEqualTo(accounts);
        assertThat(count("select count(*) from account_totp")).isEqualTo(factors);
        assertThat(jdbc.sql("select gross_cents from ledger_platform_total").query(java.math.BigDecimal.class).single()).isNotNull();
        // The row rules still hold beside it.
        assertThatThrownBy(() -> jdbc.sql("delete from audit_event").update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("update audit_event set outcome = 'success'").update()).isInstanceOf(DataAccessException.class);
    }
}
