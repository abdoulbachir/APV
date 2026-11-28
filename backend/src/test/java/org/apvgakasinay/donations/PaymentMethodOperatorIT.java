package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.apvgakasinay.identity.OperatorMode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Scenarios 1.8 to 1.10: the payment-method commands started the way an operator starts them,
 * as their own small application with no web server, reading the terminal, under production
 * rules, against a database that was never marked disposable.
 */
@org.junit.jupiter.api.extension.ExtendWith(org.apvgakasinay.support.RestoreLogLevels.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentMethodOperatorIT {

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
            set(names, "apv.identity.password-blocklist-path", org.apvgakasinay.support.TestBlocklist.production().toString());
            set(names, "logging.level.root", "WARN");
            System.setIn(new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
            // Exactly what the application's entry point does for "operator <command>".
            int exit = OperatorMode.run(command, DonationOperatorConfiguration.class);
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

    private static String one(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    @Test
    @Order(1)
    void d1_10_aMethodIsCreatedInactiveAndItsDetailsArePublishedFromAFileAfterTypingTheIdentifierAgain(@TempDir Path folder) throws Exception {
        assertThat(run("payment-method-list", "")).startsWith("0\n").contains("No payment method exists");

        String created = run("payment-method-create", "zelle\nZelle\nZelle\n1\n");
        assertThat(created).startsWith("0\n").contains("Created, inactive");
        assertThat(one("select active::text from payment_method where id = 'zelle'")).isEqualTo("false");

        Path definition = folder.resolve("zelle.json");
        Files.writeString(definition, """
                {"instructions": {"en": "Send your gift with Zelle, then report it here.", "fr": "Envoyez votre don avec Zelle, puis déclarez-le ici."},
                 "recipient": [{"labels": {"en": "Send to", "fr": "Envoyer à"}, "value": "gifts@example.test", "format": "text"}]}
                """);
        // The identifier typed again does not match: nothing is published.
        String mistyped = run("payment-method-publish-details", "zelle\n" + definition + "\nzele\n");
        assertThat(mistyped).startsWith("1\n").contains("NEW VERSION").contains("gifts@example.test").contains("nothing was published");
        assertThat(one("select count(*)::text from payment_method_details")).isEqualTo("0");

        String published = run("payment-method-publish-details", "zelle\n" + definition + "\nzelle\n");
        assertThat(published).startsWith("0\n").contains("CURRENT VERSION: none").contains("Published as revision 1");
        assertThat(one("select recipient -> 0 ->> 'value' from payment_method_details where method_id = 'zelle' and superseded_at is null"))
                .isEqualTo("gifts@example.test");

        // A second version is shown next to the current one before it replaces it.
        Files.writeString(definition, """
                {"instructions": {"en": "Send your gift with Zelle, then report it here.", "fr": "Envoyez votre don avec Zelle, puis déclarez-le ici."},
                 "recipient": [{"labels": {"en": "Send to", "fr": "Envoyer à"}, "value": "new-gifts@example.test", "format": "text"}]}
                """);
        String second = run("payment-method-publish-details", "zelle\n" + definition + "\nzelle\n");
        assertThat(second).startsWith("0\n").contains("CURRENT VERSION (revision 1)").contains("gifts@example.test").contains("new-gifts@example.test")
                .contains("Published as revision 2");
        assertThat(one("select count(*)::text from payment_method_details where method_id = 'zelle' and superseded_at is null")).isEqualTo("1");

        // A definition that is not what the contract allows is refused, and says why.
        Files.writeString(definition, "{\"instructions\": {\"en\": \"x\", \"fr\": \"y\"}, \"recipient\": [], \"active\": true}");
        assertThat(run("payment-method-publish-details", "zelle\n" + definition + "\nzelle\n")).startsWith("1\n").contains("Refused");
        assertThat(run("payment-method-publish-details", "zelle\n" + folder.resolve("absent.json") + "\nzelle\n")).startsWith("1\n").contains("cannot be read");
        assertThat(run("payment-method-publish-details", "nothing\n")).startsWith("1\n").contains("there is no payment method");
    }

    @Test
    @Order(2)
    void d1_8_inProductionAMethodCannotBeActivatedWithoutTheEligibilityAttestation() throws Exception {
        // The operator gives the identifier and nothing else.
        String refused = run("payment-method-activate", "zelle\n");
        assertThat(refused).startsWith("1\n").contains("eligibility").contains("Refused");
        assertThat(one("select active::text from payment_method where id = 'zelle'")).isEqualTo("false");
        // Part of the attestation is not the attestation.
        assertThat(run("payment-method-activate", "zelle\nSample Owner\n2026-11-20\n")).startsWith("1\n");
        assertThat(run("payment-method-activate", "zelle\nSample Owner\nyesterday\nTerms read\n")).startsWith("1\n").contains("not a date");
        assertThat(one("select active::text from payment_method where id = 'zelle'")).isEqualTo("false");
        assertThat(one("select count(*)::text from audit_event where event_type = 'payment_method.activated'")).isEqualTo("0");
    }

    @Test
    @Order(3)
    void d1_9_withTheAttestationTheMethodIsActiveAndTheAttestationIsStoredAndAudited() throws Exception {
        String activated = run("payment-method-activate", "zelle\nSample Owner\n2026-11-20\nRead the provider's terms for personal accounts and its limits for the United States.\n");
        assertThat(activated).startsWith("0\n").contains("Active");
        assertThat(one("select active::text from payment_method where id = 'zelle'")).isEqualTo("true");
        assertThat(one("select eligibility_checked_by || '|' || eligibility_checked_on from payment_method where id = 'zelle'"))
                .isEqualTo("Sample Owner|2026-11-20");
        assertThat(one("""
                select details ->> 'eligibility_checked_by' || '|' || (details ->> 'eligibility_checked_on') || '|' || (details ->> 'attested')
                  from audit_event where event_type = 'payment_method.activated'
                """)).isEqualTo("Sample Owner|2026-11-20|true");

        String listed = run("payment-method-list", "");
        assertThat(listed).startsWith("0\n").contains("zelle").contains("active=true").contains("eligibility checked by Sample Owner on 2026-11-20")
                .contains("revision 2").contains("CURRENT").contains("superseded");
    }

    @Test
    @Order(4)
    void d1_10_everyCommandIsAuditedWithTheOperatingSystemUserAndDeactivationKeepsTheMethod() throws Exception {
        assertThat(run("payment-method-deactivate", "zelle\n")).startsWith("0\n").contains("Deactivated");
        assertThat(one("select active::text || '|' || (deactivated_at is not null)::text from payment_method where id = 'zelle'")).isEqualTo("false|true");
        assertThat(run("payment-method-deactivate", "zelle\n")).startsWith("1\n").contains("not active");

        String user = System.getProperty("user.name");
        for (String event : List.of("payment_method.created", "payment_method.details_published", "payment_method.activated", "payment_method.deactivated")) {
            assertThat(one("select count(*)::text from audit_event where event_type = '" + event + "' and actor_operator = '" + user
                    + "' and outcome = 'success' and actor_account_id is null and client_address is null")).as(event).isNotEqualTo("0");
        }
        // The command made this database real for good, like every operator command.
        assertThat(one("select database_real::text from identity_guard")).isEqualTo("true");
        assertThat(run("payment-method-nothing", "")).startsWith("2\n").contains("Unknown command").contains("payment-method-create");
    }
}
