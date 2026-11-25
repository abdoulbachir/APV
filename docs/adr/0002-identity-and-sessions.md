# ADR 0002: Identity, sessions and request security

- Status: accepted as the M1A design baseline on 2026-11-25, following independent acceptance at `b5ea149fb3e33380dd622ad734117a15051b4bfd` and the owner's approval of the seven policy decisions in `docs/decisions.md`. Implementation remains subject to the four compatibility probes below and M1B runtime acceptance.
- Date: 2026-11-24
- Scope: how sign-in, sessions, the second factor and request protection are built on the architecture approved in ADR 0001. It changes none of ADR 0001.

The rules themselves are in `contract/identity-and-security.md`. This record holds the technical choices, the compatibility evidence and the alternatives.

## Decision

| Concern | Choice |
|---|---|
| Framework | Spring Security, the version Spring Boot manages |
| Sessions | Spring Session JDBC on the existing PostgreSQL |
| Session schema | The tables Spring Session documents for PostgreSQL, created by a Flyway migration. Automatic schema initialization is switched off. |
| Password hashing | Argon2id through `Argon2PasswordEncoder`, wrapped in `DelegatingPasswordEncoder` so hashes can be upgraded |
| CSRF | Spring Security's CSRF protection with the token stored in the server-side session (its default `HttpSessionCsrfTokenRepository`), served to the page by `GET /api/auth/csrf`. Not the cookie-based variant. |
| Anonymous sessions | Created only by `GET /api/auth/csrf`, to hold the token; short-lived and rate-limited |
| Super administrator invariant | One guard row locked by every transaction that can change the set of usable super administrators, plus a commit-time constraint trigger as a backstop |
| Second factor | Spring Security's multi-factor support for the rules, with a small RFC 6238 verifier written on the JDK's `javax.crypto` |
| Secret encryption | AES-256-GCM from the JDK, key supplied by the environment |
| Rate limiting | A table in PostgreSQL, updated with `insert … on conflict do update` |
| Staff provisioning | An operator mode of the same application, run on the server |
| New modules | `identity` (accounts, credentials, sessions, second factor, operator commands) and `audit` (append-only events). Both were already named in ADR 0001. |

## Compatibility

No dependency version changes. Everything is what Spring Boot 4.1.3, already in use, manages.

| Component | Version | Evidence |
|---|---|---|
| Spring Boot | 4.1.3 | In use since milestone 0B |
| Spring Security | 7.1.3 | `spring-security.version` in the Spring Boot 4.1.3 dependency list |
| Spring Session | 4.1.2 | `spring-session.version` in the same list |
| Starters | `spring-boot-starter-security`, `spring-boot-starter-session-jdbc`, and their `-test` companions | Present in the same list |
| Bouncy Castle | 1.85, not managed by Spring Boot | Required by `Argon2PasswordEncoder`. `org.bouncycastle:bcprov-jdk18on:1.85`, the version Spring Security 7.1.3 is built against; verified by probe 4. |

Checked on 2026-11-24 in the official documentation:

- CSRF protection, the session-stored token repository (the default) and the cookie-based alternative: https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html
- Argon2 support and its Bouncy Castle requirement; `DelegatingPasswordEncoder`: https://docs.spring.io/spring-security/reference/7.1/features/authentication/password-storage.html
- Multi-factor support (`@EnableMultiFactorAuthentication`, factor authorities): https://docs.spring.io/spring-security/reference/7.1/servlet/authentication/mfa.html
- Spring Session JDBC tables for PostgreSQL: https://docs.spring.io/spring-session/reference/configuration/jdbc.html
- Session cookie customization and lookup of sessions by account: https://docs.spring.io/spring-session/reference/api.html
- Argon2id minimum parameters: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- Password length rules (minimum 15 for a single factor, at least 64 allowed, no composition rules, no periodic change, blocklist): https://pages.nist.gov/800-63-4/sp800-63b.html
- Time-based one-time passwords: RFC 6238. `__Host-` cookie prefix: RFC 6265bis.

## Compatibility probes

The four points that were "not verified by running" have been run. The probes are executable tests in `backend/probes/`, a separate throwaway project on the same Spring Boot parent as the backend. Evidence: commit `a1b7f000a966e6c5dd69ff436500cd68e87b25c9`, [Backend checks run 45548617064](https://github.com/abdoulbachir/APV/actions/runs/45548617064), 59 probe tests, 0 failures, with PostgreSQL 18.7 started by Testcontainers. The accepted backend's 114 tests passed in the same run.

This is framework evidence, not runtime acceptance of identity: the probe application has in-memory accounts, its own paths, no rate limits, no audit and no migrations.

| # | Question | Result | How it was shown |
|---|---|---|---|
| 1 | Can a custom time-based-code factor take part in Spring Security's multi-factor model? | **Pass.** No fallback needed. | The password step grants the framework's `FACTOR_PASSWORD` authority, the custom code check grants `FACTOR_TOTP` (`FactorGrantedAuthority.fromFactor("TOTP")`), and `AuthorizationManagerFactories.multiFactor().when(isStaff).requireFactors(…)` makes "authenticated" mean both for staff and the password alone for donors. A password-only staff session is refused on every fully signed-in operation, by the framework's own authorization manager. |
| 2 | Per-role idle timeout, fixed absolute deadline, pending expiry, and refusal of expired sessions before cleanup, on Spring Session JDBC? | **Pass.** | Each `SPRING_SESSION` row carries its own `MAX_INACTIVE_INTERVAL` (1800 anonymous, 600 pending, 7200 donor, 1800 staff). An idle-expired row is refused while it is still in the table, with cleanup switched off. The absolute deadline is a session attribute checked by a filter before the CSRF check; activity moves the idle clock in the row and never the deadline. |
| 3 | Is the session-stored CSRF token replaced at every session transition? | **Pass.** | At password sign-in, second-factor verification, enrollment confirmation and password change: the session identifier and the token are both replaced; the old token on the new session, and any token with the old identifier, answer 403 `csrf_invalid`; a `GET` with the old identifier is 401; a fresh token works. A protected `POST` with a fresh anonymous token is 401, and sign-in with it works. |
| 4 | Which Bouncy Castle version, and does Argon2id behave as accepted? | **Pass.** `org.bouncycastle:bcprov-jdk18on:1.85`. | 1.85 is the version Spring Security 7.1.3 itself is built against (`gradle/libs.versions.toml` at tag 7.1.3). Hashes are `$argon2id$v=19$m=19456,t=2,p=1$…` with a 16-byte salt and 32-byte output; 128-code-point Unicode passwords are never cut; NFC is applied by the application, not the encoder; hashes upgrade when parameters rise. |

### What the implementation must take from the probes

- **Transitions are explicit.** The contract's sign-in, second-factor and password-change operations are JSON endpoints, not the framework's login filter, so the framework does not rotate anything by itself. One component must apply `ChangeSessionIdAuthenticationStrategy` and then `CsrfAuthenticationStrategy`, set the session's own idle timeout, and save the security context. The probe's `SessionTransitions` is that component in miniature.
- **The absolute deadline is ours.** Spring Session has only an idle timeout. The deadline is a session attribute enforced by a filter placed before the CSRF filter, which ends the session; from then on the request is handled as one with no session. That ordering is what gives 403 `csrf_invalid` on a `POST` and 401 on a `GET`, as the contract requires.
- **Idle expiry is exact.** Spring Session JDBC refuses a row whose idle time has passed when it loads it, independently of the cleanup job.
- **Sessions are found by account.** `findByPrincipalName` returns all sessions of an account, so deleting them on revocation works as the contract's first mechanism assumes. The principal name stored in the row must therefore be the stable account identifier, not something that can change.
- **Session content is serialized.** The authentication, including the factor authorities with their issue instants, is stored in the row with Java serialization and read back correctly. Classes stored there must stay serializable and compatible across deployments, or sessions must be discarded on upgrade.
- **The pending state needs no extra flag.** "Staff with the password factor and without the code factor" is the pending state; the framework's access-denied handling is where the contract's 401 `mfa_required` is produced instead of a 403.
- **Spring Session creates its tables in the probe.** The implementation creates the same tables in a Flyway migration and switches automatic initialization off.

### Argon2id timings

Median of nine single-threaded verifications after one warm-up.

| Parameters | CI runner (4 processors, 4 GiB heap) | Development machine (24 processors, 8 GiB heap) |
|---|---|---|
| 19 MiB, 2 iterations (the accepted floor) | 24 ms | 18 ms |
| 46 MiB, 1 iteration | 39 ms | 38 ms |
| 46 MiB, 2 iterations | 68 ms | 51 ms |
| 64 MiB, 3 iterations | 142 ms | 108 ms |

Limits of these numbers: both machines are faster than a small server is likely to be; a shared CI runner is noisy; the measurement is single-threaded, while the design allows four hashes at once; and it is the Java implementation in Bouncy Castle, not native code. The production value must be measured on the production machine. What the numbers do show is that the accepted floor is far below the 0.25 to 0.5 second target on this class of hardware, so production will most likely run well above the floor, and the memory for four simultaneous hashes (four times the chosen memory cost) must be budgeted when the server is sized.

### Still open after the probes

- The probes used single-instance, in-process state for accounts and for the "last accepted time step". The database versions of those (conditional updates) are implementation work and are covered by the acceptance matrix, not by these probes.
- Rate limiting, the origin and fetch-metadata check, the security-version check and the super administrator guard were not probed; none was listed as a framework risk.
- Bouncy Castle 1.85 is the version Spring Security 7.1.3 is built against. Newer releases exist (1.85.2, 1.86, 1.87). I did not review their release notes or advisories; that review belongs with the dependency pin in the implementation.
- Java-serialized session attributes are a compatibility commitment across upgrades.

The API contract does not depend on any of these.

## Alternatives considered

| Alternative | Why not |
|---|---|
| JWT in browser storage | Readable by any injected script, hard to revoke. Rejected in the assignment and in ADR 0001. |
| CSRF token in a script-readable cookie (double submit, `csrf.spa()`) | First proposed, then dropped after review. A cookie-held token is not bound to the session: anything able to set a cookie for the site could supply its own pair. The session-bound token costs one short-lived session row per visitor who opens a form, which is bounded by expiry and rate limits. |
| A commit-time trigger alone for the last super administrator | Two transactions removing different administrators each see the other still present. A shared lock taken first is required. |
| Serializable isolation with retries for those writes | Correct, but every such write path would need retry logic. One guard row is simpler and these writes are rare. |
| NFKC password normalization | Makes visibly different characters equal. NFC is what the cited NIST revision asks for stability across devices. |
| A roles-and-permissions schema | Three fixed roles and one flag do not need it. The API exposes `role` and `permissions`, so it can be introduced later without a contract change. |
| An external identity provider | Not required, adds an account and a dependency for a site run by one person. |
| SMS or email codes as second factor | SMS is weak and costs money; the site sends no email yet. |
| Passkeys | Stronger than time-based codes, and worth revisiting. More frontend work and recovery design than milestone 1 allows. |
| A library for time-based codes | The algorithm is about thirty lines on the JDK and has published test vectors; a dependency would add more risk than it removes. |
| Redis for sessions or rate limits | PostgreSQL is enough at this size. ADR 0001 excludes it without a measured need. |
| bcrypt | Supported, but OWASP recommends Argon2id first, and bcrypt silently ignores input past 72 bytes, which conflicts with "never cut a password". |

## Consequences

- Every authenticated request costs one extra primary-key read of the account, which is what makes revocation immediate.
- Password hashing is deliberately slow and memory-hungry; concurrency is capped and sign-in is rate-limited.
- The key that encrypts authenticator secrets, and the keys for hashing recovery codes and rate-limit counters, are new secrets to manage and back up separately from the database. Losing the encryption key means every staff member must enroll again.
- Until the site can send email, password recovery and staff activation depend on the owner, and public sign-up is proposed to stay closed in production.
- Every visitor who opens a form that posts gets a short-lived session row. Expiry, cleanup and the rate limit on the token endpoint bound this.
- All changes to who the super administrators are run one at a time. They are rare, so this costs nothing in practice.
