package org.apvgakasinay.donations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apvgakasinay.donations.DonationIntegration;
import org.apvgakasinay.donations.Pictures;
import org.apvgakasinay.identity.Browser;
import org.apvgakasinay.support.SharedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Scenario 3.11: at most two pictures are decoded at once. An upload that finds no decoder free
 * within two seconds is answered 503 with Retry-After, keeps nothing, and spends nothing.
 */
class EvidenceBudgetIT extends DonationIntegration {

    @Autowired
    private EvidenceService evidence;

    private long files() throws Exception {
        try (Stream<Path> all = Files.walk(SharedPostgres.evidenceRoot())) {
            return all.filter(Files::isRegularFile).count();
        }
    }

    private Browser.Answer upload(String token) {
        Browser browser = visitor();
        browser.extraHeaders.put("X-Evidence-Token", token);
        return browser.sendBytes("POST", "/api/donations/evidence", Pictures.png(200, 200), "image/png", browser.token);
    }

    @Test
    void d3_11_whenEveryDecoderIsBusyAnUploadWaitsBrieflyThenIsAskedToComeBack() throws Exception {
        Reported gift = report("HELP-904", 5000, zelle());
        long before = files();
        // Both decoders are taken, as two large pictures being decoded would take them.
        assertThat(evidence.decoders().availablePermits()).isEqualTo(2);
        evidence.decoders().acquire(2);
        try {
            long started = System.nanoTime();
            Browser.Answer busy = upload(gift.uploadToken());
            long waitedMillis = (System.nanoTime() - started) / 1_000_000;
            assertError(busy, 503, "unavailable");
            assertThat(busy.header("Retry-After")).isEqualTo("5");
            assertThat(waitedMillis).isBetween(1900L, 8000L);
            assertThat(files()).isEqualTo(before);
            assertThat(count("select count(*) from donation_evidence")).isZero();
            assertThat(count("select count(*) from audit_event where event_type = 'donation.brake_held' and details ->> 'brake' = 'decoders'")).isEqualTo(1);
        } finally {
            evidence.decoders().release(2);
        }
        // Nothing was spent: the same token works once a decoder is free, and every place is given back afterwards.
        assertThat(upload(gift.uploadToken()).status()).isEqualTo(204);
        assertThat(evidence.decoders().availablePermits()).isEqualTo(2);
        assertThat(files()).isEqualTo(before + 1);
    }
}
