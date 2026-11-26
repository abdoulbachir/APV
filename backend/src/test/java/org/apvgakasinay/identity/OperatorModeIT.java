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
@org.junit.jupiter.api.extension.ExtendWith(org.apvgakasinay.support.RestoreLogLevels.class)
class OperatorModeIT {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("apv.postgres.image", "postgres:18.7"));

    static {
        POSTGRES.start();
    }

    private static String run(String command, String typed) throws Exception {
        return run(command, typed, org.apvgakasinay.support.TestBlocklist.production().toString());
    }

    private static String run(String command, String typed, String blocklist) throws Exception {
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
            if (blocklist != null) {
                set(names, "apv.identity.password-blocklist-path", blocklist);
            }
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

    /** Scenario 2.9 for the operator command: the same configuration rules as the server, and nothing is done without them. */
    @Test
    void a2_9_theOperatorCommandRefusesToRunWithoutASuitableBreachedPasswordList(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        String missing = run("create-admin", "sample.admin@example.org\nSample Admin\n", null);
        assertThat(missing).startsWith("3\n").contains("Refused to start").contains("APV_PASSWORD_BLOCKLIST_PATH is not set");

        java.nio.file.Path small = folder.resolve("small.bin");
        org.apvgakasinay.support.TestBlocklist.write(small, 50_000, java.util.List.of("password", "123456", "qwerty", "iloveyou"), "apv-sha1-sorted-v1");
        String unsuitable = run("create-admin", "sample.admin@example.org\nSample Admin\n", small.toString());
        assertThat(unsuitable).startsWith("3\n").contains("Refused to start").contains("at least 100000000 are required");

        String absent = run("list-staff", "", folder.resolve("absent.bin").toString());
        assertThat(absent).startsWith("3\n").contains("does not exist or cannot be read");
        // Nothing was created by the refused runs, and no credential was printed.
        assertThat(missing + unsuitable + absent).doesNotContain("credential (shown once");
        assertThat(run("list-staff", "")).doesNotContain("sample.admin@example.org");
    }
}
