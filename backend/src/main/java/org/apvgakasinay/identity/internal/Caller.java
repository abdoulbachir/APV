package org.apvgakasinay.identity.internal;

/** Who is asking, as far as the audit log and the rate limits are concerned. */
public record Caller(String address, String requestId) {
}
