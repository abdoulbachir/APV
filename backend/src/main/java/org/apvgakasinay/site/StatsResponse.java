package org.apvgakasinay.site;

/** Platform totals. {@code peopleHelped} counts cases with a positive net delivery, not people. */
public record StatsResponse(long received, long delivered, long available, long peopleHelped, String asOf) {
}
