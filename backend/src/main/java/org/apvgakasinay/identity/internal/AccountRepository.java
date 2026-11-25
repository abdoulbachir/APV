package org.apvgakasinay.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL of the identity module's account data. Things that may be used only once (a code's
 * time step, a recovery code, a one-time credential, a pending enrollment) are consumed by
 * conditional updates, so two simultaneous requests cannot both succeed.
 */
@Repository
public class AccountRepository {

    private static final String ACCOUNT = """
            select id, public_id, email, display_name, locale, role, can_disburse, enabled, activated,
                   enrollment_window_ends_at, email_verified, security_version, created_at
              from account
            """;

    /** The stored second factor of an account. */
    public record TotpRow(byte[] secretEncrypted, String state, Instant pendingExpiresAt, long lastAcceptedStep) {

        public boolean active() {
            return state.equals("active");
        }
    }

    /** A redeemed one-time credential. */
    public record Redeemed(long accountId, String purpose) {
    }

    /** The guard row. */
    public record Guard(boolean bootstrapped, boolean databaseReal) {
    }

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Account account(ResultSet rs, int row) throws SQLException {
        Timestamp window = rs.getTimestamp("enrollment_window_ends_at");
        return new Account(
                rs.getLong("id"), rs.getString("public_id"), rs.getString("email"), rs.getString("display_name"),
                rs.getString("locale"), rs.getString("role"), rs.getBoolean("can_disburse"), rs.getBoolean("enabled"),
                rs.getBoolean("activated"), window == null ? null : window.toInstant(), rs.getBoolean("email_verified"),
                rs.getLong("security_version"), rs.getTimestamp("created_at").toInstant());
    }

    // ------------------------------------------------------------------ accounts

    public Optional<Account> findById(long id) {
        return jdbc.sql(ACCOUNT + " where id = :id").param("id", id).query(AccountRepository::account).optional();
    }

    /** Reads the account and holds a shared lock on its row until the transaction ends (6.3). */
    public Optional<Account> findByIdForShare(long id) {
        return jdbc.sql(ACCOUNT + " where id = :id for share").param("id", id).query(AccountRepository::account).optional();
    }

    /** Reads the account and holds an exclusive lock on its row until the transaction ends. */
    public Optional<Account> findByIdForUpdate(long id) {
        return jdbc.sql(ACCOUNT + " where id = :id for update").param("id", id).query(AccountRepository::account).optional();
    }

    public Optional<Account> findByNormalizedEmail(String normalizedEmail) {
        return jdbc.sql(ACCOUNT + " where email_normalized = :email").param("email", normalizedEmail)
                .query(AccountRepository::account).optional();
    }

    public List<Account> findStaff() {
        return jdbc.sql(ACCOUNT + " where role <> 'donor' order by email_normalized").query(AccountRepository::account).list();
    }

    /**
     * Creates an account unless the address is taken. The unique constraint decides between
     * simultaneous attempts: exactly one gets an identifier back.
     */
    public Optional<Long> insertIfAbsent(String publicId, String email, String normalizedEmail, String displayName, String locale,
                                         String role, boolean activated, Instant now) {
        return jdbc.sql("""
                        insert into account (public_id, email, email_normalized, display_name, locale, role, activated, created_at)
                        values (:publicId, :email, :normalized, :displayName, :locale, :role, :activated, :now)
                        on conflict (email_normalized) do nothing
                        returning id
                        """)
                .param("publicId", publicId).param("email", email).param("normalized", normalizedEmail)
                .param("displayName", displayName).param("locale", locale).param("role", role)
                .param("activated", activated).param("now", Timestamp.from(now))
                .query(Long.class).optional();
    }

    public Optional<String> passwordHash(long accountId) {
        return jdbc.sql("select password_hash from account_password where account_id = :id").param("id", accountId)
                .query(String.class).optional();
    }

    public void setPassword(long accountId, String hash, Instant now) {
        jdbc.sql("""
                        insert into account_password (account_id, password_hash, changed_at) values (:id, :hash, :now)
                        on conflict (account_id) do update set password_hash = excluded.password_hash, changed_at = excluded.changed_at
                        """)
                .param("id", accountId).param("hash", hash).param("now", Timestamp.from(now)).update();
    }

    public void removePassword(long accountId) {
        jdbc.sql("delete from account_password where account_id = :id").param("id", accountId).update();
    }

    /** Raises the security version, which ends every session that stored the old one. Returns the new value. */
    public long raiseSecurityVersion(long accountId) {
        return jdbc.sql("update account set security_version = security_version + 1 where id = :id returning security_version")
                .param("id", accountId).query(Long.class).single();
    }

    public void setActivation(long accountId, boolean activated, Instant enrollmentWindowEndsAt) {
        jdbc.sql("update account set activated = :activated, enrollment_window_ends_at = :window where id = :id")
                .param("activated", activated)
                .param("window", enrollmentWindowEndsAt == null ? null : Timestamp.from(enrollmentWindowEndsAt))
                .param("id", accountId).update();
    }

    public void setEnabled(long accountId, boolean enabled) {
        jdbc.sql("update account set enabled = :enabled where id = :id").param("enabled", enabled).param("id", accountId).update();
    }

    public void setCanDisburse(long accountId, boolean canDisburse) {
        jdbc.sql("update account set can_disburse = :value where id = :id").param("value", canDisburse).param("id", accountId).update();
    }

    // ------------------------------------------------------------------ second factor

    public Optional<TotpRow> totp(long accountId) {
        return jdbc.sql("select secret_encrypted, state, pending_expires_at, last_accepted_step from account_totp where account_id = :id")
                .param("id", accountId)
                .query((rs, row) -> new TotpRow(rs.getBytes("secret_encrypted"), rs.getString("state"),
                        rs.getTimestamp("pending_expires_at") == null ? null : rs.getTimestamp("pending_expires_at").toInstant(),
                        rs.getLong("last_accepted_step")))
                .optional();
    }

    public boolean hasActiveTotp(long accountId) {
        return jdbc.sql("select exists (select 1 from account_totp where account_id = :id and state = 'active')")
                .param("id", accountId).query(Boolean.class).single();
    }

    /** Stores a pending secret, replacing an earlier pending one. Does nothing if a factor is already active. */
    public boolean storePendingTotp(long accountId, byte[] secretEncrypted, Instant expiresAt) {
        return jdbc.sql("""
                        insert into account_totp (account_id, secret_encrypted, state, pending_expires_at)
                        values (:id, :secret, 'pending', :expires)
                        on conflict (account_id) do update
                           set secret_encrypted = excluded.secret_encrypted, pending_expires_at = excluded.pending_expires_at,
                               last_accepted_step = -1
                         where account_totp.state = 'pending'
                        """)
                .param("id", accountId).param("secret", secretEncrypted).param("expires", Timestamp.from(expiresAt)).update() == 1;
    }

    /**
     * Accepts a code's time step, once. The step only moves forward, so of two requests with
     * the same code exactly one changes the row.
     */
    public boolean acceptStep(long accountId, long step) {
        return jdbc.sql("""
                        update account_totp set last_accepted_step = :step
                         where account_id = :id and state = 'active' and last_accepted_step < :step
                        """)
                .param("step", step).param("id", accountId).update() == 1;
    }

    /**
     * Turns the pending secret into the active factor, once, and only if it is still the
     * secret the caller verified a code against.
     */
    public boolean activatePendingTotp(long accountId, byte[] expectedSecretEncrypted, long step, Instant now, byte[] sessionHash) {
        return jdbc.sql("""
                        update account_totp
                           set state = 'active', pending_expires_at = null, activated_at = :now, last_accepted_step = :step,
                               activated_by_session = :session
                         where account_id = :id and state = 'pending' and secret_encrypted = :secret and pending_expires_at > :now
                        """)
                .param("now", Timestamp.from(now)).param("step", step).param("id", accountId)
                .param("secret", expectedSecretEncrypted).param("session", sessionHash).update() == 1;
    }

    /** True when the factor of this account was activated by the session with this hash. */
    public boolean wasActivatedBy(long accountId, byte[] sessionHash) {
        return jdbc.sql("select exists (select 1 from account_totp where account_id = :id and activated_by_session = :session)")
                .param("id", accountId).param("session", sessionHash).query(Boolean.class).single();
    }

    public void removeSecondFactor(long accountId) {
        jdbc.sql("delete from account_recovery_code where account_id = :id").param("id", accountId).update();
        jdbc.sql("delete from account_totp where account_id = :id").param("id", accountId).update();
    }

    public void replaceRecoveryCodes(long accountId, List<byte[]> hashes) {
        jdbc.sql("delete from account_recovery_code where account_id = :id").param("id", accountId).update();
        for (byte[] hash : hashes) {
            jdbc.sql("insert into account_recovery_code (account_id, code_hash) values (:id, :hash)")
                    .param("id", accountId).param("hash", hash).update();
        }
    }

    /** Uses a recovery code, once. */
    public boolean consumeRecoveryCode(long accountId, byte[] hash, Instant now) {
        return jdbc.sql("update account_recovery_code set used_at = :now where account_id = :id and code_hash = :hash and used_at is null")
                .param("now", Timestamp.from(now)).param("id", accountId).param("hash", hash).update() == 1;
    }

    public int recoveryCodesRemaining(long accountId) {
        return jdbc.sql("select count(*) from account_recovery_code where account_id = :id and used_at is null")
                .param("id", accountId).query(Integer.class).single();
    }

    // ------------------------------------------------------------------ one-time credentials

    /** Issues a credential and cancels every earlier unused one of the account. */
    public void issueCredential(long accountId, String purpose, byte[] hash, Instant now, Instant expiresAt) {
        cancelCredentials(accountId, now);
        jdbc.sql("""
                        insert into one_time_credential (credential_hash, account_id, purpose, created_at, expires_at)
                        values (:hash, :id, :purpose, :now, :expires)
                        """)
                .param("hash", hash).param("id", accountId).param("purpose", purpose)
                .param("now", Timestamp.from(now)).param("expires", Timestamp.from(expiresAt)).update();
    }

    public void cancelCredentials(long accountId, Instant now) {
        jdbc.sql("update one_time_credential set cancelled_at = :now where account_id = :id and used_at is null and cancelled_at is null")
                .param("now", Timestamp.from(now)).param("id", accountId).update();
    }

    /** Looks at a credential without using it. Empty when it is unknown, used, cancelled or expired. */
    public Optional<Redeemed> peekCredential(byte[] hash, Instant now) {
        return jdbc.sql("""
                        select account_id, purpose from one_time_credential
                         where credential_hash = :hash and used_at is null and cancelled_at is null and expires_at > :now
                        """)
                .param("hash", hash).param("now", Timestamp.from(now))
                .query((rs, row) -> new Redeemed(rs.getLong("account_id"), rs.getString("purpose"))).optional();
    }

    /** Uses a credential, once. Empty when it is unknown, used, cancelled or expired. */
    public Optional<Redeemed> redeemCredential(byte[] hash, Instant now) {
        return jdbc.sql("""
                        update one_time_credential set used_at = :now
                         where credential_hash = :hash and used_at is null and cancelled_at is null and expires_at > :now
                        returning account_id, purpose
                        """)
                .param("now", Timestamp.from(now)).param("hash", hash)
                .query((rs, row) -> new Redeemed(rs.getLong("account_id"), rs.getString("purpose"))).optional();
    }

    // ------------------------------------------------------------------ the guard

    /**
     * Locks the guard row until the transaction ends. Every transaction that can change the
     * set of usable super administrators calls this first, so they run one after another.
     */
    public Guard lockGuard() {
        return jdbc.sql("select bootstrapped, database_real from identity_guard for update")
                .query((rs, row) -> new Guard(rs.getBoolean("bootstrapped"), rs.getBoolean("database_real"))).single();
    }

    public Guard guard() {
        return jdbc.sql("select bootstrapped, database_real from identity_guard")
                .query((rs, row) -> new Guard(rs.getBoolean("bootstrapped"), rs.getBoolean("database_real"))).single();
    }

    public int usableSuperAdministrators() {
        return jdbc.sql("select count(*) from usable_super_administrator").query(Integer.class).single();
    }

    public void markBootstrapped() {
        jdbc.sql("update identity_guard set bootstrapped = true where not bootstrapped").update();
    }

    public void markDatabaseReal() {
        jdbc.sql("update identity_guard set database_real = true where not database_real").update();
    }

    /** True when this database was explicitly marked disposable when it was created. */
    public boolean disposableMarkerPresent() {
        return jdbc.sql("select exists (select 1 from information_schema.schemata where schema_name = 'apv_disposable_database')")
                .query(Boolean.class).single();
    }
}
