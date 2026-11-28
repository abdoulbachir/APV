package org.apvgakasinay.ledger;

/**
 * The entry written by {@link LedgerWriteService#recordCorrection}. {@code remainingCents} is
 * what still stands of the gift after this correction (confirmed amount minus every correction),
 * which the donations module reports and audits as "what still stands"
 * (contract/donation-lifecycle.md, 8.2).
 */
public record RecordedCorrection(long entryId, String entryPublicId, long remainingCents) {
}
