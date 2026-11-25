package org.apvgakasinay.identity.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The permission matrix of contract/identity-and-security.md 1.2, in one place.
 *
 * Only the operational permissions are ever returned or enforced in this milestone. The
 * planned ones are names: nothing checks them and no response carries them until the
 * milestone that adds their endpoints also adds them to the API schema.
 */
public final class Permissions {

    /** Permissions that work with the endpoints that exist today. */
    public static final List<String> OPERATIONAL = List.of("account.read_own", "account.change_password");

    private static final List<String> EVERY_ACCOUNT = List.of("account.read_own", "account.change_password", "donations.read_own");
    private static final List<String> STAFF = List.of("cases.manage", "case_updates.post", "donation_reports.read");
    private static final List<String> SUPER_ADMIN = List.of("donations.confirm", "funds.transfer", "payment_methods.manage",
            "staff.manage", "contact_messages.read", "audit.read");

    private Permissions() {
    }

    /** The whole planned matrix for a role. A visitor (no account) has none. */
    public static Set<String> planned(String role, boolean canDisburse) {
        Set<String> granted = new LinkedHashSet<>();
        if (role == null) {
            return granted;
        }
        granted.addAll(EVERY_ACCOUNT);
        boolean admin = role.equals("admin");
        boolean superAdmin = role.equals("super_admin");
        if (admin || superAdmin) {
            granted.addAll(STAFF);
        }
        // A super administrator can always record a disbursement; an administrator only when chosen.
        if (superAdmin || (admin && canDisburse)) {
            granted.add("disbursements.record");
        }
        if (superAdmin) {
            granted.addAll(SUPER_ADMIN);
        }
        return granted;
    }

    /** What an account is told it may do: the planned permissions that are operational, in a fixed order. */
    public static List<String> operational(String role, boolean canDisburse) {
        Set<String> planned = planned(role, canDisburse);
        return OPERATIONAL.stream().filter(planned::contains).toList();
    }
}
