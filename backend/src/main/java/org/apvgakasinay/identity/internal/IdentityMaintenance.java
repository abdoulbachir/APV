package org.apvgakasinay.identity.internal;

import java.time.Duration;
import org.apvgakasinay.shared.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Housekeeping of the identity tables, run by the web application on a timer. It is not part
 * of the operator command.
 *
 * Removed: rate-limit windows and cooldown markers older than {@link Throttle#RETENTION}, and
 * pending sign-ins that ended more than an hour ago. Each run removes a bounded number of
 * rows, so it never holds a long transaction; what is left is taken by the next run.
 *
 * Not removed: one-time credentials. A used, cancelled or expired credential is only a hash
 * with its dates, and it is the record of what was issued to whom; how long to keep that is a
 * retention decision for the owner, not housekeeping. Expired sessions are removed by Spring
 * Session's own task.
 */
@Configuration
@EnableScheduling
public class IdentityMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityMaintenance.class);
    static final int BATCH = 1000;
    static final int MAX_BATCHES = 20;

    private final Throttle throttle;
    private final AccountRepository accounts;
    private final AppClock clock;

    public IdentityMaintenance(Throttle throttle, AccountRepository accounts, AppClock clock) {
        this.throttle = throttle;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${apv.identity.cleanup-interval:PT10M}", fixedDelayString = "${apv.identity.cleanup-interval:PT10M}")
    void scheduled() {
        try {
            int removed = run();
            if (removed > 0) {
                LOG.info("Identity housekeeping removed {} expired rows", removed);
            }
        } catch (RuntimeException failure) {
            // Housekeeping never takes the application down; the next run tries again.
            LOG.warn("Identity housekeeping failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** One bounded run. Returns how many rows were removed. */
    public int run() {
        int removed = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int count = throttle.removeExpired(BATCH);
            removed += count;
            if (count < BATCH) {
                break;
            }
        }
        removed += accounts.removeExpiredPending(clock.now().minus(Duration.ofHours(1)), BATCH);
        return removed;
    }
}
