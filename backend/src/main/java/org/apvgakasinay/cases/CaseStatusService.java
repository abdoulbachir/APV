package org.apvgakasinay.cases;

import org.apvgakasinay.cases.internal.CaseRepository;
import org.apvgakasinay.cases.internal.CaseRepository.StatusRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The status transitions that money causes (contract/financial-terms.md, section 9;
 * contract/donation-lifecycle.md, 9.3). The donations module calls this in the transaction that
 * has just written a ledger entry on a case, so the entry and the status change commit together.
 *
 * <ul>
 *   <li>{@code received} reaching the goal: {@code active} becomes {@code funded}.</li>
 *   <li>{@code received} falling below the goal: {@code funded} returns to {@code active}.</li>
 *   <li>A {@code delivered} or {@code closed} case never changes by itself: it is reported back
 *       as needing review, which the caller records in the audit log.</li>
 *   <li>The general fund has no goal and its status never changes.</li>
 * </ul>
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CaseStatusService {

    /** What a money movement did to a case's status. {@code before} equals {@code after} when nothing changed. */
    public record StatusOutcome(String caseCode, String before, String after, boolean reviewNeeded) {

        public boolean changed() {
            return !before.equals(after);
        }
    }

    private final CaseRepository repository;

    public CaseStatusService(CaseRepository repository) {
        this.repository = repository;
    }

    public StatusOutcome afterMoneyMoved(long caseId) {
        StatusRow row = repository.lockForStatus(caseId).orElseThrow(() -> new IllegalStateException("no case " + caseId));
        String status = row.status();
        if (status.equals("delivered") || status.equals("closed")) {
            return new StatusOutcome(row.code(), status, status, true);
        }
        if (!row.kind().equals("case") || row.goalCents() == null) {
            return new StatusOutcome(row.code(), status, status, false);
        }
        String next = status;
        if (status.equals("active") && row.received() >= row.goalCents()) {
            next = "funded";
        } else if (status.equals("funded") && row.received() < row.goalCents()) {
            next = "active";
        }
        if (!next.equals(status)) {
            repository.setStatus(caseId, next);
        }
        return new StatusOutcome(row.code(), status, next, false);
    }
}
