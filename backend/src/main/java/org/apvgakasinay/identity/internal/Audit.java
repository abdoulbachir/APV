package org.apvgakasinay.identity.internal;

import java.util.Map;
import org.apvgakasinay.audit.AuditService;
import org.apvgakasinay.audit.AuditService.AuditEvent;
import org.apvgakasinay.audit.AuditService.Outcome;
import org.springframework.stereotype.Component;

/**
 * The identity module's audit events. Details are built here from fixed keys, never copied
 * from a request: no password, hash, token, code, secret or credential can reach the log.
 */
@Component
class Audit {

    private final AuditService audit;

    Audit(AuditService audit) {
        this.audit = audit;
    }

    void event(String type, Outcome outcome, Long accountId, Caller caller, Map<String, String> details) {
        AuditEvent event = AuditEvent.of(type, outcome).details(details);
        if (accountId != null) {
            event = event.account(accountId);
        }
        if (caller != null) {
            event = event.request(caller.address(), caller.requestId());
        }
        audit.record(event);
    }

    void event(String type, Outcome outcome, Long accountId, Caller caller) {
        event(type, outcome, accountId, caller, Map.of());
    }

    void operator(String type, Outcome outcome, Long targetAccountId, String operator, Map<String, String> details) {
        AuditEvent event = AuditEvent.of(type, outcome).operator(operator).details(details);
        if (targetAccountId != null) {
            event = event.target(targetAccountId);
        }
        audit.record(event);
    }
}
