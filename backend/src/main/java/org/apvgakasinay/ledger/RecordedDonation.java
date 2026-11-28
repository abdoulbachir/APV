package org.apvgakasinay.ledger;

/**
 * The entry written by {@link LedgerWriteService#recordDonation}. The donations module stores
 * {@code entryId} on the gift row as its one ledger entry (contract/donation-lifecycle.md, 9.1).
 */
public record RecordedDonation(long entryId, String entryPublicId, String listingPublicId) {
}
