package org.apvgakasinay.probe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A minimal application that exists only to answer the four compatibility questions of
 * ADR 0002 with running code. It is not the identity implementation: accounts are in memory,
 * there are no migrations, no rate limits, no audit, and the paths are not the contract's.
 */
@SpringBootApplication
public class ProbeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProbeApplication.class, args);
    }
}
