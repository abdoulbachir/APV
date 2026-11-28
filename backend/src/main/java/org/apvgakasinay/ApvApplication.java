package org.apvgakasinay;

import org.apvgakasinay.identity.OperatorMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One deployable application, split into modules by package: {@code site}, {@code cases},
 * {@code ledger}, {@code media}, {@code identity}, {@code audit}, {@code donations}, plus {@code shared} plumbing
 * and the development-only {@code fixtures} importer. A module's {@code internal} package is
 * private to it; the architecture test enforces this.
 *
 * Started as {@code operator <command>} it does not serve anything: it runs one staff
 * administration command on the terminal and exits. Started as {@code blocklist build|verify}
 * it builds or verifies the local breached-password list, without a database or a network.
 */
@SpringBootApplication
public class ApvApplication {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("operator")) {
            // The staff commands of the identity module, and the payment-method commands the donations module adds.
            System.exit(OperatorMode.run(args.length > 1 ? args[1] : "", org.apvgakasinay.donations.DonationOperatorConfiguration.class));
        }
        if (args.length > 0 && args[0].equals("blocklist")) {
            System.exit(org.apvgakasinay.identity.BlocklistTool.run(java.util.Arrays.copyOfRange(args, 1, args.length), System.out));
        }
        SpringApplication.run(ApvApplication.class, args);
    }
}
