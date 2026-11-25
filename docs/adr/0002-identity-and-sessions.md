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

## Implementation notes (milestone 1B, phase 2)

What the production implementation does where the contract left room, and what it found.

- **Nothing from the probes is reused.** The probe project stays in `backend/probes`, with its own build; the production classpath has none of its classes. Accounts, replay protection and counters are PostgreSQL tables.
- **Bouncy Castle is pinned to `bcprov-jdk18on` 1.85**, the version Spring Security 7.1.3 is built and tested against. Reviewed before pinning: the Bouncy Castle release notes for 1.85 and the advisories published for `bcprov` in the GitHub advisory database. Every published advisory is fixed in 1.85 or earlier; none is open against 1.85 at the time of writing. Only Argon2 is used from it. To be re-checked at each dependency update.
- **Where each rate limit is counted: the exact precedence.** Step 5 counts the limits keyed by client address, because the address is all that is known before the session and the body are looked at: sign-in (30 in 15 minutes), sign-up, one-time credential, token and options, and the requests to the two operations of a signed-in session that check a password (see the next point). A request refused at steps 1 to 4 counts toward nothing. A request refused at step 6 or 7 has counted toward its address limit and nothing else. The limits keyed by what the body names cannot be evaluated at step 5, since the body is validated at step 7: the email-and-address pair and the per-account password counter, and the per-account second-factor counter, are counted at the start of step 8, each immediately before its secret is checked and never after, and a request over one of them is answered 429 there. So "429 comes from step 5" is true for address limits only; a 429 for an account or a pair is produced inside the operation, after state and validation. The password or code is in no case checked once a counter has refused.
- **A wrong code at enrollment confirmation counts among the five** of the pending session, like a wrong code at verification. Section 11 of the contract names both steps; section 6 only words it for verification.
- **A session is bound to the identifier it was issued under.** Spring Session writes the identifier of a session back when a request ends. A slow request that began before a rotation could in principle restore the old identifier. Each privileged session therefore stores the identifier it was created with, and a session whose current identifier differs is ended at the state check. Found by reasoning about the concurrent confirmation scenario (6.5), not by a failing test.
- **Who won a simultaneous enrollment confirmation is recorded, not inferred.** The row of the activated factor keeps a hash of the pending session identifier that activated it. A losing request on the same session is answered 409 `already_signed_in`; one on another pending session of the account is answered 401 `mfa_code_invalid`.
- **Database connections.** Password hashing takes about 50 ms and four may run at once. No request holds a database connection while it hashes, and counters and audit records are written outside the main transaction, so a flood of sign-ins cannot exhaust the connection pool.
- **Two keys, not three.** `APV_MFA_ENCRYPTION_KEY` encrypts authenticator secrets. `APV_HASH_KEY` is the root of two derived keys (HMAC with a fixed label each): one for recovery-code hashes, one for rate-limit and audit key hashes. The contract speaks of server-side keys without fixing their number.
- **The disposable marker is a schema**, `apv_disposable_database`, rather than a row: a row would have to live in a table created by a migration, and the rule is that no migration and no application code can produce the marker.
- **The built-in password blocklist is small** (the most common passwords and site-specific ones). A full breached-password list is supplied as a local file through `APV_PASSWORD_BLOCKLIST_PATH`; nothing is fetched from the network.
- **Argon2id cost** is the OWASP minimum (19 MiB, 2 iterations, 1 lane) by default and can only be raised. Calibrating it on the production machine is a deployment task.
- **The framework error page** (`/error`) is not a route of the API: requested directly it answers 404.
- **An encoded percent sign in a path** is let through the HTTP firewall so that a malformed case code keeps answering 404 as accepted in milestone 0B. All other firewall rules are the defaults.

Not done in this phase, on purpose: any email; any staff administration over HTTP.

## Corrective review of the implementation (milestone 1B)

Changes made after the coordinator's source review of `f36ca68`, and the questions the contract leaves open.

- **The five wrong codes of a pending sign-in are counted in PostgreSQL** (`pending_sign_in`, migration V4), not in a session attribute. Every request that checks a code of a pending sign-in locks its row first, so wrong codes are counted exactly on any number of instances, a correct code is never accepted after the fifth wrong one, and a second request of a sign-in that was just completed is answered 409. The request filter also treats a pending session whose row says exhausted or expired as no session.
- **A replaced session identifier cannot come back.** Spring Session writes a session's identifier at the end of each request; a request that began before a rotation would write the old one back. A trigger on `spring_session` records each replaced identifier and discards, whole, any later write that tries to restore one. The identifier binding added earlier stays as a second line.
- **Lock order, everywhere:** pending sign-in, then the guard, then the account. Operator changes take the guard and the account and never a pending row.
- **Every operation that grants or writes re-reads the account under a row lock** and compares its security version with the one the request started from; where a password was verified first, also that the stored hash is still the one verified. This covers sign-in (including the upgrade of an old hash and the closing of an enrollment window, which are now conditional and inside that transaction), password change, verification, enrollment, confirmation and recovery-code regeneration. Hashing still happens outside any transaction. In regeneration the authenticator code is consumed and the codes are replaced in one transaction.
- **A sign-in that fails still ends a pending sign-in**, as sign-up and set-password already did: once the request is valid the operation proceeds as from anonymous. A request refused earlier (address limit at step 5, validation at step 7) did not proceed and leaves the pending sign-in alone.
- **One password counter per account**, keyed by the normalized address in sign-in, password change and recovery-code regeneration alike, so the three draw on one budget and an address with no account has a counter like any other.
- **Cooldown transitions are serialized per key** with a transaction-level advisory lock taken before the marker is read, so requests arriving together at the boundary start exactly one cooldown.
- **Hashes run on a pool of at most four ordinary threads, not on the request's thread.** Found by the HTTP saturation test. Requests run on virtual threads; a hash computed on one holds a carrier thread until it finishes, and with every carrier hashing nothing else in the server ran, including the timeout of requests waiting for a slot: forty simultaneous sign-ins were all served, the last after twenty seconds, none answered 503. Now the request thread waits while a pool thread hashes, the rest of the server keeps running, and the wait is also checked against the clock. Measured after the change: 4 checked, 36 answered 503 with `Retry-After: 5`, peak of 4 simultaneous hashes, 2.6 seconds in all.
- **Response objects that carry a secret do not print it.** Found by the log inspection: at DEBUG the web framework logs the object it is about to write, and the records for the enrollment secret and the recovery codes printed their fields. Their text form is now fixed. Production logs at INFO, where this line is not written at all.
- **Hashing limits are bounds, not defaults:** start-up is refused above four simultaneous hashes or a wait above two seconds, in every profile.

### Final targeted corrections (owner decisions of 2026-11-25)

- **A comprehensive local breached-password list is mandatory outside dev and test.** The server and the operator command refuse to start when it is missing, unreadable or unsuitable. The list is a file of sorted SHA-1 hashes searched on disk, with a manifest carrying its size and SHA-256; "unsuitable" is defined in `docs/backend-password-blocklist.md`, including a fixed minimum of 100,000,000 entries that configuration cannot lower. The earlier optional plain-text extra list is gone: one setting, one format. The selected source is Have I Been Pwned's Pwned Passwords. No breach data is in the repository.
- **No session identifier is logged, retired ones included, at any level.** The identifier that the log inspection had found at DEBUG came from Spring Security's session fixation strategy ("Changed session id from ..."). Looking further, at TRACE the embedded server prints raw requests and cookies, and the SQL parameter logger and the database driver print bound values, which include session identifiers and password hashes. A filter installed with the logging system drops those loggers below INFO whatever their configured level and replaces identifier shapes and Argon2 hashes in every other message. The log inspection now runs at TRACE with no exemption.
- **The client-address budget for password change and recovery-code regeneration is confirmed** as implemented: 30 requests in 15 minutes, shared by the two operations, separate from sign-in, counted at step 5.

Questions 1 and 5 below are thereby decided. Questions 2 to 4 remain open as wording and retention questions.

### Corrections after the first real-browser run (2026-11-25)

The joint acceptance run (`docs/m1b-acceptance-evidence.md` on the integration branch) found four backend defects. None changes a schema, an error code, the order of checks or an approved decision.

- **A token that cannot be unmasked is a wrong token.** Spring Security masks the session's token differently at every read and unmasks what the client sends. A value of the right length and alphabet with wrong content usually unmasks to bytes that are not text, and the framework's handler then throws, which was answered 500 with no body. The application's handler (`CsrfTokenHandler`) delegates to the framework's and turns any such failure into "no token", which the CSRF filter refuses with 403 `csrf_invalid` like every other wrong token.
- **A session's token is stored when the session changes, not when it is first read.** The framework's strategy removed the old token at sign-in and left the new one to be created by the first request that asked. Two requests asking at once both created and stored one; the second insert failed and was answered 500. `Sessions.establish` now creates and stores the new token together with the new identifier and state, so every session that exists has its token, and concurrent readers only read. Considered and rejected: making the session store's attribute insert tolerate a conflict. It would hide the failure and, for every attribute, let two simultaneous session changes mix silently.
- **Exceptions are redacted like messages.** The log filter searched the message of a log line and not the exception attached to it; a database error quotes the row it failed on, and printed a session row's key. The message of the exception, of every cause and of every suppressed exception is now searched, and when anything is replaced the stack trace is printed from a redacted copy that keeps the original frames and names the original class.
- **Identity has its own clock, and it is the real time in every profile.** The development fixture loader pins the application clock so public responses are repeatable; identity shared it, so real authenticator codes were refused, rate-limit windows never ended and session deadlines were in the past. `IdentityClock` cannot be pinned by anything in the application. The automated tests move it by supplying a time source as a bean, which the application never defines (asserted in the dev-profile test). `AppClock` remains for the public read responses only.
- **Optional `APV_PASSWORD_BLOCKLIST_SHA256`** (reviewer's recommendation, adopted): the digest recorded at build time, checked against the manifest by the server and the operator command, so replacing the list together with its manifest is refused. Optional so that an existing configuration keeps working; recommended in production.

Questions for the contract, reported and not decided here:

1. **Section 7.1 says password change and recovery-code regeneration draw on "client address"; section 7.2 defines no limit for that counter.** Implemented as its own counter, 30 requests in 15 minutes per client address, counted at step 5 and separate from the sign-in counter. The number and whether it should instead share the sign-in counter need confirming.
2. **Section 4.3 puts "rate limits" at step 5, but the pair and account counters need the validated body** (step 7) and, for the signed-in operations, the session (step 6). They are counted at the start of step 8. If the contract means something else, it has to say what a body-keyed limit is keyed on before the body is read.
3. **"A pending session that calls sign-in, sign-up or set-password is discarded first"** does not say whether "first" is before validation. Implemented as: discarded when the operation proceeds, that is after step 7.
4. **Retention of one-time credentials** (used, cancelled, expired) is not stated. They are kept.
5. **The built-in password blocklist is a small starter list.** The contract's breached-password rule needs a real list, supplied as a local file through `APV_PASSWORD_BLOCKLIST_PATH`. Which list, and whether production should refuse to start without one, is an owner decision.

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
