package org.apvgakasinay.cases;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.apvgakasinay.ledger.CaseFinancials;
import org.apvgakasinay.ledger.LedgerEntryResponse;
import org.apvgakasinay.ledger.PublicDonorResponse;

/** One case with its money breakdown and previews of its history, all from one snapshot. */
public record CaseDetailResponse(
        @JsonProperty("case") CaseResponse caseView,
        CaseFinancials financials,
        List<CasePhotoResponse> photos,
        List<CaseUpdateResponse> updates,
        List<PublicDonorResponse> donors,
        List<LedgerEntryResponse> ledger,
        Counts counts,
        String asOf) {

    public record Counts(long updates, long donors, long ledger, long photos) {
    }
}
