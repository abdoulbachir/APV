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
                .isEqualTo("1");
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
