package org.apvgakasinay.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.support.TestBlocklist;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Scenario 2.9, the accepting side: a list that meets every production requirement, opened by
 * the constructor production uses, with no profile and no shortcut. Needs no database. Records
 * what it costs to verify and to search a list of this size.
 */
class BreachedPasswordsProductionSizeIT {

    private static MockEnvironment production(Path list) {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new MockEnvironment()
                .withProperty("apv.identity.mfa-encryption-key", key)
                .withProperty("apv.identity.hash-key", key)
                .withProperty("apv.identity.password-blocklist-path", list.toString());
    }

    @Test
    void a2_9_aSuitableListIsAcceptedInProductionAndSearchedLocally() throws Exception {
        long started = System.nanoTime();
        Path file = TestBlocklist.production();
        long generated = (System.nanoTime() - started) / 1_000_000;
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long heapBefore = runtime.totalMemory() - runtime.freeMemory();

        started = System.nanoTime();
        try (BreachedPasswords list = new BreachedPasswords(new IdentityProperties(production(file)))) {
            long verified = (System.nanoTime() - started) / 1_000_000;
            runtime.gc();
            long heapAfter = runtime.totalMemory() - runtime.freeMemory();
            assertThat(list.present()).isTrue();
            assertThat(list.entries()).isGreaterThanOrEqualTo(BreachedPasswords.MIN_PRODUCTION_ENTRIES);
            assertThat(heapAfter - heapBefore).as("the list is searched on disk, not loaded").isLessThan(64L << 20);

            PasswordPolicy policy = new PasswordPolicy(list);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> policy.accept("newPassword", TestBlocklist.BREACHED_PASSPHRASE, List.of()))
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.fieldCode()).isEqualTo("compromised"));
            assertThat(policy.accept("newPassword", "a long harmless passphrase 2026", List.of())).isNotBlank();

            started = System.nanoTime();
            int lookups = 2000;
            int found = 0;
            for (int i = 0; i < lookups; i++) {
                found += list.contains("candidate passphrase number " + i) ? 1 : 0;
            }
            double microsPerLookup = (System.nanoTime() - started) / 1000.0 / lookups;
            assertThat(found).isZero();

            // The operator command accepts the same list, without reading all of it.
            started = System.nanoTime();
            try (BreachedPasswords operator = BreachedPasswords.forOperator(new IdentityProperties(production(file)))) {
                assertThat(operator.contains(TestBlocklist.BREACHED_PASSPHRASE)).isTrue();
            }
            long operatorMillis = (System.nanoTime() - started) / 1_000_000;

            Path evidence = Path.of("target", "identity-evidence");
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("breached-password-list.txt"), String.format(
                    "Synthetic list of production size: %d entries, %d MiB on disk (generated in %d ms; reused if already present).%n"
                            + "Server start-up verification, including SHA-256 of the whole file: %d ms. Operator command verification: %d ms.%n"
                            + "Heap added by opening the list: %d KiB. One lookup: %.1f microseconds on average over %d lookups.%n",
                    list.entries(), Files.size(file) >> 20, generated, verified, operatorMillis, (heapAfter - heapBefore) >> 10, microsPerLookup, lookups));
        }
    }
}
