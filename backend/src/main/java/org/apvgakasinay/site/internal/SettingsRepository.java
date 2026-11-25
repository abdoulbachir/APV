package org.apvgakasinay.site.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {

    public record Settings(String timezone, String defaultLocale) {
    }

    private final JdbcClient jdbc;

    public SettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Settings current() {
        return jdbc.sql("select timezone, default_locale from organization_settings")
                .query((rs, row) -> new Settings(rs.getString("timezone"), rs.getString("default_locale")))
                .single();
    }
}
