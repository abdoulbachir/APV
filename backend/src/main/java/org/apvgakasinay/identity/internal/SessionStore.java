package org.apvgakasinay.identity.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Direct access to the stored sessions, for revocation. Sessions are stored with the stable
 * account identifier as their principal name, so every session of an account can be deleted
 * in the same transaction as the change that must end them. The attribute rows go with them.
 *
 * This does not depend on the web layer, so the operator command can use it.
 */
@Component
public class SessionStore {

    private final JdbcClient jdbc;

    public SessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes every stored session of an account, except one that is to continue (null for none). */
    public void endAllOf(Account account, String exceptSessionId) {
        jdbc.sql("delete from spring_session where principal_name = :name and session_id <> :except")
                .param("name", account.publicId())
                .param("except", exceptSessionId == null ? "" : exceptSessionId)
                .update();
    }

    /**
     * Deletes the session that used to have this identifier, if there is one. Every replaced
     * identifier is recorded with the session it belonged to (migration V4), so a request that
     * still holds the old identifier can end the session under its new one.
     */
    public void endSuccessorOf(String replacedSessionId) {
        jdbc.sql("""
                delete from spring_session
                 where primary_id in (select session_primary_id from spring_session_retired where session_id = :id)
                """)
                .param("id", replacedSessionId)
                .update();
    }

    public boolean exists(String sessionId) {
        return jdbc.sql("select exists (select 1 from spring_session where session_id = :id)")
                .param("id", sessionId).query(Boolean.class).single();
    }
}
