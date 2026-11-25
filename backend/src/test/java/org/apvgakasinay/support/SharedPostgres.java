package org.apvgakasinay.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One real PostgreSQL, started once by Testcontainers and shared by the integration tests. */
public final class SharedPostgres {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("apv.postgres.image", "postgres:18.7"));

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
    }
}
