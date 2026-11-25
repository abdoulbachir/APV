package org.apvgakasinay.ledger;

import org.apvgakasinay.ledger.internal.LedgerRepository;
import org.apvgakasinay.shared.Language;
import org.apvgakasinay.shared.Page;
import org.apvgakasinay.shared.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the ledger. Every method runs in a read-only transaction with one snapshot;
 * called from another module's read transaction, it joins that snapshot.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class LedgerReadService {

    private final LedgerRepository repository;

    public LedgerReadService(LedgerRepository repository) {
        this.repository = repository;
    }

    public CaseFinancials financials(long caseId) {
        return repository.financials(caseId);
    }

    public PlatformTotals platformTotals() {
        return repository.platformTotals();
    }

    public Page<LedgerEntryResponse> entries(long caseId, Language language, PageRequest page) {
        return Page.of(repository.entries(caseId, language, page), page, repository.countEntries(caseId));
    }

    public Page<PublicDonorResponse> donors(long caseId, PageRequest page) {
        return Page.of(repository.donors(caseId, page), page, repository.countDonors(caseId));
    }
}
