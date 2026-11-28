package org.apvgakasinay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apvgakasinay.fixtures.FixtureImporter;
import org.apvgakasinay.shared.ProfileGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The application as it starts with no development profile: against an empty database,
 * migrated by Flyway, with nothing able to load fictional data.
 */
@SpringBootTest
class ProductionProfileIT {

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
        // Outside dev and test the keys must be supplied: these are throwaway test values.
        registry.add("apv.identity.mfa-encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("apv.identity.hash-key", () -> "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        // Outside dev and test a comprehensive local breached-password list is required too. This
        // one meets the real requirements (see TestBlocklist): there is no test-only way around them.
        registry.add("apv.identity.password-blocklist-path", () -> org.apvgakasinay.support.TestBlocklist.production().toString());
    }

    /** Starts the whole application again, as production, against the same database, with other list settings. */
    private static Throwable startUpFailure(String... properties) {
        java.util.List<String> all = new java.util.ArrayList<>(java.util.List.of(
                "server.port=0",
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "apv.identity.mfa-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "apv.identity.hash-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="));
        all.addAll(java.util.List.of(properties));
        try (org.springframework.context.ConfigurableApplicationContext started =
                     new org.springframework.boot.builder.SpringApplicationBuilder(ApvApplication.class)
                             .bannerMode(org.springframework.boot.Banner.Mode.OFF).logStartupInfo(false)
                             // As command-line arguments, which take precedence over application.yaml the way environment variables do.
                             .run(all.stream().map(property -> "--" + property).toArray(String[]::new))) {
            return null;
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return cause;
        }
    }

    @Test
    void a2_9_productionDoesNotStartWithoutASuitableBreachedPasswordList(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        // Not configured. The bundled starter list alone is not enough.
        assertThat(startUpFailure()).isInstanceOf(IllegalStateException.class).hasMessageContaining("APV_PASSWORD_BLOCKLIST_PATH is not set");
        // Configured, but nothing there.
        assertThat(startUpFailure("apv.identity.password-blocklist-path=" + folder.resolve("absent.bin")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not exist or cannot be read");
        // Well-formed, holding the known passwords, far too small to be comprehensive.
        java.nio.file.Path small = folder.resolve("small.bin");
        org.apvgakasinay.support.TestBlocklist.write(small, 50_000, java.util.List.of("password", "123456", "qwerty", "iloveyou"), "apv-sha1-sorted-v1");
        assertThat(startUpFailure("apv.identity.password-blocklist-path=" + small))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 100000000 are required");
        // A text file of passwords, such as the starter list, is not a list in the required format.
        java.nio.file.Path text = folder.resolve("starter.txt");
        java.nio.file.Files.writeString(text, "passwordpassword\n123456789012345\n");
        assertThat(startUpFailure("apv.identity.password-blocklist-path=" + text)).isInstanceOf(IllegalStateException.class).hasMessageContaining("manifest");
        // The development profile is not a way around it in production: the two cannot be combined (see below),
        // and the test profile was not used to start this class.
        assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
    }

    @Test
    void a2_9_withASuitableListProductionStartsAndRefusesAListedPassword() {
        org.apvgakasinay.identity.internal.BreachedPasswords list = context.getBean(org.apvgakasinay.identity.internal.BreachedPasswords.class);
        assertThat(list.present()).isTrue();
        assertThat(list.entries()).isGreaterThanOrEqualTo(100_000_000L);
        org.apvgakasinay.identity.internal.PasswordPolicy policy = context.getBean(org.apvgakasinay.identity.internal.PasswordPolicy.class);
        assertThatThrownBy(() -> policy.accept("newPassword", org.apvgakasinay.support.TestBlocklist.BREACHED_PASSPHRASE, java.util.List.of()))
                .isInstanceOfSatisfying(org.apvgakasinay.shared.ApiException.class, refused -> assertThat(refused.fieldCode()).isEqualTo("compromised"));
        assertThat(policy.accept("newPassword", "a long harmless passphrase 2026", java.util.List.of())).isNotBlank();
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    private org.apvgakasinay.site.SiteService site;

    @Test
    void noFixtureLoaderAndNoDevelopmentPictureRouteExist() {
        assertThat(context.getBeanNamesForType(FixtureImporter.class)).isEmpty();
        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("fixture"));
        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("devmedia"));
    }

    @Test
    void aFreshDatabaseHoldsNoFictionalDataOnlyTheSeededSettings() {
        // A real database holds the general fund (donation contract 10, migration V6), empty, and
        // nothing else: no individual case, no money.
        assertThat(jdbc.sql("select count(*) from aid_case where kind <> 'fund'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select code, kind, status from aid_case where kind = 'fund'")
                .query((rs, n) -> rs.getString("code") + "/" + rs.getString("kind") + "/" + rs.getString("status")).list())
                .containsExactly("FUND/fund/active");
        assertThat(jdbc.sql("select count(*) from ledger_entry").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select version from flyway_schema_history order by installed_rank desc limit 1").query(String.class).single())
                .isEqualTo("7");
        assertThat(jdbc.sql("select data_origin from organization_settings").query(String.class).single()).isEqualTo("real");
        assertThat(jdbc.sql("select version()").query(String.class).single()).startsWith("PostgreSQL 18.");
        // The six approved categories and the default timezone come from the migration.
        assertThat(site.site().timezone()).isEqualTo("America/Chicago");
        assertThat(site.site().categories()).extracting(c -> c.id())
                .containsExactly("education", "medical", "housing", "family", "emergency", "other");
        assertThat(site.stats().received()).isZero();
        assertThat(site.stats().peopleHelped()).isZero();
    }

    /**
     * Scenarios 10.1, 10.2, 10.3, 10.5 and 10.7 of contract/tests/donation-acceptance.md: a real
     * database, never marked disposable, holds the general fund after the migrations, with the
     * text the owner approved, and it is read through the public API like any case.
     */
    @Test
    void d10_theGeneralFundExistsOnARealDatabaseWithTheApprovedTextAndIsNeverRemoved() {
        org.apvgakasinay.cases.CaseReadService cases = context.getBean(org.apvgakasinay.cases.CaseReadService.class);
        // Matched case-insensitively, like every case: GET /api/cases/FUND answers 200 where it answered 404 before.
        var english = cases.detail("fund", org.apvgakasinay.shared.Language.EN);
        assertThat(english.caseView().code()).isEqualTo("FUND");
        assertThat(english.caseView().kind()).isEqualTo("fund");
        assertThat(english.caseView().status()).isEqualTo("active");
        assertThat(english.caseView().acceptingDonations()).isTrue();
        assertThat(english.caseView().goal()).isNull();
        assertThat(english.caseView().deadline()).isNull();
        assertThat(english.caseView().category()).isNull();
        assertThat(english.caseView().photoUrl()).isNull();
        assertThat(english.caseView().createdAt()).isNotBlank();
        // Zero totals: no ledger entry, nothing received, nothing handed over.
        assertThat(english.caseView().received()).isZero();
        assertThat(english.caseView().delivered()).isZero();
        assertThat(english.financials().externalDonations()).isZero();
        assertThat(english.financials().available()).isZero();
        assertThat(english.counts().ledger()).isZero();
        assertThat(english.counts().donors()).isZero();
        assertThat(english.ledger()).isEmpty();

        // The approved text, exactly as written (decision 7).
        assertThat(english.caseView().title()).isEqualTo("General fund");
        assertThat(english.caseView().story()).isEqualTo("Gifts to the general fund are not tied to one case. They are kept until APV-GAKASINAY "
                + "allocates them to a case: for an urgent need, or to complete a case that has not reached its goal. Every allocation appears in "
                + "the record of this fund and in the record of the case that receives it. The fund never pays a beneficiary directly.");
        var french = cases.detail("FUND", org.apvgakasinay.shared.Language.FR);
        assertThat(french.caseView().title()).isEqualTo("Fonds général");
        assertThat(french.caseView().storyLanguage()).isEqualTo("fr");
        assertThat(french.caseView().story()).isEqualTo("Les dons au fonds général ne sont pas liés à un cas précis. Ils sont conservés jusqu’à ce "
                + "qu’APV-GAKASINAY les affecte à un cas : pour un besoin urgent, ou pour compléter un cas qui n’a pas atteint son objectif. "
                + "Chaque affectation apparaît dans l’historique de ce fonds et dans celui du cas qui la reçoit. Le fonds ne verse jamais "
                + "d’argent directement à un bénéficiaire.");
        assertThat(site.site().fundCode()).isEqualTo("FUND");

        // The migration's statement run again on a database that has the fund inserts nothing, and there can only be one.
        int again = jdbc.sql("""
                insert into aid_case (code, kind, status, accepts_donations, public_name, title_en, title_fr, story_en, story_fr, published_at)
                select 'FUND', 'fund', 'active', true, '', 'General fund', 'Fonds général', 'x', 'y', date_trunc('second', now())
                where not exists (select 1 from aid_case where kind = 'fund')
                """).update();
        assertThat(again).isZero();
        assertThat(jdbc.sql("select count(*) from aid_case where kind = 'fund'").query(Long.class).single()).isEqualTo(1);

        // Never deleted, and its code and kind never change.
        assertThatThrownBy(() -> jdbc.sql("delete from aid_case where kind = 'fund'").update()).hasMessageContaining("never deleted");
        assertThatThrownBy(() -> jdbc.sql("update aid_case set code = 'FUNDS' where kind = 'fund'").update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("update aid_case set kind = 'case', category_id = 'other' where code = 'FUND'").update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // It never pays a beneficiary directly (financial-terms, section 11).
        assertThatThrownBy(() -> jdbc.sql("""
                insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at)
                values ('X1', (select id from aid_case where code = 'FUND'), 'disbursement', 100, date_trunc('second', now()))
                """).update()).hasMessageContaining("does not pay beneficiaries directly");
        // Giving is not open until a payment method is active: none exists on a new database.
        assertThat(jdbc.sql("select count(*) from payment_method").query(Long.class).single()).isZero();
    }

    @Test
    void aNewCategoryNeedsOnlyARow() {
        jdbc.sql("insert into category (id, label_en, label_fr, position) values ('food', 'Food', 'Alimentation', 7)").update();
        assertThat(site.site().categories()).extracting(c -> c.id()).contains("food");
        jdbc.sql("delete from category where id = 'food'").update();
    }

    @Test
    void productionCannotBeCombinedWithADevelopmentProfile() {
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("prod", "dev");
        assertThatThrownBy(() -> new ProfileGuard(mixed)).isInstanceOf(IllegalStateException.class);
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        new ProfileGuard(production);
    }
}
