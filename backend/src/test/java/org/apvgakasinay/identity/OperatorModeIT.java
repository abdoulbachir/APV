package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The operator command started the way an operator starts it: its own small application with
 * no web server, reading the terminal. Run against a database that was never marked disposable.
 */
class OperatorModeIT {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("apv.postgres.image", "postgres:18.7"));

    static {
        POSTGRES.start();
    }

    private static String run(String command, String typed) throws Exception {
        List<String> names = new ArrayList<>();
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        try {
            set(names, "spring.datasource.url", POSTGRES.getJdbcUrl());
            set(names, "spring.datasource.username", POSTGRES.getUsername());
            set(names, "spring.datasource.password", POSTGRES.getPassword());
            set(names, "apv.identity.mfa-encryption-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            set(names, "apv.identity.hash-key", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
            set(names, "logging.level.root", "WARN");
            System.setIn(new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
            int exit = OperatorMode.run(command);
            return exit + "\n" + printed.toString(StandardCharsets.UTF_8);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            names.forEach(System::clearProperty);
        }
    }

    private static void set(List<String> names, String name, String value) {
        System.setProperty(name, value);
        names.add(name);
    }

    @Test
    void a7_2_theOperatorCommandRunsWithoutAWebServerAndMigratesAnEmptyDatabase() throws Exception {
        String created = run("create-super-admin", "sample.owner@example.org\nSample Owner\n");
        assertThat(created).startsWith("0\n").contains("Activation credential (shown once, valid 60 minutes, works once)");
        assertThat(created).containsPattern("(?m)^\\s{4}[A-Za-z0-9_-]{43}\\s*$");

        String listed = run("list-staff", "");
        assertThat(listed).startsWith("0\n").contains("sample.owner@example.org  super_admin  enabled=true  activated=false  second-factor=false");

        // The same address again is refused, with a message and exit code 1.
        String again = run("create-super-admin", "Sample.Owner@example.org\nSample Owner\n");
        assertThat(again).startsWith("1\n");
        assertThat(again).doesNotContainPattern("(?m)^\\s{4}[A-Za-z0-9_-]{43}\\s*$");
    }
}
