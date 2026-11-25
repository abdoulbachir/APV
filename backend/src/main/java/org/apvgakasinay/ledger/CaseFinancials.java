package org.apvgakasinay.ledger;

/** Public money breakdown of one case. Field meanings: contract/financial-terms.md, section 3. */
public record CaseFinancials(
        long externalDonations,
        long transfersIn,
        long transfersOut,
        long allocated,
        long delivered,
        long available,
        boolean hasShortfall) {

    public static CaseFinancials of(long externalDonations, long transfersIn, long transfersOut, long delivered) {
        long allocated = externalDonations + transfersIn - transfersOut;
        long available = allocated - delivered;
        return new CaseFinancials(externalDonations, transfersIn, transfersOut, allocated, delivered, available, available < 0);
    }
}
