package org.apvgakasinay.cases;

/**
 * A non-draft case resolved from a code for another module (the donations module), with enough
 * to decide a report or a confirmation without that module reading the cases tables.
 *
 * <p>{@code accepting} is the same effective value the public {@code Case.acceptingDonations}
 * carries: the case accepts gifts, is active or funded, and is before any deadline cutoff
 * (contract/donation-lifecycle.md, 3.3). A confirmation ignores it (6.3); a report requires it.
 */
public record ResolvedCase(long id, String code, String kind, String status, boolean accepting) {

    public boolean isFund() {
        return "fund".equals(kind);
    }
}
