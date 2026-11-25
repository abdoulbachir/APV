package org.apvgakasinay.ledger;

import java.util.List;

/** Public view of one ledger entry. */
public record LedgerEntryResponse(
        String id,
        String kind,
        long amount,
        long allocatedDelta,
        long deliveredDelta,
        String date,
        String label,
        String corrects,
        List<String> correctedBy,
        long correctedAmount,
        long netAmount,
        String counterpartCaseCode) {
}
