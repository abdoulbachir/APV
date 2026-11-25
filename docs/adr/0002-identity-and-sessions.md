# ADR 0002: Identity, sessions and request security

- Status: proposed for milestone 1B. Revised after the independent review of pull request 8. Awaiting the project owner's decisions.
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
| Bouncy Castle | Not managed by Spring Boot | Required by `Argon2PasswordEncoder`. One new dependency (`org.bouncycastle:bcprov-jdk18on`), to be pinned to an exact version in milestone 1B. |

Checked on 2026-11-24 in the official documentation:

- CSRF protection, the session-stored token repository (the default) and the cookie-based alternative: https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html
- Argon2 support and its Bouncy Castle requirement; `DelegatingPasswordEncoder`: https://docs.spring.io/spring-security/reference/7.1/features/authentication/password-storage.html
- Multi-factor support (`@EnableMultiFactorAuthentication`, factor authorities): https://docs.spring.io/spring-security/reference/7.1/servlet/authentication/mfa.html
- Spring Session JDBC tables for PostgreSQL: https://docs.spring.io/spring-session/reference/configuration/jdbc.html
- Session cookie customization and lookup of sessions by account: https://docs.spring.io/spring-session/reference/api.html
- Argon2id minimum parameters: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- Password length rules (minimum 15 for a single factor, at least 64 allowed, no composition rules, no periodic change, blocklist): https://pages.nist.gov/800-63-4/sp800-63b.html
- Time-based one-time passwords: RFC 6238. `__Host-` cookie prefix: RFC 6265bis.

Not verified by running, to be confirmed at the start of milestone 1B before any design depends on them:

- That Spring Security's multi-factor support can hold a custom time-based-code factor the way this design assumes. The documentation I read describes passwords and one-time tokens; I found no built-in time-based-code verifier. If the fit is poor, the fallback is a session attribute for the state, enforced by one authorization rule, with the same contract.
- That a per-session idle timeout by role and a fixed absolute deadline work as intended with Spring Session JDBC 4.1.2.
- That the session-stored CSRF token is replaced at each session-identifier change in the way section 9.1 of the contract requires, including for the two second-factor transitions that are custom code.
- The exact Bouncy Castle version to pin.

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
