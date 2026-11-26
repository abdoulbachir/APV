package org.apvgakasinay.audit.internal;

import java.util.Map;
import java.util.TreeMap;
import org.apvgakasinay.audit.AuditService.AuditEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEvent event) {
        jdbc.sql("""
                        insert into audit_event (event_type, outcome, actor_account_id, actor_operator, target_account_id,
                                                 client_address, request_id, details)
                        values (:type, :outcome, :actor, :operator, :target, :address, :requestId, :details::jsonb)
                        """)
                .param("type", event.type())
                .param("outcome", event.outcome().name().toLowerCase())
                .param("actor", event.actorAccountId())
                .param("operator", event.actorOperator())
                .param("target", event.targetAccountId())
                .param("address", event.clientAddress())
                .param("requestId", event.requestId())
                .param("details", json(event.details()))
                .update();
    }

    /** A flat JSON object of text values, with keys in a stable order. */
    private static String json(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : String.valueOf(text).toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
