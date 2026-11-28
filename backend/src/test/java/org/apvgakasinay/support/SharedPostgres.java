package org.apvgakasinay.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One real PostgreSQL, started once by Testcontainers and shared by the integration tests. */
public final class SharedPostgres {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("apv.postgres.image", "postgres:18.7"))
                    // The test database is explicitly marked disposable when it is created.
                    .withInitScript("disposable-database.sql");

    static {
        POSTGRES.start();
    }

    private SharedPostgres() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The container is disposable, so the destructive fixture loader may use it wherever it runs.
        registry.add("apv.fixtures.allowed-hosts", () -> "localhost,127.0.0.1,[::1]," + POSTGRES.getHost());
        registry.add("apv.media.dev-root", () -> TestMedia.root().toString());
        // Screenshots of test gifts go to a directory of their own, and the daily retention task
        // never runs by itself during the tests: the tests that cover it call it.
        registry.add("apv.donations.evidence-root", () -> EVIDENCE.toString());
        registry.add("apv.donations.retention-initial-delay", () -> "PT24H");
    }

    private static final java.nio.file.Path EVIDENCE = evidenceDirectory();

    /** The private directory the application stores test screenshots in. */
    public static java.nio.file.Path evidenceRoot() {
        return EVIDENCE;
    }

    private static java.nio.file.Path evidenceDirectory() {
        try {
            return java.nio.file.Files.createTempDirectory("apv-test-evidence");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
