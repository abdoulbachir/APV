package org.apvgakasinay.donations.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.apvgakasinay.donations.internal.PaymentMethodAdministration.Attestation;
import org.apvgakasinay.donations.internal.PaymentMethodAdministration.Definition;
import org.apvgakasinay.donations.internal.PaymentMethodAdministration.Refused;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Details;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Method;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.RecipientField;
import org.apvgakasinay.identity.OperatorCommandSet;
import org.springframework.stereotype.Component;

/**
 * The five payment-method commands of the operator command (contract/donation-lifecycle.md, 2.5):
 *
 *   java -jar apv-backend.jar operator payment-method-create
 *
 * Run on the server, prompting on the terminal, never over the network. Recipient details are
 * read from a file the operator names, shown next to the current version, and published only
 * after the operator types the method identifier again.
 */
@Component
public class PaymentMethodCommands implements OperatorCommandSet {

    private static final Set<String> COMMANDS = Set.of("payment-method-create", "payment-method-publish-details",
            "payment-method-activate", "payment-method-deactivate", "payment-method-list");

    private final PaymentMethodAdministration administration;
    private final DonationSettings settings;

    public PaymentMethodCommands(PaymentMethodAdministration administration, DonationSettings settings) {
        this.administration = administration;
        this.settings = settings;
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public int run(String command, String operator, BufferedReader in, PrintStream out) throws IOException {
        try {
            switch (command) {
                case "payment-method-create" -> {
                    String id = ask(in, out, "Identifier (for example zelle)");
                    String nameEn = ask(in, out, "English name");
                    String nameFr = ask(in, out, "French name");
                    administration.create(id, nameEn, nameFr, integer(ask(in, out, "Position in the list (1 is first)")), operator);
                    out.println("Created, inactive. Publish its recipient details, then activate it.");
                }
                case "payment-method-publish-details" -> publish(operator, in, out);
                case "payment-method-activate" -> {
                    String id = ask(in, out, "Identifier");
                    Attestation attestation = null;
                    if (!settings.development()) {
                        out.println("Before a method is enabled, the provider's eligibility rules, geographic limits and terms must be checked.");
                        out.println("A personal account is not assumed to be permitted for collecting donations. Your answers are recorded.");
                        attestation = new Attestation(ask(in, out, "Who checked"), date(ask(in, out, "On which date (YYYY-MM-DD)")),
                                ask(in, out, "What was checked"));
                    }
                    administration.activate(id, attestation, operator);
                    out.println("Active. Donors can now choose " + id + ".");
                }
                case "payment-method-deactivate" -> {
                    String id = ask(in, out, "Identifier");
                    administration.deactivate(id, operator);
                    out.println("Deactivated. Gifts already reported keep their reference to it.");
                }
                case "payment-method-list" -> list(out);
                default -> throw new IllegalStateException(command);
            }
            return 0;
        } catch (Refused refused) {
            out.println("Refused: " + refused.getMessage());
            return 1;
        }
    }

    private void publish(String operator, BufferedReader in, PrintStream out) throws IOException {
        String id = ask(in, out, "Identifier");
        if (!administration.exists(id)) {
            throw new Refused("there is no payment method " + id);
        }
        Path file = Path.of(ask(in, out, "File holding the details definition (JSON)"));
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            throw new Refused("that file cannot be read");
        }
        Definition definition = PaymentMethodAdministration.parse(json);
        Details current = administration.current(id);
        out.println();
        out.println("CURRENT VERSION" + (current == null ? ": none" : " (revision " + current.revision() + ")"));
        if (current != null) {
            show(out, current.instructions().en(), current.instructions().fr(), current.recipient());
        }
        out.println();
        out.println("NEW VERSION");
        show(out, definition.instructions().en(), definition.instructions().fr(), definition.recipient());
        out.println();
        out.println("Every gift reported from now on will be sent to the NEW details. Check each value against the payment account.");
        if (!ask(in, out, "Type the method identifier again to publish").equals(id)) {
            throw new Refused("the identifier did not match; nothing was published");
        }
        int revision = administration.publishDetails(id, definition, operator);
        out.println("Published as revision " + revision + ". The previous version is superseded.");
    }

    private void list(PrintStream out) {
        List<Method> methods = administration.list();
        if (methods.isEmpty()) {
            out.println("No payment method exists. Gifts cannot be reported until one is active.");
        }
        for (Method method : methods) {
            out.printf("%s  %s / %s  position=%d  active=%s%n", method.id(), method.nameEn(), method.nameFr(), method.position(), method.active());
            if (method.eligibilityCheckedBy() != null) {
                out.printf("    eligibility checked by %s on %s: %s%n", method.eligibilityCheckedBy(), method.eligibilityCheckedOn(), method.eligibilityNotes());
            } else {
                out.println("    eligibility: no attestation recorded");
            }
            for (Details version : administration.versions(method.id())) {
                out.printf("    revision %d  %s  published %s  %s%n", version.revision(), version.publicId(), version.publishedAt(),
                        version.supersededAt() == null ? "CURRENT" : "superseded " + version.supersededAt());
            }
        }
    }

    private static void show(PrintStream out, String instructionsEn, String instructionsFr, List<RecipientField> fields) {
        out.println("  Instructions (en): " + instructionsEn);
        out.println("  Instructions (fr): " + instructionsFr);
        for (RecipientField field : fields) {
            out.println("  " + field.labels().en() + " / " + field.labels().fr() + " [" + field.format() + "]: " + field.value());
        }
    }

    private static String ask(BufferedReader in, PrintStream out, String question) throws IOException {
        out.print(question + ": ");
        out.flush();
        String answer = in.readLine();
        if (answer == null || answer.isBlank()) {
            throw new Refused("nothing was entered for: " + question);
        }
        return answer.trim();
    }

    private static int integer(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            throw new Refused("that is not a whole number");
        }
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException notADate) {
            throw new Refused("that is not a date written YYYY-MM-DD");
        }
    }
}
