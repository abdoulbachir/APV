package org.apvgakasinay.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseTargetTest {

    private static final String LOCAL = DatabaseTarget.LOCAL_HOSTS;

    @Test
    void localDatabasesAreAllowed() {
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://localhost:5432/apv", LOCAL)).isTrue();
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://127.0.0.1/apv", LOCAL)).isTrue();
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://LOCALHOST:55432/test?loggerLevel=OFF", LOCAL)).isTrue();
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://[::1]:5432/apv", LOCAL)).isTrue();
    }

    @Test
    void anythingElseIsRefused() {
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://db.example.org:5432/apv", LOCAL)).isFalse();
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://10.0.0.5/apv", LOCAL)).isFalse();
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://localhost.example.org/apv", LOCAL)).isFalse();
        // Several hosts, no host, another driver, nothing at all: not recognizably local, so refused.
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://localhost:5432,db.example.org:5432/apv", LOCAL)).isFalse();
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql:apv", LOCAL)).isFalse();
        assertThat(DatabaseTarget.isAllowed("jdbc:h2:mem:apv", LOCAL)).isFalse();
        assertThat(DatabaseTarget.isAllowed(null, LOCAL)).isFalse();
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://localhost/apv", "")).isFalse();
    }

    @Test
    void aContainerHostCanBeListedExplicitly() {
        assertThat(DatabaseTarget.isAllowed("jdbc:postgresql://postgres:5432/apv", LOCAL + ", postgres")).isTrue();
        assertThat(DatabaseTarget.host("jdbc:postgresql://Postgres:5432/apv")).isEqualTo("postgres");
        assertThat(DatabaseTarget.host("not a url")).isEqualTo("unknown");
    }
}
