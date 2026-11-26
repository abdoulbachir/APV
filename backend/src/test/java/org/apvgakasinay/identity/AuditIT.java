package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.apvgakasinay.identity.internal.StaffAdministration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** Security acceptance group 12, and 9.8 for audit rows and stored data: what is recorded, and what never is. */
class AuditIT extends IdentityIntegration {

    private long events(String type, String outcome) {
        return jdbc.sql("select count(*) from audit_event where event_type = :type and outcome = :outcome")
                .param("type", type).param("outcome", outcome).query(Long.class).single();
    }

    @Test
    void a12_1_and_9_8_everyEventIsRecordedOnceAndNoSecretIsWrittenAnywhere() {
        List<String> secrets = new ArrayList<>(List.of(PASSWORD, OTHER_PASSWORD));

        // A donor: sign-up, failed sign-in, sign-in, state conflict, password change, sign-out.
        Browser donor = browser();
        register(donor, "sample.donor@example.org", PASSWORD);
        donor.login("sample.donor@example.org", "not the right one at all");
        donor.login("sample.donor@example.org", PASSWORD);
        secrets.add(donor.token);
        secrets.add(donor.sessionId);
        secrets.add(donor.storedSessionId());
        donor.post("/api/auth/login", "{}");
        donor.post("/api/auth/change-password", json("currentPassword", "not the right one at all", "newPassword", OTHER_PASSWORD));
        donor.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD));
        donor.csrf();
        secrets.add(donor.token);
        secrets.add(donor.storedSessionId());
        donor.postEmpty("/api/auth/logout");

        // Staff: created, credential redeemed, enrolled, verified with a wrong and a right code, recovery codes replaced.
        String activation = createStaff("sample.owner@example.org", "super_admin");
        secrets.add(activation);
        Browser owner = browser();
        owner.csrf();
        owner.post("/api/auth/set-password", json("credential", activation, "newPassword", PASSWORD));
        owner.post("/api/auth/set-password", json("credential", activation, "newPassword", PASSWORD));
        owner.login("sample.owner@example.org", PASSWORD);
        String shown = owner.postEmpty("/api/auth/mfa/enroll").json().get("secret").asText();
        byte[] secret = base32Decode(shown);
        secrets.add(shown);
        String firstCode = code(secret);
        secrets.add(firstCode);
        Browser.Answer confirmed = owner.post("/api/auth/mfa/confirm", json("code", firstCode));
        confirmed.json().get("codes").forEach(node -> secrets.add(node.asText()));
        String recovery = confirmed.json().get("codes").get(0).asText();
        nextCode();
        Staff staff = new Staff("sample.owner@example.org", owner, secret, List.of());
        Browser second = pending(staff);
        second.post("/api/auth/mfa/verify", json("method", "totp", "code", "000000"));
        second.post("/api/auth/mfa/verify", json("method", "recovery_code", "code", recovery));
        second.csrf();
        String replaceCode = code(secret);
        secrets.add(replaceCode);
        Browser.Answer replaced = second.post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", replaceCode));
        assertThat(replaced.status()).as(replaced.body()).isEqualTo(200);
        replaced.json().get("codes").forEach(node -> secrets.add(node.asText()));

        // Operator actions, one of them refused.
        String reset = staffAdministration.issuePasswordReset("sample.donor@example.org", "test-operator", "in person");
        secrets.add(reset);
        staffAdministration.setEnabled("sample.donor@example.org", false, "test-operator");
        staffAdministration.setEnabled("sample.donor@example.org", true, "test-operator");
        assertThatThrownBy(() -> staffAdministration.setEnabled("sample.owner@example.org", false, "test-operator")).isInstanceOf(StaffAdministration.Refused.class);
        // A session ended by the security-version check.
        jdbc.sql("update account set security_version = security_version + 1 where role = 'super_admin'").update();
        second.get("/api/me");

        // 12.1: exactly one record for each thing that happened.
        assertThat(events("account.sign_up", "success")).isEqualTo(1);
        assertThat(events("session.sign_in", "failure")).isEqualTo(1);
        assertThat(events("session.sign_in", "success")).isEqualTo(3);
        assertThat(events("session.state_conflict", "refused")).isEqualTo(1);
        assertThat(events("account.password_change", "failure")).isEqualTo(1);
        assertThat(events("account.password_change", "success")).isEqualTo(1);
        assertThat(events("session.sign_out", "success")).isEqualTo(1);
        assertThat(events("account.created", "success")).isEqualTo(1);
        assertThat(events("credential.redeem", "success")).isEqualTo(1);
        assertThat(events("credential.redeem", "refused")).isEqualTo(1);
        assertThat(events("mfa.enroll", "success")).isEqualTo(1);
        assertThat(events("mfa.enroll_confirm", "success")).isEqualTo(1);
        assertThat(events("site.bootstrapped", "success")).isEqualTo(1);
        assertThat(events("session.second_factor", "failure")).isEqualTo(1);
        assertThat(events("session.second_factor", "success")).isEqualTo(1);
        assertThat(events("mfa.recovery_codes", "success")).isEqualTo(1);
        assertThat(events("credential.issued", "success")).isEqualTo(1);
        assertThat(events("account.disabled", "success")).isEqualTo(1);
        assertThat(events("account.enabled", "success")).isEqualTo(1);
        assertThat(events("account.last_super_administrator_refused", "refused")).isEqualTo(1);
        assertThat(events("session.revoked", "refused")).isEqualTo(1);
        // Request events carry the client address and request identifier; operator events the operator.
        assertThat(count("select count(*) from audit_event where actor_operator is null and (client_address is null or request_id is null)")).isZero();
        assertThat(count("select count(*) from audit_event where actor_operator = 'test-operator'")).isEqualTo(5);
        assertThat(jdbc.sql("select details ->> 'method' from audit_event where event_type = 'session.second_factor' and outcome = 'success'")
                .query(String.class).single()).isEqualTo("recovery_code");

        // 9.8: no secret used above is in the audit log, in a session row, or in any identity table.
        // Everything a record says, without its timestamp and request identifier (digits that could match a code by chance).
        String audit = jdbc.sql("""
                select string_agg(concat_ws(' ', event_type, outcome, actor_operator, client_address, details::text), ' ') from audit_event
                """).query(String.class).single();
        String stored = audit
                + jdbc.sql("select coalesce(string_agg(row_to_json(a)::text, ' '), '') from account a").query(String.class).single()
                + jdbc.sql("select coalesce(string_agg(password_hash, ' '), '') from account_password").query(String.class).single()
                + jdbc.sql("select coalesce(string_agg(encode(secret_encrypted, 'escape'), ' '), '') from account_totp").query(String.class).single()
                + jdbc.sql("select coalesce(string_agg(encode(code_hash, 'escape'), ' '), '') from account_recovery_code").query(String.class).single()
                + jdbc.sql("select coalesce(string_agg(encode(credential_hash, 'escape'), ' '), '') from one_time_credential").query(String.class).single()
                + jdbc.sql("select coalesce(string_agg(encode(key_hash, 'escape'), ' '), '') from auth_throttle").query(String.class).single();
        for (String secretValue : secrets) {
            assertThat(audit).as("audit log").doesNotContain(secretValue);
            // Session identifiers are, of course, in the session table; nothing else is anywhere.
            if (!secretValue.matches("[0-9]{6}")) {
                assertThat(stored).doesNotContain(secretValue);
            }
        }
    }

    @Test
    void a12_2_theAuditLogIsAppendOnly() {
        register(browser(), "sample.donor@example.org", PASSWORD);
        assertThat(count("select count(*) from audit_event")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("update audit_event set outcome = 'failure'").update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from audit_event").update()).isInstanceOf(DataAccessException.class);
        assertThat(count("select count(*) from audit_event where outcome = 'success'")).isEqualTo(1);
    }

    @Test
    void a12_3_aFailedSignInForAnUnknownAddressIsRecordedWithoutTheAddress() {
        assertError(browser().login("who.is.this@example.org", PASSWORD), 401, "invalid_credentials");
        String row = jdbc.sql("select row_to_json(a)::text from audit_event a where event_type = 'session.sign_in'").query(String.class).single();
        assertThat(row).contains("\"outcome\":\"failure\"").contains("email_hash").doesNotContain("who.is.this").doesNotContain("example.org");
        assertThat(jdbc.sql("select details ->> 'email_hash' from audit_event").query(String.class).single()).matches("[0-9a-f]{64}");
        assertThat(count("select count(*) from audit_event where actor_account_id is not null")).isZero();
    }
}
