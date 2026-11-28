package org.apvgakasinay.donations.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.apvgakasinay.donations.internal.DonationRepository.EvidenceRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The retention periods the owner approved (contract/donation-lifecycle.md, 14.1; decision 15):
 *
 * <ul>
 *   <li>a screenshot is kept while the gift is reported and 30 days after it is confirmed or
 *       rejected, then its file is deleted; the gift keeps that one existed and when it went;</li>
 *   <li>the payer name, payment reference, contact detail and private notes are kept 90 days
 *       after a rejection and 24 months after a confirmation, then erased from the gift.</li>
 * </ul>
 *
 * Amounts, cases, dates, the method, decisions, ledger entries, audit records and a public name
 * the donor agreed to show are the financial record and are kept. Each gift is its own
 * transaction with its audit record; a gift reopened meanwhile is left alone, because reopening
 * restarts its period.
 */
@Service
@Transactional
public class Retention {

    static final Duration EVIDENCE_AFTER_DECISION = Duration.ofDays(30);
    static final Duration DETAILS_AFTER_REJECTION = Duration.ofDays(90);
    static final Period DETAILS_AFTER_CONFIRMATION = Period.ofMonths(24);

    private final DonationRepository gifts;
    private final EvidenceStore store;
    private final DonationAudit audit;
    private final DonationClock clock;

    Retention(DonationRepository gifts, EvidenceStore store, DonationAudit audit, DonationClock clock) {
        this.gifts = gifts;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Long> evidenceDue(int atMost) {
        return gifts.evidenceDue(clock.now().minus(EVIDENCE_AFTER_DECISION), atMost);
    }

    @Transactional(readOnly = true)
    public List<Long> privateDetailsDue(int atMost) {
        Instant now = clock.now();
        return gifts.privateDetailsDue(now.minus(DETAILS_AFTER_REJECTION), confirmedBefore(now), atMost);
    }

    /** Deletes the screenshot of one gift whose time has come. Returns whether it did. */
    public boolean deleteEvidence(long donationId) {
        Instant now = clock.now();
        // Under the gift's lock, and only if it is still decided that long ago: a reopened gift keeps its screenshot.
        String state = gifts.lockState(donationId).orElse(null);
        DonationRepository.Gift gift = gifts.byId(donationId).orElse(null);
        if (state == null || gift == null || gift.decidedAt() == null || !gift.decidedAt().isBefore(now.minus(EVIDENCE_AFTER_DECISION))) {
            return false;
        }
        Optional<EvidenceRow> row = gifts.evidence(donationId).filter(found -> found.deletedAt() == null);
        if (row.isEmpty() || !gifts.markEvidenceDeleted(donationId, now)) {
            return false;
        }
        gifts.history(donationId, "evidence_deleted", null, now);
        audit.retention("donation.evidence_deleted", DonationAudit.details().gift(gift.publicId()).put("reason", "retention"));
        ReviewService.deleteFileAfterCommit(store, row.get().storageKey());
        return true;
    }

    /** Erases the private details of one gift whose time has come. Returns whether it did. */
    public boolean erasePrivateDetails(long donationId) {
        Instant now = clock.now();
        Optional<List<String>> erased = gifts.erasePrivateDetails(donationId, now.minus(DETAILS_AFTER_REJECTION), confirmedBefore(now), now);
        if (erased.isEmpty()) {
            return false;
        }
        String publicId = gifts.publicIdOf(donationId).orElseThrow();
        audit.retention("donation.private_details_erased", DonationAudit.details().gift(publicId).put("fields", String.join(",", erased.get())));
        return true;
    }

    private static Instant confirmedBefore(Instant now) {
        return now.atOffset(ZoneOffset.UTC).minus(DETAILS_AFTER_CONFIRMATION).toInstant();
    }
}
