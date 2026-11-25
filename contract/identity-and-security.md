# Identity and security contract

Rules behind the identity endpoints of `openapi.yaml` (version 0.3.0). The frontend and the backend each implement against this document in milestone 1B. Nothing here is implemented yet.

Each rule is marked:

- **[Approved]** already decided by the project owner or the accepted architecture.
- **[Proposal]** a new security recommendation, to be reviewed before implementation.

## 1. Who can do what

### 1.1 Access levels

| Level | Stored as | How it comes to exist |
|---|---|---|
| Visitor | Nothing. A visitor has no account and no role. | Not signed in. **[Approved]** |
| Donor | `role = donor` | Public sign-up. Sign-up can create nothing else. **[Approved]** |
| Administrator | `role = admin`, optionally `can_disburse` | Created by the operator, never by sign-up. **[Approved]** |
| Super administrator | `role = super_admin` | Created by the operator. **[Approved]** |

An account is never required to give or to report a gift. **[Approved]**

### 1.2 Permission matrix

"M1" means the permission is enforced and reachable in milestone 1. The others are named now so the names are fixed; their endpoints arrive with their milestones, and until then nothing grants access to anything.

| Permission | Visitor | Donor | Admin | Admin with `can_disburse` | Super admin | Operational |
|---|---|---|---|---|---|---|
| Read public pages and the public API | Yes | Yes | Yes | Yes | Yes | M0 |
| Give, and report a gift | Yes | Yes | Yes | Yes | Yes | Donation milestone |
| `account.read_own` | No | Yes | Yes | Yes | Yes | **M1** |
| `account.change_password` | No | Yes | Yes | Yes | Yes | **M1** |
| `donations.read_own` | No | Yes | Yes | Yes | Yes | Donation milestone |
| `cases.manage` (create and edit cases) | No | No | Yes | Yes | Yes | Administration |
| `case_updates.post` | No | No | Yes | Yes | Yes | Administration |
| `donation_reports.read` | No | No | Yes | Yes | Yes | Administration |
| `disbursements.record` | No | No | No | Yes | Yes | Financial administration |
| `donations.confirm` (confirm and reject) | No | No | No | No | Yes | Financial administration |
| `funds.transfer` | No | No | No | No | Yes | Financial administration |
| `payment_methods.manage` | No | No | No | No | Yes | Financial administration |
| `staff.manage` | No | No | No | No | Yes | Super administration (M5) |
| `contact_messages.read` | No | No | No | No | Yes | Super administration |
| `audit.read` | No | No | No | No | Yes | Super administration |

**[Approved]** boundaries; **[Proposal]** the permission names.

`can_disburse` exists only on administrators. A super administrator can always record a disbursement, as the original design says ("me and chosen admins").

### 1.3 Ownership rules

Role alone is never enough for data that belongs to a person.

| Resource | Rule |
|---|---|
| An account | Readable and changeable only by itself. In M1 there is no endpoint that takes an account identifier: `GET /me` and `POST /auth/change-password` always act on the session's own account, so there is nothing to tamper with. |
| A donor's private gift history (later) | Only gifts whose `account_id` is the session's account. Looked up by the session, never by an identifier in the request. |
| Guest gifts | Never attached to an account because an email address matches. **[Approved]** Claiming one will need an explicit, proven step, designed with the donation milestone. |
| Sessions | An account can end its own sessions. Only the operator can end someone else's, in M1. |
| Staff acting on a person's data (later) | Allowed by permission, and written to the audit log with the actor and the target. |

### 1.4 Where permissions are enforced

- **[Approved]** Deny by default. A route with no declared rule is refused. Adding a controller without a rule fails a test, it does not open a hole.
- **[Approved]** The server checks every protected request itself. `Account.permissions` is for deciding what to show and is not a boundary.
- **[Proposal]** Permissions are computed from the account's role and `can_disburse` on every request, from the database, not from a copy stored in the session. A removed permission stops working on the next request. See 6.3.

## 2. Accounts

### 2.1 Fields

Minimal on purpose. **[Proposal]**

| Field | Notes |
|---|---|
| Identifier | Internal number, plus a public opaque identifier `acc_` and 22 random characters. Assigned once, never changed, never reused, not derived from the email. |
| Email as typed | Shown back to its owner. |
| Normalized email | Trimmed and lower-cased. Unique. ASCII only in this version, at most 254 characters. Dots and "+tags" are kept: two addresses the mail provider treats as one are two accounts here, which is the safe direction. |
| Display name | 1 to 80 characters. Not public in this milestone. |
| Locale | `en` or `fr`. |
| Role, `can_disburse` | See 1.1. |
| Email verified | False. See 2.3. |
| Enabled | A disabled account cannot sign in and its sessions end. Accounts are disabled, not deleted, once anything refers to them. |
| Security version | A counter raised by every change that must end existing sessions. See 6.3. |

No address, phone number, date of birth or picture. Profile editing, pictures and gift history are later milestones. **[Approved]**

### 2.2 Sign-up without revealing who has an account

**[Proposal]** `POST /auth/register` always answers 202 `{ "status": "accepted" }`, whether the address was new or already registered, after doing the same amount of work (the password is hashed in both cases). No session is created.

- New address: the account is created.
- Existing address: nothing changes. In particular the existing password is not replaced.
- Two simultaneous sign-ups for one address: the unique constraint lets exactly one win; the other is treated as "existing". Both get 202.

Frontend experience: after 202, show "Your account is ready. Sign in." and the sign-in form. If sign-in then fails, show: "We could not sign you in. If you already had an account, use that password, or contact us to reset it."

**What this does not hide.** Someone who signs up with another person's address and then tries to sign in learns from the failure that an account existed. Fully hiding this needs a verification email, which the site cannot send yet. Until then the protection is the rate limits of section 7. This residual risk is an owner decision, see `M1A-review.md`.

### 2.3 Email verification

**[Approved]** No email infrastructure in M1, and an unverified address is never shown as verified. `emailVerified` is false for every account created in M1. Nothing in M1 depends on it. When email exists, verification will be added, and features that need a proven address (claiming gifts, self-service reset) will require it.

## 3. Passwords

**[Proposal]**, following OWASP's Password Storage Cheat Sheet and NIST SP 800-63B revision 4.

| Rule | Value |
|---|---|
| Algorithm | Argon2id, through Spring Security's `Argon2PasswordEncoder` (uses Bouncy Castle) |
| Salt | 16 random bytes, unique per password, generated by the encoder |
| Work factor | Never below OWASP's minimum of 19 MiB memory, 2 iterations, 1 lane. Benchmarked on the production machine and raised until one verification takes about 0.25 to 0.5 seconds. |
| Hash output | 32 bytes |
| Stored form | The standard self-describing Argon2 string, inside Spring's `{id}` prefix, so parameters travel with each hash |
| Minimum length | 15 Unicode code points |
| Maximum length | 128 Unicode code points. A longer password is refused, never cut. |
| Characters | Anything, including spaces and every Unicode character. Normalized to NFKC before hashing so the same password typed on two keyboards matches. Not trimmed. |
| Composition rules | None. No required digits or symbols. |
| Forced periodic change | None. A change is required only after a reset or evidence of compromise. |
| Blocked passwords | A list shipped with the application: known breached passwords, plus the site name, the person's email and display name. Checked locally. No password, and no part of a password's hash, is sent to another service. |
| Pasting and password managers | Must work. The frontend must not block paste or autofill, and uses `autocomplete="new-password"` and `"current-password"`. |
| Upgrades | When the work factor is raised, a hash is upgraded at the person's next successful sign-in. |

Protecting the server: hashing costs memory, so at most a small fixed number of hashes run at once (proposed: 4); further requests wait briefly, then get 503. Together with rate limiting this stops sign-in from being used to exhaust the server.

Passwords, hashes, one-time credentials, authenticator secrets, recovery codes and session identifiers never appear in a response (except the two one-time displays in section 5), a log line, an error message or an audit record. Request bodies of identity endpoints are never logged.

## 4. Sessions

### 4.1 Mechanism

- **[Approved]** Server-side sessions stored in PostgreSQL, with Spring Session JDBC. The browser holds only a random identifier in a cookie. No JWT, nothing in `localStorage` or `sessionStorage`, no external identity provider.
- **[Proposal]** The stored session holds the account identifier, its security version at sign-in, the sign-in instant, the authentication state (4.3) and the absolute expiry. It holds no permission list.

### 4.2 Cookies

| | Production (HTTPS) | Developer's machine (HTTP on loopback) |
|---|---|---|
| Session cookie name | `__Host-apv_session` | `apv_session` |
| `HttpOnly` | Yes | Yes |
| `Secure` | Yes | No |
| `SameSite` | `Lax` | `Lax` |
| `Path` | `/` | `/` |
| `Domain` | Not set | Not set |
| Lifetime | Session cookie (no `Max-Age`); the server decides expiry | Same |
| CSRF cookie name | `__Host-XSRF-TOKEN` | `XSRF-TOKEN` |
| CSRF cookie `HttpOnly` | No, by design of the pattern | No |

**[Approved]** attributes; **[Proposal]** names. The `__Host-` prefix makes browsers refuse the cookie unless it is `Secure`, has `Path=/` and has no `Domain`, so a sibling subdomain cannot plant or read it.

The insecure form exists only in the `dev` and `test` profiles. Outside them the application refuses to start if the session cookie is not `Secure`, and `ProfileGuard` already refuses mixing a production profile with `dev`. Development needs never relax production.

### 4.3 Authentication states

| State | Meaning | May call |
|---|---|---|
| Anonymous | No session, or a session with no sign-in | Public API, `GET /auth/csrf`, register, login, logout, set-password |
| `mfa_required` | Password accepted, second factor owed | The above, plus `POST /auth/mfa/verify` |
| `mfa_enrollment_required` | Staff password accepted, no second factor set up yet | The above, plus `POST /auth/mfa/enroll` and `/confirm` |
| `authenticated` | Fully signed in | Everything its permissions allow |

A session in one of the two middle states has **no permission at all**. `GET /me` answers 401 with the state's code. It expires after 10 minutes.

### 4.4 Lifetimes

**[Proposal]**

| | Idle timeout | Absolute lifetime |
|---|---|---|
| Donor | 2 hours | 24 hours |
| Administrator, super administrator | 30 minutes | 12 hours |
| Pending second factor | 10 minutes | 10 minutes |

Activity moves the idle deadline and never the absolute one. The absolute deadline is fixed at sign-in and checked on every request. `Account.session` reports both, so the frontend can warn before the end. There is no "remember me".

### 4.5 Rotation

The session identifier is replaced, and the old one stops working, at every change of privilege: successful password sign-in, successful second factor, completed enrollment, password change. This defeats session fixation. A CSRF token never survives a rotation.

## 5. Second factor for staff

All of this section is **[Proposal]**.

### 5.1 Rule

Administrators and super administrators cannot use any staff permission without a second factor. A staff account without one can only enroll. Donors have no second factor in M1.

### 5.2 Method

Time-based one-time passwords, RFC 6238, as authenticator applications implement them: SHA-1, six digits, 30-second steps. A code for the previous or next step is accepted, to tolerate clock drift.

Spring Security 7.1 provides the multi-factor framework (factor authorities, rules that require several factors). It does not ship a time-based code verifier, so that small piece is written with the JDK's own cryptography and covered by the RFC's published test vectors.

- The secret is 160 random bits, generated by the server.
- Stored encrypted with AES-256-GCM under a key that lives in the environment, not in the database. A database copy alone does not reveal secrets.
- **Replay.** The last accepted time step is stored per account. A code for that step or an earlier one is refused, so a code works once even inside its 30 seconds.
- **Guessing.** Five wrong codes cancel the pending sign-in; the person starts again with the password. Attempts also count toward the account's rate limit (section 7).

### 5.3 Enrollment

1. Staff signs in with the password. Answer: `mfa_enrollment_required`.
2. `POST /auth/mfa/enroll` returns the secret and an `otpauth://` address. The frontend draws the QR code itself; the address is never sent to another service.
3. The person adds it to their application and sends one code to `POST /auth/mfa/confirm`.
4. On success the factor is active, the session becomes `authenticated` under a new identifier, and ten recovery codes are shown once.

A pending secret expires after 10 minutes and is replaced if enrollment starts again.

### 5.4 Recovery codes

- Ten codes of 80 random bits each, written `XXXX-XXXX-XXXX-XXXX`.
- Shown once. The server stores only a keyed hash of each (HMAC-SHA-256 with a server-side key), which is sufficient for random values of this size.
- Each works once, in place of an authenticator code at sign-in only.
- `POST /auth/mfa/recovery-codes` replaces all ten. It needs the password and a current authenticator code. A recovery code cannot be used to mint new ones.
- Using a recovery code is recorded in the audit log, and `recoveryCodesRemaining` tells the person how many are left.

### 5.5 Losing the second factor

A recovery code is the normal way back. With no authenticator and no recovery codes left, only the operator procedure of section 8.4 helps. A password reset never removes or bypasses the second factor.

## 6. Ending sessions

### 6.1 Sign-out

`POST /auth/logout` deletes the session row and clears the cookie. The identifier is dead immediately, not merely forgotten by the browser.

### 6.2 Events that end sessions

| Event | Sessions ended |
|---|---|
| Sign-out | The current one |
| Password change by the person | All others; the current one continues under a new identifier |
| Password set with a one-time credential (activation, reset) | All |
| Account disabled | All |
| Role changed, `can_disburse` changed | All |
| Second factor reset by the operator | All |
| Recovery codes regenerated | None; the action itself required password and code |
| Idle or absolute deadline reached | That one |

### 6.3 How it is guaranteed

Expiry alone is not relied on. Two independent mechanisms, **[Proposal]**:

1. **Delete.** Spring Session JDBC indexes sessions by account, so every event above deletes the account's session rows in the same database transaction as the change.
2. **Check on every request.** Each authenticated request loads the account row (one primary-key read). If the account is disabled, or its security version differs from the one stored in the session, the session is deleted and the answer is 401. The same read supplies the current role and `can_disburse`, so permissions are never stale.

If the first mechanism ever misses a row, the second still refuses it.

## 7. Rate limiting

**[Proposal]**. Counters live in PostgreSQL. No other infrastructure.

| What | Key | Limit | When exceeded |
|---|---|---|---|
| Failed sign-in | Normalized email and client address together | 5 in 15 minutes | 429 until the window ends |
| Failed sign-in | Normalized email alone | 20 in 1 hour | 429, at most 15 minutes |
| Sign-in attempts | Client address alone | 30 in 15 minutes | 429 |
| Sign-up | Client address | 5 in 1 hour | 429 |
| One-time credential attempts | Client address | 10 in 1 hour | 429 |
| Second-factor attempts | Pending sign-in | 5 | Pending sign-in cancelled |
| Second-factor attempts | Account | 10 in 1 hour | 429 |
| `GET /auth/csrf` | Client address | 120 in 1 minute | 429 |

- A 429 carries `Retry-After` in seconds and the `rate_limited` error.
- A throttled sign-in attempt is refused before the password is checked, and is answered the same way for existing and unknown addresses.
- **No permanent lockout.** Nothing an outsider does can disable an account. The worst case is a temporary delay for that address, which an attacker could impose on a known address; staff are additionally protected by the second factor. Keying the tight limit on the address pair means a person on their own connection is not locked out by an attacker elsewhere until the looser per-address limit is reached.
- Counters store a keyed hash of the email and of the client address, not the values.
- **Client address.** Taken from the connection. A forwarded-for header is honored only when the connection comes from the configured reverse proxy; from anywhere else it is ignored. Behind the proxy, only the right-most address added by that proxy is used.

## 8. Operator procedures

All **[Proposal]**. Browser-based staff management belongs to milestone 5. Until then, staff accounts are managed by an operator command run on the server by someone who already has access to it.

### 8.1 The operator command

A mode of the same application (`java -jar … operator`), not a network endpoint. It prompts for what it needs on the terminal.

- No default account exists. No password is ever created, stored in a file, committed, or passed as a command-line argument or environment variable.
- The command never asks for and never prints a password. It prints a **one-time credential** to the terminal, once.
- Every command writes an audit record naming the operating-system user who ran it.

| Command | Effect |
|---|---|
| `create-super-admin` | Creates a super administrator account in the "not activated" state. Prints a one-time activation credential. |
| `create-admin` | Same for an administrator. |
| `set-can-disburse` | Grants or removes the disbursement permission. Ends the account's sessions. |
| `disable-account`, `enable-account` | Ends sessions on disable. |
| `issue-password-reset` | Prints a one-time reset credential for an account. |
| `reset-mfa` | Removes the second factor and recovery codes. Ends sessions. The person must enroll again at next sign-in. |
| `list-staff` | Emails, roles and states. No secrets. |

### 8.2 One-time credentials

- 256 random bits. Stored only as a SHA-256 hash.
- Valid for 60 minutes (activation) or 30 minutes (reset). Works once. Issuing a new one cancels the earlier ones for that account.
- It allows exactly one thing: choosing a password through `POST /auth/set-password`. It signs nobody in.
- The operator gives it to the person through a channel they already trust with that person, for example in person or on a call, and not through the channel the request came in on.

### 8.3 First super administrator and staff activation

1. The owner runs `create-super-admin` on the server and receives the activation credential on the terminal.
2. In the browser, the activation page sends the credential and a new password to `POST /auth/set-password`.
3. The owner signs in, is told `mfa_enrollment_required`, enrolls, and stores the recovery codes.

An administrator is created the same way with `create-admin`. Nobody but the person ever knows their password.

### 8.4 Password recovery

Manual, until the site can send email.

1. The person contacts the owner.
2. The owner verifies who they are by a means that does not depend on the account: the owner knows every administrator personally, and for a donor uses a call or a detail only the two would know. Unverified requests are refused.
3. The owner runs `issue-password-reset` and passes the credential on a trusted channel.
4. The person sets a new password. All their sessions end.
5. A staff member still needs their second factor to sign in. The reset did not touch it.

Lost second factor and no recovery codes: same identity check, then `reset-mfa`. This is deliberately a separate command from the password reset, so that losing a password and losing a device are two different, separately audited requests. One request must never yield both.

### 8.5 The last super administrator

The database refuses any change that would leave no enabled, activated super administrator: disabling, demoting or deleting the last one fails. Enforced by a constraint trigger that runs at commit, so it also holds when two changes race. The operator command reports the refusal; there is no override flag. Recovering a site whose only super administrator lost everything is done with `create-super-admin` by someone with server access, which is the same trust the first account rested on.

## 9. CSRF and request rules

### 9.1 CSRF

**[Approved]** Spring Security's protection for single-page applications (`csrf.spa()`); details **[Proposal]**.

- `GET /auth/csrf` returns `{ headerName, token }` and sets the CSRF cookie. The frontend keeps the token in memory, not in storage.
- Every request that is not `GET`, `HEAD` or `OPTIONS` must carry the header `X-XSRF-TOKEN` with that token. This includes sign-up, sign-in and sign-out, and will include every later public form (reporting a gift, the contact form).
- A missing or wrong token is 403 `csrf_invalid`.
- The token is replaced whenever the session changes: sign-in, second factor, enrollment, password change, sign-out. The frontend fetches a new one after each of those calls.
- On any 403 `csrf_invalid`, the frontend fetches a new token and repeats the request once. If it fails again, it shows an error.
- A token from another browser session never matches this browser's cookie.

`SameSite=Lax` on the session cookie is a second, independent barrier, not a replacement.

### 9.2 Origin

- **[Approved]** One origin. No CORS headers are sent at all, so no other site can read a response or send credentials with a scripted request.
- **[Proposal]** For requests that are not `GET`, the `Origin` header, when present, must equal the site's own origin; otherwise 403. `Sec-Fetch-Site: cross-site` on such a request is refused too.

### 9.3 Bodies

- `Content-Type` must be `application/json`. Anything else is 415. This also blocks cross-site form posts, which cannot send that type without a preflight.
- At most 16 KiB. Larger is 413, refused before parsing.
- Unknown properties are refused with 400 and field code `unknown_field`. This is the defence against mass assignment: a request cannot smuggle `role`, `canDisburse` or `emailVerified`, and `npm run validate` proves the sign-up schema has no such property.
- Responses are JSON with `X-Content-Type-Options: nosniff`.

### 9.4 Response headers

| Header | Value | Where |
|---|---|---|
| `Cache-Control` | `no-store` | Every identity response, and every response to a request that carried a session cookie on a non-public path |
| `ETag` | Absent | Same |
| `Cache-Control` | `no-cache` with `ETag` | The seven public read endpoints only, which never depend on who asks |
| `X-Content-Type-Options` | `nosniff` | All |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'` | API responses |
| `Referrer-Policy` | `no-referrer` | API responses |
| `X-Frame-Options` | `DENY` | All |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Production, set at the reverse proxy |
| `Cross-Origin-Resource-Policy` | `same-origin` | API responses |

The page's own content security policy is set where the frontend is served; the frontend agent owns its content.

### 9.5 Methods and unknown routes

- A known path with a method it does not support: 405 `method_not_allowed`, with `Allow`. This replaces the temporary 404 of milestone 0B, including on the seven public endpoints.
- An unknown path: 404 `not_found`, whatever the method.
- Never a redirect to a sign-in page, and never an HTML error page, under `/api`.

### 9.6 Reverse proxy

The application trusts forwarded headers (scheme, host, client address) only from the configured proxy address. It listens on loopback or the internal container network, never on a public address. The proxy strips incoming forwarded headers from clients before adding its own.

### 9.7 Errors

500 responses are the generic `internal_error` with a request identifier and nothing else. Details go to the server log, with secrets excluded as in section 3.

## 10. Data design

Proposed tables for milestone 1B. No migration is written yet. The `identity` module owns the first seven; `audit` owns the last.

| Table | Purpose | Key constraints |
|---|---|---|
| `account` | One row per account: identifiers, emails, display name, locale, role, `can_disburse`, enabled, activated, email verified, security version | Unique normalized email. Role in a fixed list. `can_disburse` only when role is `admin`. Public identifier unique and immutable. Last-super-administrator trigger. |
| `account_password` | Current password hash and when it was set | One row per account. Separate table so ordinary account queries never select the hash. |
| `account_totp` | Encrypted authenticator secret, state (pending or active), last accepted time step | One per account. |
| `account_recovery_code` | Keyed hash of each code, used instant | Each used at most once. |
| `one_time_credential` | Hash, purpose (activation or reset), account, expiry, used instant | Hash unique. Used at most once, enforced by a conditional update. |
| `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` | Sessions, in the schema Spring Session documents for PostgreSQL | Created by a Flyway migration, never by automatic initialization. |
| `auth_throttle` | Rate-limit counters: scope, keyed hash of the key, window start, count | Unique on scope, key and window. Old windows deleted by a scheduled task. |
| `audit_event` | Append-only record of security events | No update, no delete, same technique as the ledger. |

Deliberate choices:

- **Roles are a column, not tables.** Three fixed roles and one flag. The permission matrix lives in one place in code and is covered by tests. A role-and-permission schema can replace it later without touching the API, because the API only exposes `role` and `permissions`.
- **Hashes and secrets live in their own tables**, so the query that builds an `Account` response cannot select them by accident. This mirrors `case_private` in milestone 0.
- **No personal data in counters or audit beyond what the event needs.**

### 10.1 Audit events

| Event | Recorded |
|---|---|
| Sign-in succeeded, failed, throttled | Account when known, keyed hash of the address attempted, outcome, client address, request identifier |
| Second factor succeeded, failed, cancelled after five failures | Account, method (authenticator or recovery code) |
| Sign-out | Account |
| Sign-up accepted | Whether an account was created (visible only in the audit log) |
| Password changed, password set by one-time credential | Account, sessions ended |
| One-time credential issued, redeemed, expired, refused | Account, purpose, operator |
| Second factor enrolled, reset, recovery codes regenerated | Account, operator when applicable |
| Account created, enabled, disabled | Account, operator |
| Role or `can_disburse` changed | Account, old and new value, operator |
| Session ended by the security-version check | Account, reason |
| Refused change to the last super administrator | Account, operator |

Each record has: instant, event type, outcome, actor (account or operator), target account, client address, request identifier, and a small details object with a fixed set of keys per event type. Passwords, hashes, session identifiers, CSRF tokens, authenticator secrets, codes and one-time credentials are never written, and the details object is built from an allowlist, not from request data.

The same table will take financial administration events later: the columns are generic (actor, target type, target identifier, details).

### 10.2 The development fixture loader

The loader deletes case, ledger and picture data before loading a fixture. For identity:

- It **never** touches `account`, credentials, sessions, throttles or audit. Its list of tables is explicit, and a test will assert that no identity or audit table is in it.
- Shared fixtures contain no accounts. Development sign-ins come from a separate development-only seed, with obviously fake accounts whose passwords are generated at start-up and printed to the developer's console, never committed. It exists only in the `dev` and `test` profiles and obeys the same two guards as the loader: local database only, and never a database marked as holding real data.
- A database is marked `real` as soon as the operator command creates an account in it, so neither the loader nor the development seed will ever run against it again.

## 11. Threat model

What is defended, how, and what is left.

| Threat | Defence | Residual risk |
|---|---|---|
| Password guessing online | Rate limits; 15-character minimum; blocked-password list; second factor for staff | A weak but long, unlisted donor password |
| Stolen database | Argon2id; encrypted authenticator secrets with the key outside the database; hashed recovery codes and one-time credentials | Offline guessing of weak passwords, slowed by the work factor |
| Session theft through script injection | `HttpOnly` cookie; no token in storage; strict content policy on API responses | An injected script can still act as the person while the page is open. The frontend's own content policy and review are the defence. |
| Session fixation | New identifier at every privilege change; `__Host-` cookie | None known |
| Cross-site request forgery | CSRF token on every non-GET; `SameSite=Lax`; JSON-only; origin check | None known |
| Privilege escalation by request fields | Closed request schemas; unknown fields refused; no role field on sign-up | None known |
| Reading another person's data | No identifier in M1 requests; later, lookups by session | To be re-examined for each new endpoint |
| Stale privileges after demotion | Security version checked on every request; sessions deleted | None known |
| Account enumeration | Identical sign-in errors and timing; identical sign-up answer | Sign-up followed by sign-in reveals existence (2.2) until email exists |
| Lockout abuse | No permanent lockout; pair-keyed limits | A temporary delay can be imposed on a known address |
| Second-factor bypass through recovery | Reset and second-factor reset are separate operator actions; recovery codes cannot mint codes | Depends on the operator's identity check |
| Code replay | Last time step stored | None known |
| Compromised operator or server | Audit records; no stored passwords | Someone with server access can create a super administrator. This is the trust root and cannot be removed, only watched. |
| Secrets in logs | Identity bodies never logged; audit details from an allowlist | A future developer adding a log line; covered by a leak test |
| Cached private responses | `no-store`, no `ETag`, on every identity response | None known |
| Denial of service through hashing | Concurrent hash limit; rate limits; body size limit | A distributed flood needs the reverse proxy or the host to absorb it |
| Fictional data or seeded accounts in production | Loader and seed exist only in `dev` and `test`; local-only; refused on a `real` database | None known |

Not in scope for M1: email-based flows, passkeys, device trust, single sign-on, self-service account deletion, and browser-based staff management.
