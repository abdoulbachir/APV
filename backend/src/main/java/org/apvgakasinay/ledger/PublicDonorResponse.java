package org.apvgakasinay.ledger;

/** Public view of one donor listing. The amount is net of refunds and reversals. */
public record PublicDonorResponse(String id, String name, long amount, String date, String avatarUrl) {
}
