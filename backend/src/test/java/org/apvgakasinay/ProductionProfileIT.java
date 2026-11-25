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
        assertThat(jdbc.sql("select count(*) from aid_case").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from ledger_entry").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select version from flyway_schema_history order by installed_rank desc limit 1").query(String.class).single())
                .isEqualTo("5");
        assertThat(jdbc.sql("select data_origin from organization_settings").query(String.class).single()).isEqualTo("real");
        assertThat(jdbc.sql("select version()").query(String.class).single()).startsWith("PostgreSQL 18.");
        // The six approved categories and the default timezone come from the migration.
        assertThat(site.site().timezone()).isEqualTo("America/Chicago");
        assertThat(site.site().categories()).extracting(c -> c.id())
                .containsExactly("education", "medical", "housing", "family", "emergency", "other");
        assertThat(site.stats().received()).isZero();
        assertThat(site.stats().peopleHelped()).isZero();
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
