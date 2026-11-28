package org.apvgakasinay.donations.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled work of the donations module, run by the web application; it is not part of the
 * operator command.
 *
 * <p>Daily: the retention periods of {@link Retention}. Every ten minutes: rate-limit windows
 * and idempotency keys that can no longer matter are removed. Each run handles a bounded number
 * of rows and each gift in its own transaction, so no run holds a long one and a failure on one
 * gift does not stop the others.
 */
@Component
public class DonationMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(DonationMaintenance.class);
    static final int BATCH = 500;

    private final Retention retention;
    private final DonationThrottle throttle;
    private final Idempotency idempotency;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    DonationMaintenance(Retention retention, DonationThrottle throttle, Idempotency idempotency,
                        org.springframework.transaction.PlatformTransactionManager transactions) {
        this.retention = retention;
        this.throttle = throttle;
        this.idempotency = idempotency;
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    }

    @Scheduled(initialDelayString = "${apv.donations.retention-initial-delay:PT5M}", fixedDelayString = "${apv.donations.retention-interval:PT24H}")
    void daily() {
        try {
            int done = applyRetention();
            if (done > 0) {
                LOG.info("Donation retention deleted or erased the private data of {} gifts", done);
            }
        } catch (RuntimeException failure) {
            LOG.warn("Donation retention failed: {}", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(initialDelayString = "${apv.donations.cleanup-interval:PT10M}", fixedDelayString = "${apv.donations.cleanup-interval:PT10M}")
    void housekeeping() {
        try {
            removeExpired();
        } catch (RuntimeException failure) {
            LOG.warn("Donation housekeeping failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** One bounded retention run. Returns how many gifts had a screenshot deleted or private details erased. */
    public int applyRetention() {
        int done = 0;
        for (Long id : retention.evidenceDue(BATCH)) {
            done += attempt(() -> retention.deleteEvidence(id));
        }
        for (Long id : retention.privateDetailsDue(BATCH)) {
            done += attempt(() -> retention.erasePrivateDetails(id));
        }
        return done;
    }

    /** Removes counters and idempotency keys that are older than anything that reads them. */
    public int removeExpired() {
        int removed = throttle.removeExpired(BATCH * 2);
        Integer keys = transaction.execute(status -> idempotency.removeExpired(BATCH * 2));
        return removed + (keys == null ? 0 : keys);
    }

    private static int attempt(java.util.function.BooleanSupplier one) {
        try {
            return one.getAsBoolean() ? 1 : 0;
        } catch (RuntimeException failure) {
            // One gift that cannot be handled now does not stop the rest; the next run tries it again.
            LOG.warn("Donation retention could not handle one gift: {}", failure.getClass().getSimpleName());
            return 0;
        }
    }
}
