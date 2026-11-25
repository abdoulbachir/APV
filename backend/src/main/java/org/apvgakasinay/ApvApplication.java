package org.apvgakasinay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One deployable application, split into modules by package: {@code site}, {@code cases},
 * {@code ledger}, {@code media}, plus {@code shared} plumbing and the development-only
 * {@code fixtures} importer. A module's {@code internal} package is private to it; the
 * architecture test enforces this.
 */
@SpringBootApplication
public class ApvApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApvApplication.class, args);
    }
}
