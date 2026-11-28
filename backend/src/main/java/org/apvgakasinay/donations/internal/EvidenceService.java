package org.apvgakasinay.donations.internal;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apvgakasinay.donations.internal.DonationRepository.Tracked;
import org.apvgakasinay.shared.ApiCaller;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.ApiOperation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Attaching the one optional screenshot of a gift (contract/donation-lifecycle.md, section 4).
 *
 * <p>The order matters and is the contract's: the token is checked first, and only a request
 * holding the token of an accepted report reaches the body, the decoder or the disk. A picture
 * is read up to the limit and no further, decoded by at most two requests at once within a time
 * limit, written again from its pixels, and only that file is stored. The token is spent by the
 * conditional insert of the gift's one evidence row, so of two simultaneous uploads exactly one
 * succeeds and the other leaves nothing behind.
 *
 * <p>No database transaction is held while a picture is read or decoded.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EvidenceService {

    private static final long DECODE_SECONDS = 15;
    private static final byte[] NO_SECRET = new byte[32];

    private final DonationRepository gifts;
    private final EvidenceStore store;
    private final DonationKeys keys;
    private final Brakes brakes;
    private final DonationAudit audit;
    private final DonationClock clock;
    private final DonationSettings settings;
    private final TransactionTemplate transaction;
    private final Semaphore decoders;
    private final ExecutorService decoding = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "evidence-decoder");
        thread.setDaemon(true);
        return thread;
    });

    EvidenceService(DonationRepository gifts, EvidenceStore store, DonationKeys keys, Brakes brakes, DonationAudit audit,
                    DonationClock clock, DonationSettings settings, PlatformTransactionManager transactions) {
        this.gifts = gifts;
        this.store = store;
        this.keys = keys;
        this.brakes = brakes;
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactions);
        this.decoders = new Semaphore(settings.decodeConcurrency(), true);
    }

    /** The places for pictures being decoded at once. Seen by the tests of the decode budget. */
    Semaphore decoders() {
        return decoders;
    }

    /**
     * Stores the screenshot sent with a gift's upload token.
     *
     * @param token a well-formed token; whether it is the token of a gift is decided here
     * @param body  the request body, not yet read
     */
    public void attach(String token, InputStream body, ApiCaller caller) throws IOException {
        Instant now = clock.now();

        // The secret. A token that is wrong, expired, already used, or whose gift is no longer
        // reported: one answer for all four, after the same work.
        String reference = DonationKeys.referenceOf(token);
        Tracked gift = reference == null ? null : gifts.trackedByReference(reference).orElse(null);
        String expected = keys.evidenceToken(gift == null ? "0000-0000" : gift.reference(), gift == null ? NO_SECRET : gift.secret());
        if (!DonationKeys.same(expected, token) || gift == null || !gift.origin().equals("reported")) {
            throw tokenRefused(null, "token", caller);
        }
        if (!gift.state().equals("reported")) {
            throw tokenRefused(gift, "state", caller);
        }
        if (!now.isBefore(gift.reportedAt().plus(GivingService.EVIDENCE_WINDOW))) {
            throw tokenRefused(gift, "expired", caller);
        }
        if (gift.evidenceEverAttached()) {
            throw tokenRefused(gift, "used", caller);
        }

        // The brakes: somewhere to store it, and room left.
        if (!store.available()) {
            throw Brakes.unavailable(600);
        }
        brakes.beforeEvidence(caller);

        // Only now is the body read, and never past the limit.
        byte[] sent = body.readNBytes(ApiOperation.IMAGE_MAX_BYTES + 1);
        if (sent.length > ApiOperation.IMAGE_MAX_BYTES) {
            throw ApiException.of(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "Picture too large");
        }

        EvidenceImages.Encoded picture = decode(sent, gift, caller);
        String key = EvidenceStore.newKey(picture.format());
        store.write(key, picture.bytes());
        boolean attached;
        try {
            long giftId = gift.id();
            String publicId = gift.publicId();
            attached = Boolean.TRUE.equals(transaction.execute(status -> {
                // Under the gift's lock: a decision made meanwhile is seen, and the gift is still reported or nothing is attached.
                if (!"reported".equals(gifts.lockState(giftId).orElse(null))
                        || !gifts.attachEvidence(giftId, key, picture.format(), picture.width(), picture.height(), picture.bytes().length,
                                DonationKeys.sha256(picture.bytes()), now)) {
                    return false;
                }
                gifts.history(giftId, "evidence_attached", null, now);
                audit.changed("donation.evidence_attached", caller, caller.accountId(), DonationAudit.details()
                        .gift(publicId).put("format", picture.format()).put("bytes", picture.bytes().length));
                return true;
            }));
        } catch (RuntimeException failure) {
            store.delete(key);
            throw failure;
        }
        if (!attached) {
            // Another upload with this token finished first, or the gift was decided meanwhile.
            store.delete(key);
            throw tokenRefused(gift, "used", caller);
        }
    }

    /** At most a fixed number of pictures are decoded at once, each within a time limit, never on the request's own thread. */
    private EvidenceImages.Encoded decode(byte[] sent, Tracked gift, ApiCaller caller) {
        boolean free;
        try {
            free = decoders.tryAcquire(settings.decodeWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            free = false;
        }
        if (!free) {
            throw brakes.decodersBusy(caller);
        }
        // The decoder gives its place back itself, when it has really finished: a picture that
        // ran past the time limit still counts against the bound until its work ends.
        Future<EvidenceImages.Encoded> work;
        try {
            work = decoding.submit(() -> {
                try {
                    return EvidenceImages.reencode(sent);
                } finally {
                    decoders.release();
                }
            });
        } catch (RuntimeException notStarted) {
            decoders.release();
            throw notStarted;
        }
        try {
            return work.get(DECODE_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof EvidenceImages.Invalid) {
                throw contentRefused(gift, caller);
            }
            throw new IllegalStateException("a picture could not be processed", failed.getCause());
        } catch (TimeoutException tooSlow) {
            throw contentRefused(gift, caller);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw Brakes.unavailable(5);
        }
    }

    private ApiException tokenRefused(Tracked gift, String reason, ApiCaller caller) {
        audit.refused("donation.evidence_refused", caller, caller.accountId(),
                DonationAudit.details().gift(gift == null ? null : gift.publicId()).put("reason", reason));
        return ApiException.of(HttpStatus.BAD_REQUEST, "evidence_token_invalid", "Invalid or expired upload token");
    }

    private ApiException contentRefused(Tracked gift, ApiCaller caller) {
        audit.refused("donation.evidence_refused", caller, caller.accountId(),
                DonationAudit.details().gift(gift.publicId()).put("reason", "content"));
        return ApiException.of(HttpStatus.BAD_REQUEST, "evidence_invalid", "The picture is not a JPEG or PNG that can be used");
    }
}
