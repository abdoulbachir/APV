package org.apvgakasinay.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apvgakasinay.fixtures.FixtureException;
import org.apvgakasinay.fixtures.FixtureImporter;
import org.apvgakasinay.identity.DatabasePurpose;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.Repo;
import org.apvgakasinay.support.TestMedia;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Scenarios 13.3 and 13.7: the development tools pointed at a database nobody marked
 * disposable. The test profile makes the fixture loader exist; the database is empty, local
 * as far as the loader can tell, and has never held real data. It is still refused.
 */
@SpringBootTest
@ActiveProfiles("test")
class DevelopmentDataIsolationIT {

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
        registry.add("apv.fixtures.allowed-hosts", () -> "localhost,127.0.0.1,[::1]," + POSTGRES.getHost());
        registry.add("apv.media.dev-root", () -> TestMedia.root().toString());
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FixtureImporter importer;

    @Autowired
    DatabasePurpose purpose;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordHasher hasher;

    @Autowired
    Keys keys;

    @Autowired
    IdentityClock clock;

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void a13_3_theFixtureLoaderRefusesAnEmptyDatabaseThatWasNotMarkedDisposable() {
        assertThat(purpose.disposable()).isFalse();
        // The only case on a fresh real database is the seeded general fund (migration V6).
        assertThat(count("select count(*) from aid_case where kind <> 'fund'")).isZero();
        assertThatThrownBy(() -> importer.replaceAllData(Json.readMap(Repo.contract("fixtures/example.json"))))
                .isInstanceOf(FixtureException.class).hasMessageContaining("disposable");
        assertThat(count("select count(*) from aid_case where kind <> 'fund'")).isZero();
        assertThat(count("select count(*) from ledger_entry")).isZero();
    }

    @Test
    void a13_7_theDevelopmentSeedRefusesItTooAndRefusesADatabaseElsewhere() {
        String local = "localhost,127.0.0.1,[::1]," + POSTGRES.getHost();
        // On this machine, but not marked disposable.
        new DevAccountSeeder(accounts, hasher, keys, purpose, clock, POSTGRES.getJdbcUrl(), local).run(null);
        assertThat(count("select count(*) from account")).isZero();

        // Marked disposable, but addressed as a database that is not on this machine.
        jdbc.sql("create schema apv_disposable_database").update();
        try {
            assertThat(purpose.disposable()).isTrue();
            new DevAccountSeeder(accounts, hasher, keys, purpose, clock, "jdbc:postgresql://db.example.org:5432/apv", local).run(null);
            assertThat(count("select count(*) from account")).isZero();
            // Both conditions met: it runs, and what it creates is recognisably fictional.
            new DevAccountSeeder(accounts, hasher, keys, purpose, clock, POSTGRES.getJdbcUrl(), local).run(null);
            assertThat(count("select count(*) from account where email like '%@example.test'")).isEqualTo(3);
            assertThat(count("select count(*) from account")).isEqualTo(3);
        } finally {
            jdbc.sql("drop schema apv_disposable_database").update();
        }
    }
}
