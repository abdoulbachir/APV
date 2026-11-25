package org.apvgakasinay.ledger;

/** Platform-wide totals. Internal transfers are in neither number. */
public record PlatformTotals(long received, long delivered, long casesHelped) {
}
