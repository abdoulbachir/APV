package org.apvgakasinay.ledger;

/**
 * The two ways a confirmed gift's ledger entry is corrected (contract/donation-lifecycle.md,
 * section 8.1). A reversal repairs a wrong record; a refund records money that really went back.
 * Both are {@code corrects_kind = 'donation'} entries in the ledger.
 */
public enum CorrectionKind {
    DONATION_REVERSAL("donation_reversal"),
    REFUND("refund");

    private final String stored;

    CorrectionKind(String stored) {
        this.stored = stored;
    }

    /** The {@code ledger_kind} this correction is written as. */
    public String stored() {
        return stored;
    }
}
