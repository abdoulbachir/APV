package org.apvgakasinay.donations;

import org.apvgakasinay.donations.internal.DonationClock;
import org.apvgakasinay.donations.internal.DonationSettings;
import org.apvgakasinay.donations.internal.PaymentMethodAdministration;
import org.apvgakasinay.donations.internal.PaymentMethodCommands;
import org.apvgakasinay.donations.internal.PaymentMethodRepository;
import org.springframework.context.annotation.Import;

/**
 * The beans the operator command needs for the payment-method commands
 * (contract/donation-lifecycle.md, 2.5), and nothing else of this module: no controller, no
 * filter and nothing that listens. The application's entry point adds this to the operator
 * command's own configuration.
 *
 * <p>Deliberately not a stereotype-annotated class, so the web application's component scan
 * ignores it; there the same beans are found by that scan.
 */
@Import({DonationClock.class, DonationSettings.class, PaymentMethodRepository.class, PaymentMethodAdministration.class,
        PaymentMethodCommands.class})
public class DonationOperatorConfiguration {
}
