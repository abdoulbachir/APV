package org.apvgakasinay.identity.internal;

import java.time.Instant;

/** One account as stored. Never holds a hash or a secret: those are in their own tables. */
public record Account(
        long id,
        String publicId,
        String email,
        String displayName,
        String locale,
        String role,
        boolean canDisburse,
        boolean enabled,
        boolean activated,
        Instant enrollmentWindowEndsAt,
        boolean emailVerified,
        long securityVersion,
        Instant createdAt) {

    public boolean isStaff() {
        return !role.equals("donor");
    }

    public boolean enrollmentWindowOpen(Instant now) {
        return enrollmentWindowEndsAt != null && now.isBefore(enrollmentWindowEndsAt);
    }
}
