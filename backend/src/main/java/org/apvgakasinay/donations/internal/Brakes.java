package org.apvgakasinay.donations.internal;

import java.time.Duration;
import org.apvgakasinay.shared.ApiCaller;
import org.apvgakasinay.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The brakes for the whole site (contract/donation-lifecycle.md, 12.3). A flood from many
 * addresses passes every per-address limit; the review queue is one person's attention and the
 * disk is finite, so each has its own bound. A brake that holds answers 503 with Retry-After
 * and writes nothing; each time one starts to hold, one audit record is written.
 */
@Component
class Brakes {

    private static final Duration HOUR = Duration.ofHours(1);
    /** How long a client is asked to wait when the queue is full: it empties only as fast as it is reviewed. */
    private static final long QUEUE_RETRY_SECONDS = 600;

    private final DonationSettings settings;
    private final DonationThrottle throttle;
    private final DonationRepository gifts;
    private final DonationAudit audit;

    Brakes(DonationSettings settings, DonationThrottle throttle, DonationRepository gifts, DonationAudit audit) {
        this.settings = settings;
        this.throttle = throttle;
        this.gifts = gifts;
        this.audit = audit;
    }

    /** Refuses a new report while too many wait to be reviewed, or too many arrived this hour. Counted before the report is recorded. */
    void beforeReport(ApiCaller caller) {
        if (gifts.waitingCount() >= settings.queueLimit()) {
            holds("queue", caller);
            throw paused(QUEUE_RETRY_SECONDS);
        }
        DonationThrottle.Tally accepted = throttle.tally("report.site", HOUR);
        if (accepted.count() > settings.reportsPerHour()) {
            holds("reports_per_hour", caller);
            throw paused(accepted.secondsLeft());
        }
    }

    /** Refuses a new screenshot while the stored ones fill their budget. Reports continue: evidence is optional. */
    void beforeEvidence(ApiCaller caller) {
        if (gifts.evidenceBytesStored() >= settings.evidenceBytesLimit()) {
            holds("evidence_storage", caller);
            throw unavailable(QUEUE_RETRY_SECONDS);
        }
    }

    /** Called when no decoder was free within the wait. */
    ApiException decodersBusy(ApiCaller caller) {
        holds("decoders", caller);
        return unavailable(5);
    }

    /** One audit record per brake and per hour while it holds, however many requests it refuses. */
    private void holds(String brake, ApiCaller caller) {
        if (throttle.tally("brake." + brake, HOUR).count() == 1) {
            audit.noted("donation.brake_held", caller, null, DonationAudit.details().put("brake", brake));
        }
    }

    private static ApiException paused(long retryAfterSeconds) {
        return ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "reporting_paused", "Reports cannot be taken at the moment")
                .header("Retry-After", String.valueOf(Math.min(3600, Math.max(1, retryAfterSeconds))));
    }

    static ApiException unavailable(long retryAfterSeconds) {
        return ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "Temporarily unavailable")
                .header("Retry-After", String.valueOf(Math.min(3600, Math.max(1, retryAfterSeconds))));
    }
}
