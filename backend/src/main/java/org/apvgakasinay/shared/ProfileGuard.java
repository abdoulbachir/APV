package org.apvgakasinay.shared;

import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a production profile is combined with a profile that can load
 * fictional data. Fixture loading exists only in the dev and test profiles.
 */
@Component
public class ProfileGuard {

    public ProfileGuard(Environment environment) {
        Set<String> active = Set.of(environment.getActiveProfiles());
        boolean production = active.contains("prod") || active.contains("production");
        if (production && (active.contains("dev") || active.contains("test"))) {
            throw new IllegalStateException(
                    "A production profile cannot be combined with dev or test: fixtures must never load in production.");
        }
    }
}
