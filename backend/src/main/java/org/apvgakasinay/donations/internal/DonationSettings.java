package org.apvgakasinay.donations.internal;

import java.nio.file.Path;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Settings of the donations module, read once at start-up: where screenshots are kept, and the
 * four brakes of contract/donation-lifecycle.md 12.3, which are configuration and never below one.
 */
@Component
public class DonationSettings {

    private final boolean development;
    private final Path evidenceRoot;
    private final int reportsPerHour;
    private final int queueLimit;
    private final long evidenceBytesLimit;
    private final int decodeConcurrency;
    private final long decodeWaitMillis;

    public DonationSettings(Environment environment) {
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        this.development = profiles.contains("dev") || profiles.contains("test");
        String root = environment.getProperty("apv.donations.evidence-root", "").trim();
        this.evidenceRoot = root.isEmpty() ? null : Path.of(root).toAbsolutePath().normalize();
        this.reportsPerHour = atLeastOne(environment, "apv.donations.brakes.reports-per-hour", 60).intValue();
        this.queueLimit = atLeastOne(environment, "apv.donations.brakes.queue", 300).intValue();
        this.evidenceBytesLimit = atLeastOne(environment, "apv.donations.brakes.evidence-bytes", 2L * 1024 * 1024 * 1024);
        this.decodeConcurrency = atLeastOne(environment, "apv.donations.brakes.decodes", 2).intValue();
        this.decodeWaitMillis = atLeastOne(environment, "apv.donations.brakes.decode-wait-millis", 2000);
    }

    private static Long atLeastOne(Environment environment, String name, long fallback) {
        long value = environment.getProperty(name, Long.class, fallback);
        if (value < 1) {
            throw new IllegalStateException(name + " must be at least 1.");
        }
        return value;
    }

    /** True in the dev and test profiles, where a payment method may be activated without an eligibility attestation. */
    public boolean development() {
        return development;
    }

    /** The private directory screenshots are stored under, or null when none is configured. */
    public Path evidenceRoot() {
        return evidenceRoot;
    }

    public int reportsPerHour() {
        return reportsPerHour;
    }

    public int queueLimit() {
        return queueLimit;
    }

    public long evidenceBytesLimit() {
        return evidenceBytesLimit;
    }

    public int decodeConcurrency() {
        return decodeConcurrency;
    }

    public long decodeWaitMillis() {
        return decodeWaitMillis;
    }
}
