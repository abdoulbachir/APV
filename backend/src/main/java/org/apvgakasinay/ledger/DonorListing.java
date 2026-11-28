package org.apvgakasinay.ledger;

/**
 * How a confirmed gift is to be listed, as the donations module decides it from the donor's
 * choice and the reviewer's {@code listing} decision (contract/donation-lifecycle.md, 6.3).
 *
 * <p>A gift carries no picture in milestone 2: a guest has none and a picture is a separate
 * consent. {@code donorName} is the name the donor typed and agreed to show, present only when
 * {@link ListingVisibility#NAMED}.
 */
public record DonorListing(ListingVisibility visibility, String donorName) {

    public static DonorListing named(String donorName) {
        return new DonorListing(ListingVisibility.NAMED, donorName);
    }

    public static DonorListing anonymous() {
        return new DonorListing(ListingVisibility.ANONYMOUS, null);
    }

    public static DonorListing hidden() {
        return new DonorListing(ListingVisibility.HIDDEN, null);
    }
}
