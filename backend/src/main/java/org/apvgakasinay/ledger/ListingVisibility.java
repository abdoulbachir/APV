package org.apvgakasinay.ledger;

/** How a confirmed gift appears in a case's public donor list (contract/donation-lifecycle.md, section 3.4). */
public enum ListingVisibility {
    NAMED("named"),
    ANONYMOUS("anonymous"),
    HIDDEN("hidden");

    private final String stored;

    ListingVisibility(String stored) {
        this.stored = stored;
    }

    /** The value written to {@code donation_listing.visibility}. */
    public String stored() {
        return stored;
    }
}
