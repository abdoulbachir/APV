package org.apvgakasinay.identity.internal;

import java.time.Instant;
import java.util.Locale;
import org.apvgakasinay.identity.DatabasePurpose;
import org.apvgakasinay.shared.AppClock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Development only: fictional sign-ins for local work, created at start-up with freshly
 * generated passwords printed to the developer's console. Nothing here is committed, and none
 * of it exists outside the dev profile or runs on a database that is not marked disposable.
 */
@Component
@Profile("dev")
@Order(10)
class DevAccountSeeder implements ApplicationRunner {

    private final AccountRepository accounts;
    private final PasswordHasher hasher;
    private final Keys keys;
    private final DatabasePurpose purpose;
    private final AppClock clock;

    private final String databaseUrl;
    private final String allowedHosts;

    DevAccountSeeder(AccountRepository accounts, PasswordHasher hasher, Keys keys, DatabasePurpose purpose, AppClock clock,
                     @org.springframework.beans.factory.annotation.Value("${spring.datasource.url:}") String databaseUrl,
                     @org.springframework.beans.factory.annotation.Value("${apv.fixtures.allowed-hosts:localhost,127.0.0.1,[::1]}") String allowedHosts) {
        this.databaseUrl = databaseUrl;
        this.allowedHosts = allowedHosts;
        this.accounts = accounts;
        this.hasher = hasher;
        this.keys = keys;
        this.purpose = purpose;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!onThisMachine(databaseUrl, allowedHosts)) {
            System.out.println("DEVELOPMENT ACCOUNTS: not created. The database is not on this machine.");
            return;
        }
        if (!purpose.disposable()) {
            System.out.println("DEVELOPMENT ACCOUNTS: not created. This database is not marked disposable.");
            return;
        }
        System.out.println();
        System.out.println("DEVELOPMENT ACCOUNTS (fictional, this start-up only; passwords are regenerated at every start):");
        seed("dev.donor@example.test", "Dev Donor", "donor");
        seed("dev.admin@example.test", "Dev Admin", "admin");
        seed("dev.owner@example.test", "Dev Owner", "super_admin");
        System.out.println();
    }

    /**
     * The same rule the fixture loader applies: exactly one host, and it is this machine or a
     * host listed explicitly (a container network in tests). Anything else is not a development database.
     */
    static boolean onThisMachine(String jdbcUrl, String allowedHosts) {
        java.util.regex.Matcher url = java.util.regex.Pattern
                .compile("^jdbc:postgresql://(\\[[^\\]]+\\]|[^:/?,]+)(:\\d+)?/.*$").matcher(jdbcUrl == null ? "" : jdbcUrl);
        if (!url.matches()) {
            return false;
        }
        String host = url.group(1).toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(allowedHosts.split(",")).map(allowed -> allowed.trim().toLowerCase(Locale.ROOT))
                .anyMatch(allowed -> !allowed.isEmpty() && allowed.equals(host));
    }

    private void seed(String email, String displayName, String role) {
        Instant now = clock.now();
        String password = "dev-" + Keys.oneTimeCredential().substring(0, 24);
        Account account = accounts.findByNormalizedEmail(email.toLowerCase(Locale.ROOT)).orElse(null);
        long id = account != null ? account.id()
                : accounts.insertIfAbsent(Keys.accountPublicId(), email, email, displayName, "en", role, true, now).orElseThrow();
        accounts.setPassword(id, hasher.hash(PasswordPolicy.normalize(password)), now);
        accounts.raiseSecurityVersion(id);
        String line = "  " + role + "  " + email + "  password: " + password;
        if (!role.equals("donor")) {
            // Staff get an active second factor, so the whole sign-in can be exercised.
            byte[] secret = Keys.totpSecret();
            accounts.removeSecondFactor(id);
            accounts.storePendingTotp(id, keys.encrypt(secret), now.plusSeconds(600));
            accounts.activatePendingTotp(id, accounts.totp(id).orElseThrow().secretEncrypted(), -1, now, null);
            line += "  authenticator secret: " + Keys.base32(secret);
        }
        System.out.println(line);
    }
}
