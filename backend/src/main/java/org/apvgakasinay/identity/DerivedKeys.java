package org.apvgakasinay.identity;

import org.apvgakasinay.identity.internal.Keys;
import org.springframework.stereotype.Component;

/**
 * Keys for other modules, derived from the hashing key that comes from the environment and is
 * never in the database (APV_HASH_KEY). A module asks for one key per purpose, by a label of
 * its own, and receives HMAC-SHA-256 of that label under the hashing key. A copy of the
 * database alone therefore yields nothing that was keyed with it
 * (contract/donation-lifecycle.md, 3.6), and the hashing key itself is never handed out.
 */
@Component
public class DerivedKeys {

    private final Keys keys;

    public DerivedKeys(Keys keys) {
        this.keys = keys;
    }

    /** The 32-byte key for a purpose. A label starts with {@code apv/} and names the module, the purpose and a version. */
    public byte[] derive(String label) {
        return keys.derive(label);
    }
}
