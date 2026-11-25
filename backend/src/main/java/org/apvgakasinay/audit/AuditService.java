package org.apvgakasinay.audit;

import java.util.Map;
import org.apvgakasinay.audit.internal.AuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The append-only record of security events. Other modules call this; none reads or writes the
 * table itself.
 *
 * An event is written in its own transaction, so a refused or failed attempt is recorded even
 * when the operation around it rolls back.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class AuditService {

    /** How an event ended. */
    public enum Outcome { SUCCESS, FAILURE, REFUSED }

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Records one event. {@code details} must hold only what the event type documents: never a
     * password, hash, token, code, secret or one-time credential. Values are stored as text.
     */
    public void record(AuditEvent event) {
        repository.insert(event);
    }

    /** One security event. Use {@link #of} and the with-methods. */
    public record AuditEvent(
            String type,
            Outcome outcome,
            Long actorAccountId,
            String actorOperator,
            Long targetAccountId,
            String clientAddress,
            String requestId,
            Map<String, String> details) {

        public static AuditEvent of(String type, Outcome outcome) {
            return new AuditEvent(type, outcome, null, null, null, null, null, Map.of());
        }

        /** The account that acted, which is also the account acted on unless a target is set. */
        public AuditEvent account(Long accountId) {
            return new AuditEvent(type, outcome, accountId, actorOperator, targetAccountId == null ? accountId : targetAccountId, clientAddress, requestId, details);
        }

        public AuditEvent operator(String operator) {
            return new AuditEvent(type, outcome, actorAccountId, operator, targetAccountId, clientAddress, requestId, details);
        }

        public AuditEvent target(Long accountId) {
            return new AuditEvent(type, outcome, actorAccountId, actorOperator, accountId, clientAddress, requestId, details);
        }

        public AuditEvent request(String address, String id) {
            return new AuditEvent(type, outcome, actorAccountId, actorOperator, targetAccountId, address, id, details);
        }

        public AuditEvent details(Map<String, String> values) {
            return new AuditEvent(type, outcome, actorAccountId, actorOperator, targetAccountId, clientAddress, requestId, Map.copyOf(values));
        }
    }
}
