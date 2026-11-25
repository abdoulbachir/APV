package org.apvgakasinay.fixtures;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides whether a database address is one the destructive fixture loader may touch. */
final class DatabaseTarget {

    static final String LOCAL_HOSTS = "localhost,127.0.0.1,[::1]";

    private static final Pattern POSTGRES_URL = Pattern.compile("^jdbc:postgresql://(\\[[^\\]]+\\]|[^:/?,]+)(:\\d+)?/.*$");

    private DatabaseTarget() {
    }

    /** The host of a single-host PostgreSQL JDBC URL, or "unknown" for anything else. */
    static String host(String jdbcUrl) {
        Matcher matcher = POSTGRES_URL.matcher(jdbcUrl == null ? "" : jdbcUrl);
        return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : "unknown";
    }

    /** True only when the URL names exactly one host and that host is in the comma-separated list. */
    static boolean isAllowed(String jdbcUrl, String allowedHosts) {
        String host = host(jdbcUrl);
        return Arrays.stream(allowedHosts.split(","))
                .map(allowed -> allowed.trim().toLowerCase(Locale.ROOT))
                .anyMatch(allowed -> !allowed.isEmpty() && allowed.equals(host));
    }
}
