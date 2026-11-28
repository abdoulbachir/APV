package org.apvgakasinay.ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apvgakasinay.ledger.internal.LedgerWriteRepository;
import org.apvgakasinay.ledger.internal.LedgerWriteRepository.LockedDonation;
import org.apvgakasinay.shared.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write side of the ledger (ADR 0001: only the ledger writes ledger entries). Other modules
 * ask it to record a confirmed gift or a correction and pass a case already resolved to its id;
 * the ledger never reads a case code (ArchUnit {@code ledgerDoesNotKnowCases}).
 *
 * <p>Every method runs in the caller's transaction, so a confirmation or a correction and the
 * entry it writes commit together or not at all (9.1). The database triggers remain the final
 * guard: this service pre-checks what it must to answer with the contract's codes, and maps the
 * platform-bound constraint should a race reach it.
 */
@Service
@Transactional
public class LedgerWriteService {

    private final LedgerWriteRepository repository;

    public LedgerWriteService(LedgerWriteRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes the one {@code donation} entry of a confirmed gift on the resolved case, for the
     * confirmed amount, dated at {@code at} (truncated to whole seconds, 9.1), and the donor
     * listing that goes with it. A write that would pass the platform bound answers 409
     * {@code platform_limit_reached} (6.3).
     */
    public RecordedDonation recordDonation(long caseId, long amountCents, Instant at, DonorListing listing) {
        Instant effectiveAt = at.truncatedTo(ChronoUnit.SECONDS);
        guardPlatformBound(amountCents);
        String entryPublicId = LedgerWriteRepository.publicId("led_");
        long entryId = insert(() -> repository.insertDonation(caseId, amountCents, effectiveAt, entryPublicId));
        String listingPublicId = LedgerWriteRepository.publicId("lst_");
        repository.insertListing(entryId, listingPublicId, listing.visibility().stored(),
                listing.visibility() == ListingVisibility.NAMED ? listing.donorName() : null);
        return new RecordedDonation(entryId, entryPublicId, listingPublicId);
    }

    /**
     * Writes a correction (reversal or refund) of a gift's {@code donation} entry, recorded on the
     * case the gift was confirmed for and dated at {@code at}. The gift's entry is locked while the
     * remaining amount is checked, so two simultaneous corrections cannot both pass (8.2); a
     * request for more than what still stands answers 409 {@code correction_exceeds_remaining}.
     *
     * <p>The caller holds the lock on the gift row and has verified the gift is {@code confirmed};
     * this adds the lock on the ledger entry, keeping the order gift, entry, case, platform total.
     */
    public RecordedCorrection recordCorrection(long donationEntryId, CorrectionKind kind, long amountCents, Instant at) {
        if (amountCents < 1) {
            throw new IllegalArgumentException("a correction is for at least one cent");
        }
        Instant effectiveAt = at.truncatedTo(ChronoUnit.SECONDS);
        LockedDonation target = repository.lockDonation(donationEntryId);
        long remaining = target.amountCents() - repository.correctionsTotal(donationEntryId);
        if (amountCents > remaining) {
            throw ApiException.of(HttpStatus.CONFLICT, "correction_exceeds_remaining",
                    "a correction is for at most what still stands of the gift");
        }
        guardPlatformBound(amountCents);
        String entryPublicId = LedgerWriteRepository.publicId("led_");
        long entryId = insert(() -> repository.insertCorrection(target.caseId(), donationEntryId, kind.stored(), amountCents, effectiveAt, entryPublicId));
        return new RecordedCorrection(entryId, entryPublicId, remaining - amountCents);
    }

    /** What still stands of a confirmed gift: its entry amount less every correction. Locks the entry. */
    public long remaining(long donationEntryId) {
        LockedDonation target = repository.lockDonation(donationEntryId);
        return target.amountCents() - repository.correctionsTotal(donationEntryId);
    }

    /** Lowers how a confirmed gift is listed (8.4). The caller guarantees this shows no more than before. */
    public void restrictListing(long donationEntryId, ListingVisibility to) {
        if (to == ListingVisibility.NAMED) {
            throw new IllegalArgumentException("restricting a listing can only show less, never a name");
        }
        repository.restrictListing(donationEntryId, to.stored());
    }

    /** Refuses before the insert when the platform-wide total would pass its bound. */
    private void guardPlatformBound(long amountCents) {
        if (repository.platformGross() + amountCents > LedgerWriteRepository.PLATFORM_BOUND_CENTS) {
            throw platformLimitReached();
        }
    }

    /** Runs an insert, mapping the platform-bound constraint a concurrent writer may trip to its code. */
    private long insert(java.util.function.LongSupplier insert) {
        try {
            return insert.getAsLong();
        } catch (DataIntegrityViolationException violation) {
            if (isPlatformBound(violation)) {
                throw platformLimitReached();
            }
            throw violation;
        }
    }

    private static ApiException platformLimitReached() {
        return ApiException.of(HttpStatus.CONFLICT, "platform_limit_reached", "the platform-wide movement total is at its bound");
    }

    /**
     * True when the failure is the platform-bound check constraint tripping. The database names the
     * constraint in the message of the chained SQL error, so matching it needs no JDBC-driver type.
     */
    private static boolean isPlatformBound(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(LedgerWriteRepository.PLATFORM_BOUND_CONSTRAINT)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
