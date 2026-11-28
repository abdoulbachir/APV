package org.apvgakasinay.shared;

import java.util.OptionalLong;

/**
 * Provides the per-client-address rate limits that {@link ApiOperation#limit()} names. A module
 * that declares such operations supplies one bean; the request pipeline asks it at step 5,
 * before the session is looked at.
 */
public interface AddressLimiter {

    /**
     * Counts one request against the named limit for a client address.
     *
     * @return empty when this limiter does not know the name; otherwise 0 when the request is
     *         within the limit, or the seconds to wait when it is not
     */
    OptionalLong count(String limit, String clientAddress);
}
