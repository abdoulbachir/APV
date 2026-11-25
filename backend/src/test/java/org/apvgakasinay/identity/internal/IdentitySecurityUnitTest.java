package org.apvgakasinay.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apvgakasinay.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Unit tests of the pieces that need no database: settings, hashing and authenticator codes. */
class IdentitySecurityUnitTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static MockEnvironment production() {
        return new MockEnvironment()
                .withProperty("apv.identity.mfa-encryption-key", KEY)
                .withProperty("apv.identity.hash-key", KEY);
    }

    private static MockEnvironment test() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return environment;
    }

    @Test
    void a3_10_productionDefaultsAreTheSecureHostCookieAndClosedSignUp() {
        IdentityProperties properties = new IdentityProperties(production());
        assertThat(properties.cookieName()).isEqualTo("__Host-apv_session");
        assertThat(properties.cookieSecure()).isTrue();
        assertThat(properties.registrationOpen()).isFalse();
        assertThat(properties.development()).isFalse();
    }

    @Test
    void a3_10_and_1_11_productionRefusesToStartWithDevelopmentConveniences() {
        assertThatThrownBy(() -> new IdentityProperties(production().withProperty("apv.identity.session-cookie.secure", "false")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure");
        assertThatThrownBy(() -> new IdentityProperties(production().withProperty("apv.identity.session-cookie.name", "apv_session")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("__Host-");
        assertThatThrownBy(() -> new IdentityProperties(production().withProperty("apv.identity.registration-open", "true")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sign-up");
        assertThatThrownBy(() -> new IdentityProperties(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APV_MFA_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new IdentityProperties(production().withProperty("apv.identity.hash-key", "c2hvcnQ=")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }

    @Test
    void a2_14_argon2ParametersCanBeRaisedNeverLowered() {
        assertThatThrownBy(() -> new IdentityProperties(test().withProperty("apv.identity.argon2.memory-kib", "8192")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("19456");
        assertThatThrownBy(() -> new IdentityProperties(test().withProperty("apv.identity.argon2.iterations", "1")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new IdentityProperties(test().withProperty("apv.identity.argon2.memory-kib", "32768")).argon2MemoryKib()).isEqualTo(32768);
    }

    @Test
    void a2_13_and_2_14_hashesAreArgon2idAndWeakerOnesAreUpgraded() {
        PasswordHasher hasher = new PasswordHasher(new IdentityProperties(test()));
        String hash = hasher.hash("a long harmless passphrase 2026");
        assertThat(hash).startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(hasher.matches("a long harmless passphrase 2026", hash)).isTrue();
        assertThat(hasher.matches("a long harmless passphrase 2027", hash)).isFalse();
        assertThat(hasher.needsUpgrade(hash)).isFalse();
        // With no stored hash the answer is always no, after the work of a real check.
        assertThat(hasher.matches("a long harmless passphrase 2026", null)).isFalse();

        // A hash made with stronger parameters than this server's is fine; one made with weaker is upgraded.
        PasswordHasher stronger = new PasswordHasher(new IdentityProperties(test().withProperty("apv.identity.argon2.memory-kib", "32768")));
        assertThat(stronger.matches("a long harmless passphrase 2026", hash)).isTrue();
        assertThat(stronger.needsUpgrade(hash)).isTrue();
        String weak = "{argon2id}" + new Argon2PasswordEncoder(16, 32, 1, 4096, 1).encode("a long harmless passphrase 2026");
        assertThat(hasher.matches("a long harmless passphrase 2026", weak)).isTrue();
        assertThat(hasher.needsUpgrade(weak)).isTrue();
    }

    @Test
    void a8_11_whenEveryHashSlotIsBusyARequestIsToldToComeBack() throws Exception {
        PasswordHasher hasher = new PasswordHasher(new IdentityProperties(test()
                .withProperty("apv.identity.hash-concurrency", "1")
                .withProperty("apv.identity.hash-wait-millis", "1")
                .withProperty("apv.identity.argon2.memory-kib", "65536")
                .withProperty("apv.identity.argon2.iterations", "4")));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> {
                start.await();
                try {
                    return hasher.hash("a long harmless passphrase 2026");
                } catch (ApiException refused) {
                    assertThat(refused.status().value()).isEqualTo(503);
                    assertThat(refused.headers()).containsEntry("Retry-After", "5");
                    return "refused";
                }
            });
        }
        List<Future<String>> results = new ArrayList<>();
        for (Callable<String> call : calls) {
            results.add(pool.submit(call));
        }
        start.countDown();
        int hashed = 0;
        int refused = 0;
        for (Future<String> result : results) {
            if (result.get().equals("refused")) {
                refused++;
            } else {
                hashed++;
            }
        }
        pool.shutdownNow();
        assertThat(hashed).isGreaterThanOrEqualTo(1);
        assertThat(refused).as("with one slot and eight simultaneous requests, some are refused").isGreaterThanOrEqualTo(1);
    }

    @Test
    void a8_11_hashingLimitsCanBeMadeStricterNeverLooser() {
        for (MockEnvironment environment : List.of(test(), production())) {
            assertThatThrownBy(() -> new IdentityProperties(environment.withProperty("apv.identity.hash-concurrency", "5")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("between 1 and 4");
        }
        assertThatThrownBy(() -> new IdentityProperties(production().withProperty("apv.identity.hash-concurrency", "0")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IdentityProperties(production().withProperty("apv.identity.hash-wait-millis", "2001")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("between 1 and 2000");
        assertThatThrownBy(() -> new IdentityProperties(production().withProperty("apv.identity.hash-wait-millis", "0")))
                .isInstanceOf(IllegalStateException.class);
        IdentityProperties defaults = new IdentityProperties(production());
        assertThat(defaults.hashConcurrency()).isEqualTo(4);
        assertThat(defaults.hashWaitMillis()).isEqualTo(2000);
        assertThat(new IdentityProperties(production().withProperty("apv.identity.hash-concurrency", "2")).hashConcurrency()).isEqualTo(2);
    }

    @Test
    void a13_7_developmentAccountsAreOnlyForADatabaseOnThisMachine() {
        String local = "localhost,127.0.0.1,[::1]";
        assertThat(DevAccountSeeder.onThisMachine("jdbc:postgresql://localhost:5432/apv", local)).isTrue();
        assertThat(DevAccountSeeder.onThisMachine("jdbc:postgresql://127.0.0.1/apv", local)).isTrue();
        assertThat(DevAccountSeeder.onThisMachine("jdbc:postgresql://[::1]:5432/apv", local)).isTrue();
        assertThat(DevAccountSeeder.onThisMachine("jdbc:postgresql://db.example.org:5432/apv", local)).isFalse();
        assertThat(DevAccountSeeder.onThisMachine("jdbc:postgresql://localhost.example.org/apv", local)).isFalse();
        assertThat(DevAccountSeeder.onThisMachine("jdbc:postgresql://localhost,db.example.org/apv", local)).isFalse();
        assertThat(DevAccountSeeder.onThisMachine("jdbc:postgresql://db.example.org/apv?host=localhost", local)).isFalse();
        assertThat(DevAccountSeeder.onThisMachine("", local)).isFalse();
        assertThat(DevAccountSeeder.onThisMachine(null, local)).isFalse();
        assertThat(DevAccountSeeder.onThisMachine("jdbc:postgresql://172.17.0.2:5432/apv", local + ",172.17.0.2")).isTrue();
    }

    /**
     * Scenario 2.17. Everything a password check does: the policy with its blocklist, hashing,
     * verification. Run in CI a second time inside a network namespace with no interface
     * (-Dapv.test.network-disabled=true), where the first assertion proves there is no network.
     */
    @Test
    void a2_17_passwordChecksNeedNoNetwork() throws Exception {
        if (Boolean.getBoolean("apv.test.network-disabled")) {
            assertThatThrownBy(() -> {
                try (java.net.Socket socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress("1.1.1.1", 443), 2000);
                }
            }).as("this run was supposed to have no outgoing network").isInstanceOf(java.io.IOException.class);
            java.nio.file.Path evidence = java.nio.file.Path.of("target", "identity-evidence");
            java.nio.file.Files.createDirectories(evidence);
            java.nio.file.Files.writeString(evidence.resolve("no-network.txt"),
                    "Password policy, blocklist, Argon2id hashing and verification ran with no outgoing network: a connection to 1.1.1.1:443 failed.\n");
        }
        IdentityProperties properties = new IdentityProperties(test());
        PasswordPolicy policy = new PasswordPolicy(properties);
        assertThatThrownBy(() -> policy.accept("password", "passwordpassword", List.of())).isInstanceOf(ApiException.class);
        String normalized = policy.accept("password", "a long harmless passphrase 2026", List.of("sample.donor", "Sample Person"));
        PasswordHasher hasher = new PasswordHasher(properties);
        String hash = hasher.hash(normalized);
        assertThat(hasher.matches(normalized, hash)).isTrue();
        assertThat(hasher.matches(normalized, null)).isFalse();
    }

    @Test
    void a5_4_thePermissionFunctionEqualsTheMatrix() {
        List<String> own = List.of("account.read_own", "account.change_password", "donations.read_own");
        List<String> staff = List.of("cases.manage", "case_updates.post", "donation_reports.read");
        List<String> owner = List.of("donations.confirm", "funds.transfer", "payment_methods.manage", "staff.manage",
                "contact_messages.read", "audit.read");
        assertThat(Permissions.planned(null, false)).isEmpty();
        assertThat(Permissions.planned("donor", false)).containsExactlyInAnyOrderElementsOf(own);
        List<String> admin = new ArrayList<>(own);
        admin.addAll(staff);
        assertThat(Permissions.planned("admin", false)).containsExactlyInAnyOrderElementsOf(admin);
        admin.add("disbursements.record");
        assertThat(Permissions.planned("admin", true)).containsExactlyInAnyOrderElementsOf(admin);
        admin.addAll(owner);
        assertThat(Permissions.planned("super_admin", false)).containsExactlyInAnyOrderElementsOf(admin);
        // What is returned today is the same two for every role, and nothing for a visitor.
        for (String role : List.of("donor", "admin", "super_admin")) {
            assertThat(Permissions.operational(role, true)).containsExactly("account.read_own", "account.change_password");
        }
        assertThat(Permissions.operational(null, false)).isEmpty();
    }

    /** RFC 6238, appendix B, SHA-1 rows, truncated to six digits. */
    @Test
    void a6_9_authenticatorCodesMatchTheRfcTestVectors() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(1111111109L)))).isEqualTo("081804");
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(1111111111L)))).isEqualTo("050471");
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(1234567890L)))).isEqualTo("005924");
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(2000000000L)))).isEqualTo("279037");
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(20000000000L)))).isEqualTo("353130");
    }

    @Test
    void a6_9_aCodeMatchesOnlyWithinOneStepEitherSide() {
        byte[] secret = Keys.totpSecret();
        Instant now = Instant.parse("2026-11-24T12:00:00Z");
        long step = Totp.step(now);
        for (long offset = -1; offset <= 1; offset++) {
            assertThat(Totp.matchingStep(secret, Totp.code(secret, step + offset), now)).isEqualTo(step + offset);
        }
    }

    @Test
    void a6_18_encryptionIsAuthenticatedAndNeverRepeats() {
        Keys keys = new Keys(new IdentityProperties(test()));
        byte[] secret = Keys.totpSecret();
        byte[] one = keys.encrypt(secret);
        byte[] two = keys.encrypt(secret);
        assertThat(one).isNotEqualTo(two);
        assertThat(keys.decrypt(one)).isEqualTo(secret);
        one[one.length - 1] ^= 1;
        assertThatThrownBy(() -> keys.decrypt(one)).isInstanceOf(RuntimeException.class);
        assertThat(Keys.recoveryCode()).matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}|[A-Z0-9-]{10,}");
        assertThat(Keys.oneTimeCredential()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Keys.accountPublicId()).matches("acc_[A-Za-z0-9]{22}");
    }
}
