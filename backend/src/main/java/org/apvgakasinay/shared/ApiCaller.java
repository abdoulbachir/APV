package org.apvgakasinay.shared;

import jakarta.servlet.http.HttpServletRequest;

/**
 * What the request pipeline established about the caller of an {@link ApiOperation} before its
 * controller runs: the client address and request identifier for rate limits and the audit log,
 * and, only when the session is fully signed in, the account.
 *
 * <p>{@code securityVersion} is the account's version as the session holds it. An operation that
 * changes something on behalf of the account hands this record back to the identity module,
 * which re-reads the account under a lock and refuses when it has changed since.
 */
public record ApiCaller(Long accountId, long securityVersion, String address, String requestId) {

    public static final String ATTRIBUTE = "apv.api.caller";

    public static ApiCaller of(HttpServletRequest request) {
        return (ApiCaller) request.getAttribute(ATTRIBUTE);
    }

    public boolean signedIn() {
        return accountId != null;
    }
}
