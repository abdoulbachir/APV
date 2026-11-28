package org.apvgakasinay.donations.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apvgakasinay.audit.AuditService;
import org.apvgakasinay.audit.AuditService.AuditEvent;
import org.apvgakasinay.audit.AuditService.Outcome;
import org.apvgakasinay.shared.ApiCaller;
import org.springframework.stereotype.Component;

/**
 * The donations module's audit events (contract/donation-lifecycle.md, 13).
 *
 * <p>Details are built here from fixed keys and from values that are identifiers, amounts,
 * codes and dates. Nothing a donor or a reviewer typed as free text can reach the log: there is
 * no parameter here for a payer name, a contact detail, a payment reference, a public name, a
 * note, a tracking code, an upload token or an idempotency key.
 *
 * <p>An event of a change is written in the transaction of that change; an event of a refusal
 * is written in its own, so it stays when the refused operation rolls back.
 */
@Component
class DonationAudit {

    /** Builds the details of one event from an allowlist of keys. Null values are left out. */
    static final class Details {

        private final Map<String, String> values = new LinkedHashMap<>();

        Details gift(String publicId) {
            return put("gift", publicId);
        }

        Details put(String key, Object value) {
            if (value != null) {
                values.put(key, String.valueOf(value));
            }
            return this;
        }
    }

    static Details details() {
        return new Details();
    }

    private final AuditService audit;

    DonationAudit(AuditService audit) {
        this.audit = audit;
    }

    /** A change, recorded with it. */
    void changed(String type, ApiCaller caller, Long actorAccountId, Details details) {
        audit.recordWithin(event(type, Outcome.SUCCESS, caller, actorAccountId, details));
    }

    /** A refusal, recorded whatever happens to the request that was refused. */
    void refused(String type, ApiCaller caller, Long actorAccountId, Details details) {
        audit.record(event(type, Outcome.REFUSED, caller, actorAccountId, details));
    }

    /** Something that happened outside any change: a read, or a brake starting to hold. */
    void noted(String type, ApiCaller caller, Long actorAccountId, Details details) {
        audit.record(event(type, Outcome.SUCCESS, caller, actorAccountId, details));
    }

    /** A change the daily retention task made. There is no account and no request behind it. */
    void retention(String type, Details details) {
        audit.recordWithin(AuditEvent.of(type, Outcome.SUCCESS).operator("retention-task").details(details.values));
    }

    private static AuditEvent event(String type, Outcome outcome, ApiCaller caller, Long actorAccountId, Details details) {
        AuditEvent event = AuditEvent.of(type, outcome).details(details.values);
        if (actorAccountId != null) {
            event = event.account(actorAccountId);
        }
        if (caller != null) {
            event = event.request(caller.address(), caller.requestId());
        }
        return event;
    }
}
