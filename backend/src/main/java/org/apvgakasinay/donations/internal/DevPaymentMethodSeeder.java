package org.apvgakasinay.donations.internal;

import java.util.List;
import org.apvgakasinay.donations.internal.PaymentMethodAdministration.Definition;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Localized;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.RecipientField;
import org.apvgakasinay.identity.DatabasePurpose;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Development only: two fictional payment methods, so the giving flow can be built and tried
 * locally. Their recipient details are recognisably fictional and lead nowhere. Nothing here
 * exists outside the dev profile or runs on a database that is not marked disposable, and
 * nothing is created when a method already exists.
 *
 * <p>Real methods and their real recipient details are never in the repository: they are
 * entered on the server with the operator command (contract/donation-lifecycle.md, 2.5).
 */
@Component
@Profile("dev")
@Order(20)
class DevPaymentMethodSeeder implements ApplicationRunner {

    private static final String OPERATOR = "development-seed";

    private final PaymentMethodAdministration administration;
    private final DatabasePurpose purpose;

    DevPaymentMethodSeeder(PaymentMethodAdministration administration, DatabasePurpose purpose) {
        this.administration = administration;
        this.purpose = purpose;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!purpose.disposable()) {
            System.out.println("DEVELOPMENT PAYMENT METHODS: not created. This database is not marked disposable.");
            return;
        }
        if (!administration.list().isEmpty()) {
            return;
        }
        seed("zelle", "Zelle", "Zelle", 1,
                new Definition(new Localized(
                        "Send your gift with Zelle from your bank's application, then come back to this page to report it.",
                        "Envoyez votre don avec Zelle depuis l’application de votre banque, puis revenez sur cette page pour le déclarer."),
                        List.of(new RecipientField(new Localized("Send to", "Envoyer à"), "gifts@example.test", "text"),
                                new RecipientField(new Localized("Name shown by your bank", "Nom affiché par votre banque"), "Fictional Recipient", "text"))));
        seed("paypal", "PayPal", "PayPal", 2,
                new Definition(new Localized(
                        "Open the link, send your gift, then come back to this page to report it.",
                        "Ouvrez le lien, envoyez votre don, puis revenez sur cette page pour le déclarer."),
                        List.of(new RecipientField(new Localized("Payment page", "Page de paiement"), "https://paypal.example.test/fictional-recipient", "link"))));
        System.out.println("DEVELOPMENT PAYMENT METHODS (fictional): zelle, paypal");
    }

    private void seed(String id, String nameEn, String nameFr, int position, Definition definition) {
        administration.create(id, nameEn, nameFr, position, OPERATOR);
        administration.publishDetails(id, definition, OPERATOR);
        administration.activate(id, null, OPERATOR);
    }
}
