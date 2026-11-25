package org.apvgakasinay;

import org.apvgakasinay.identity.OperatorMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One deployable application, split into modules by package: {@code site}, {@code cases},
 * {@code ledger}, {@code media}, {@code identity}, {@code audit}, plus {@code shared} plumbing
 * and the development-only {@code fixtures} importer. A module's {@code internal} package is
 * private to it; the architecture test enforces this.
 *
 * Started as {@code operator <command>} it does not serve anything: it runs one staff
 * administration command on the terminal and exits.
 */
@SpringBootApplication
public class ApvApplication {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("operator")) {
            System.exit(OperatorMode.run(args.length > 1 ? args[1] : ""));
        }
        SpringApplication.run(ApvApplication.class, args);
    }
}
