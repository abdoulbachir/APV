# Identity and security contract

Rules behind the identity endpoints of `openapi.yaml` (version 0.3.0), revised after the independent review of commit `bd162cd` (pull request 8). The frontend and the backend each implement against this document in milestone 1B. Nothing here is implemented yet.

Status: technically accepted at `b5ea149fb3e33380dd622ad734117a15051b4bfd`; owner policy decisions approved on 2026-11-25 (see `../docs/decisions.md`). This is an accepted design, not runtime evidence.

Rule labels:

- **[Approved]** decided by the project owner or the accepted architecture.
- **[M1A design]** reviewed technical design to implement and verify in M1B; framework compatibility probes and runtime acceptance remain required.

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

"M1" means the permission is enforced, reachable, and returned in `Account.permissions` in milestone 1. The others are planned names only: they are not in the API schema, are never returned, and grant nothing until the milestone that adds their endpoints also adds them to the schema. In milestone 1, `GET /me` returns exactly `account.read_own` and `account.change_password` for every role.

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

**[Approved]** boundaries; **[M1A design]** the permission names.

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
- **[M1A design]** Permissions are computed from the account's role and `can_disburse` on every request, from the database, not from a copy stored in the session. A removed permission stops working on the next request. See 6.3.

## 2. Accounts

### 2.1 Fields

Minimal on purpose. **[M1A design]**

| Field | Notes |
|---|---|
| Identifier | Internal number, plus a public opaque identifier `acc_` and 22 random characters. Assigned once, never changed, never reused, not derived from the email. |
| Email as typed | Shown back to its owner. |
| Normalized email | Lower-cased. Unique. ASCII only in this version, at most 254 characters. Dots and "+tags" are kept: two addresses the mail provider treats as one are two accounts here, which is the safe direction. |
| Display name | 1 to 80 characters. Not public in this milestone. |
| Locale | `en` or `fr`. |
| Role, `can_disburse` | See 1.1. |
| Email verified | False. See 2.3. |
| Enabled | A disabled account cannot sign in and its sessions end. Accounts are disabled, not deleted, once anything refers to them. |
| Activated | False for a staff account until its holder has set a password with an activation credential. |
| Security version | A counter raised by every change that must end existing sessions. See 6.3. |

No address, phone number, date of birth or picture. Profile editing, pictures and gift history are later milestones. **[Approved]**

**White space.** The server does not trim an address. The frontend trims before sending; an address that still has surrounding white space is refused with 400 `invalid_format`. The schema pattern and the server therefore agree exactly.

### 2.2 Public sign-up

**[Approved, owner decision 1]** Public sign-up is **closed in production** until the site can prove that the person signing up controls the address (a verification email). The reason is in "Preclaimed addresses" below.

- A server setting decides. It is closed unless explicitly opened, and the production profile refuses to start with it open while no address proof exists.
- `GET /auth/options` tells the frontend (`registrationOpen`), which hides the sign-up form when closed.
- `POST /auth/register` on a closed server answers 403 `registration_closed`. Nothing is created.
- The endpoint, its contract and its tests stay, and sign-up is open in the development and test profiles, so milestone 1B builds and tests it fully.

While sign-up is closed in production, the only accounts are staff accounts created by the operator. Giving and reporting a gift need no account, so donors lose nothing they have today.

**When sign-up is open.** `POST /auth/register` always answers 202 `{ "status": "accepted" }`, whether the address was new or already registered, after doing the same amount of work (the password is hashed in both cases). No session is created.

- New address: the account is created.
- Existing address: nothing changes. The existing password is not replaced.
- Two simultaneous sign-ups for one address: the unique constraint lets exactly one win; the other is treated as "existing". Both get 202.

Frontend experience: after 202, show "Your account is ready. Sign in." and the sign-in form. If sign-in then fails: "We could not sign you in. If you already had an account, use that password, or contact us."

**What an open sign-up cannot hide.** Someone who signs up with another person's address and then tries to sign in learns from the failure that an account existed. Only address proof removes this.

**Preclaimed addresses.** Without address proof, anyone can create an account under an address they do not control, before its real owner does. Rules, whatever the sign-up setting:

- An unverified account is never treated as evidence that its holder owns the address. Nothing is ever granted, linked or sent on the strength of an unverified address. Guest gifts are never attached by matching address. **[Approved]**
- Staff accounts are never created by sign-up, so a staff address cannot be preclaimed into a privileged account.
- When address proof exists, the person who proves control of an address becomes the owner of that address on the site. A preclaimed account under it is detached from the address (its sessions end; it keeps nothing tied to the address), and it is never merged into the owner's account.
- Until then, the operator can disable a preclaimed account on a complaint, after the independent check of 8.4.

### 2.3 Email verification

**[Approved]** No email infrastructure in M1, and an unverified address is never shown as verified. `emailVerified` is false for every account created in M1. Nothing in M1 depends on it.

## 3. Passwords

**[Approved policy, owner decision 6; M1A design for technical details]**, following OWASP's Password Storage Cheat Sheet and NIST SP 800-63B revision 4.

| Rule | Value |
|---|---|
| Algorithm | Argon2id, through Spring Security's `Argon2PasswordEncoder` (uses Bouncy Castle) |
| Salt | 16 random bytes, unique per password, generated by the encoder |
| Work factor | Never below OWASP's minimum of 19 MiB memory, 2 iterations, 1 lane. Benchmarked on the production machine and raised until one verification takes about 0.25 to 0.5 seconds. |
| Hash output | 32 bytes |
| Stored form | The standard self-describing Argon2 string, inside Spring's `{id}` prefix, so parameters travel with each hash |
| Normalization | Unicode **NFC**, applied to every password before it is measured, checked or hashed, at sign-up, sign-in and change alike. NFC only makes the same visible text typed on two devices produce the same bytes. Compatibility normalization (NFKC) is not used: it would make visibly different characters equal and shrink the space of passwords. |
| Length as sent | At most 128 Unicode code points. Longer is refused with `too_long` before anything else is done. |
| Length after NFC | 15 to 128 code points. Fewer is `too_short`. NFC never increases the count beyond what was sent for ordinary text; if it does for an unusual sequence, the same upper bound applies and the password is refused, never cut. |
| Characters | Anything, including spaces. Not trimmed. |
| Composition rules | None. No required digits or symbols. |
| Forced periodic change | None. A change is required only after a reset or evidence of compromise. |
| Pasting and password managers | Must work. The frontend must not block paste or autofill, and uses `autocomplete="new-password"` and `"current-password"`. |
| Upgrades | When the work factor is raised, a hash is upgraded at the person's next successful sign-in. |

**Blocked passwords.** Checked locally on the NFC form; no password and no part of a hash leaves the server. Refused with field code `compromised` when either holds:

1. It is, exactly, on the local list of known breached passwords. **[Approved, M1B corrective follow-up]** A comprehensive local breached-password list is mandatory outside dev/test. Startup is refused if the configured list is missing, unreadable or unsuitable; the small bundled starter list alone is insufficient. The deployment supplies and maintains the comprehensive list locally; document its provenance, integrity, format, sizing and update procedure. Dev/test may use the bundled starter list.
2. It is built from the context. Take the password, lower-case it and drop everything that is not a letter or digit. It is refused when what is left contains one of these context words and fewer than 8 other characters remain after removing every occurrence of them: the site names `apvgakasinay`, `apv` and `gakasinay`; the part of the email before `@`; the display name. Context words shorter than 4 characters are ignored, except the site names.

The same error code is used for both, so the answer does not say which. A sign-in is never refused for these reasons; they apply only when a password is chosen.

**Protecting the server.** Hashing costs memory. At most 4 hashes run at once. A request that cannot start hashing within 2 seconds gets 503 with `Retry-After: 5`. Rate limits (section 7) are applied before any hashing, so one client cannot hold the four slots.

Passwords, hashes, one-time credentials, authenticator secrets, recovery codes, CSRF tokens and session identifiers never appear in a response (except the two one-time displays in section 5 and the CSRF token itself), a log line, an error message or an audit record. Request bodies of identity endpoints are never logged. This includes retired or expired session identifiers and verbose logging levels; there is no exception for a previously valid identifier.

## 4. Sessions

### 4.1 Mechanism

- **[Approved]** Server-side sessions stored in PostgreSQL, with Spring Session JDBC. The browser holds only a random identifier in a cookie. No JWT, nothing in `localStorage` or `sessionStorage`, no external identity provider.
- **[M1A design]** A session holds its CSRF token and, once someone signs in, the account identifier, its security version at sign-in, the sign-in instant, the state (4.3) and the absolute deadline. It holds no permission list.
- **[M1A design]** A browser gets an **anonymous session** the first time it calls `GET /auth/csrf`. It holds only the CSRF token, grants nothing, and expires after 30 idle minutes and at most 2 hours. Anonymous sessions are created only by that endpoint, which is rate-limited (section 7), and expired rows are deleted by a scheduled task.

### 4.2 Cookie

There is one cookie. The CSRF token is not in a cookie.

| | Production (HTTPS) | Developer's machine (HTTP on loopback) |
|---|---|---|
| Name | `__Host-apv_session` | `apv_session` |
| `HttpOnly` | Yes | Yes |
| `Secure` | Yes | No |
| `SameSite` | `Lax` | `Lax` |
| `Path` | `/` | `/` |
| `Domain` | Not set | Not set |
| Lifetime | Session cookie (no `Max-Age`); the server decides expiry | Same |

**[Approved]** attributes; **[M1A design]** names. The `__Host-` prefix makes browsers refuse the cookie unless it is `Secure`, has `Path=/` and has no `Domain`, so a sibling subdomain cannot plant or read it.

The insecure form exists only in the `dev` and `test` profiles. Outside them the application refuses to start if the session cookie is not `Secure`, and `ProfileGuard` already refuses mixing a production profile with `dev`. Development needs never relax production.

### 4.3 Session states

| State | Meaning |
|---|---|
| `anonymous` | No session, or a session that only holds a CSRF token |
| `mfa_required` | Password accepted, second factor owed |
| `mfa_enrollment_required` | Staff password accepted, no second factor set up yet |
| `authenticated` | Fully signed in |

A session in one of the two middle states has **no permission at all**.

Which operation accepts which state is written in the contract as `x-session-state` on each operation, checked by `npm run validate`, and generated into `identity-api.ts` as `allowedStates`:

| Operation | anonymous | mfa_required | mfa_enrollment_required | authenticated |
|---|---|---|---|---|
| Public read endpoints, `GET /auth/csrf`, `GET /auth/options`, `POST /auth/logout` | Yes | Yes | Yes | Yes |
| `POST /auth/register`, `/auth/login`, `/auth/set-password` | Yes | Yes (see below) | Yes (see below) | 409 |
| `POST /auth/mfa/verify` | 401 | Yes | 403 | 409 |
| `POST /auth/mfa/enroll`, `/auth/mfa/confirm` | 401 | 403 | Yes | 409 |
| `GET /me`, `POST /auth/change-password` | 401 | 401 | 401 | Yes |
| `POST /auth/mfa/recovery-codes` | 401 | 401 | 401 | Staff with a second factor; 403 for others |

The answer in a wrong state is always the same:

- **401** when more sign-in is owed. Code `unauthenticated` from `anonymous`; `mfa_required` or `mfa_enrollment_required` from those states. These three codes describe the session. They are distinct from the two 401 codes that describe a wrong secret; see 4.7.
- **409 `already_signed_in`** when the session is fully signed in and the operation is for one that is not. Nothing is changed, and the existing session is left alone. The frontend reads `GET /me` and offers to sign out. Signing in "over" a signed-in session is never done silently.
- **403 `forbidden`** when a pending session calls the other pending state's operation, or a signed-in account lacks the permission.

A pending session that calls `/auth/login`, `/auth/register` or `/auth/set-password` is discarded first: the pending sign-in is over, and the call proceeds as from `anonymous`.

**Order of checks**, so that every wrong request has exactly one answer. A request is refused at the first step it fails, and the later steps are not evaluated at all:

| Step | Check | Refusal |
|---|---|---|
| 1 | Path and method. A method the path does not support is refused here, before any other check. | 404 `not_found`; 405 `method_not_allowed` |
| 2 | Origin and fetch metadata, on methods other than `GET`, `HEAD` and `OPTIONS` (9.2) | 403 `forbidden` |
| 3 | Body size and declared type, before anything is parsed | 413; 415 |
| 4 | CSRF token, on methods other than `GET`, `HEAD` and `OPTIONS` (9.1) | 403 `csrf_invalid` |
| 5 | Rate limits | 429 |
| 6 | Session state: is there a live session, is its identity still valid, is it in a state this operation accepts | 401; 409 `already_signed_in`; 403 `forbidden` |
| 7 | Body validation | 400 |
| 8 | The operation's own checks: password, code, credential | 401 `invalid_credentials`, `mfa_code_invalid`; 400 `credential_invalid` |

The first failing check determines the refusal. A request refused before step 8 does not execute the operation, and its password, code or credential is neither checked nor counted as a failed attempt. That is not the same as having no effect at all: session cleanup, rate-limit bookkeeping and audit records follow the step that refused the request. Step 5 advances the rate-limit counters it documents (section 7), step 6 deletes a session it finds revoked or expired, and refusals are audited as section 10.1 lists. `GET`, `HEAD` and `OPTIONS` skip steps 2 and 4. No supported method changes: the contract's operations use `GET` and `POST` only, and any other method on a known path is refused at step 1.

**Which session exists at steps 4 and 6.** This is what makes the answers for lost sessions deterministic.

- A session **does not exist** once it has been deleted (sign-out, a revocation that deletes rows, the fifth wrong code), once its identifier has been replaced by a rotation, or once it has passed its idle or absolute deadline. A deadline takes effect at the instant it passes, at every step, whether or not the row has been cleaned up yet.
- A request whose cookie names a session that does not exist is handled exactly as a request with no cookie. It has no CSRF token on the server, so a `POST` from it fails **step 4** with 403 `csrf_invalid`, whatever token it sends. A `GET` from it reaches step 6 and is `anonymous`.
- A session whose row still exists and is within its deadlines, but whose identity has been revoked (the account was disabled, or its security version changed, and the row was not deleted), still has its token. A `POST` with that token passes step 4. At **step 6** the session is deleted. For a **protected** operation (one that does not accept `anonymous`) the request is then refused with 401 `unauthenticated`, and its secret is never checked. For an operation that accepts `anonymous` the request proceeds as from `anonymous`, under the explicit exception below.

Two more things are decided at step 6, so their place in the order is fixed too:

- **A revoked identity calling an operation that accepts `anonymous`** (sign-in, sign-up, set-password, sign-out): its session is ended, and the request then proceeds as from `anonymous`. It is not answered 409, because that identity is no longer signed in.
- **Closed sign-up.** `POST /auth/register` on a server where sign-up is closed answers 403 `registration_closed` at this step: after the token and the rate limit, before the body is validated.

So, for a signed-in or pending session that has been lost:

| Request | Answer |
|---|---|
| `GET /me`, or any `GET` that needs a session | 401 `unauthenticated` |
| A `POST` still carrying the lost session's token (the usual case in a browser) | 403 `csrf_invalid`. Then, as always after `csrf_invalid`: new token, `GET /me` (401 `unauthenticated`), clear private state, sign-in screen, nothing resent. |
| A `POST` to a protected operation with a **fresh, valid** token of an anonymous session | 401 `unauthenticated` |
| A `POST` to a public operation (sign-in, sign-up, set-password, sign-out) with a fresh, valid anonymous token | Proceeds normally: these accept `anonymous` |
| A `POST` to a **protected** operation on a revoked identity whose row and token survived | 401 `unauthenticated`, session deleted, secret not checked |
| A `POST` to an operation that accepts `anonymous`, on a revoked identity whose row and token survived | Session deleted; proceeds as from `anonymous` (the exception above) |

### 4.4 Lifetimes

**[Approved, owner decision 2]**

| | Idle timeout | Absolute lifetime |
|---|---|---|
| Anonymous (CSRF token only) | 30 minutes | 2 hours |
| Pending second factor | 10 minutes | 10 minutes |
| Donor | 2 hours | 24 hours |
| Administrator, super administrator | 30 minutes | 12 hours |

Activity moves the idle deadline and never the absolute one. The absolute deadline is fixed at sign-in and checked on every request. `Account.session` reports both, and a pending result reports `pendingExpiresAt`. There is no "remember me".

**Expiry while a second factor is pending.** After 10 minutes the pending session does not exist any more (4.3). A `POST` to `/auth/mfa/verify`, `/enroll` or `/confirm` from the page that was showing the challenge still carries the dead session's token, so it answers 403 `csrf_invalid`; the frontend fetches a token, reads `GET /me`, gets 401 `unauthenticated` and returns to the password step. The same `POST` sent with a fresh anonymous token answers 401 `unauthenticated`. Either way the code is never checked, and nothing of the pending sign-in survives.

### 4.5 Rotation

The session identifier is replaced, and the old one stops working, at every change of privilege: successful password sign-in, successful second factor, completed enrollment, password change. This defeats session fixation, including through an anonymous session planted before sign-in. The CSRF token is replaced at the same moments.

### 4.6 When the answer is lost

A request can succeed on the server while its answer never reaches the browser. The frontend never resends a password, a code or a credential blindly. It reads `GET /me` and continues from the state it finds.

| Lost answer of | What the frontend does |
|---|---|
| Sign-in | `GET /me`: 200 means signed in; 401 `mfa_required` or `mfa_enrollment_required` means continue with the second factor; 401 `unauthenticated` means ask for the password again |
| Second-factor verification | `GET /me` as above. The code is spent either way. |
| Start of enrollment | Call it again. The earlier pending secret is replaced. |
| Enrollment confirmation | `GET /me`. If 200, the recovery codes were not seen: replace them with `POST /auth/mfa/recovery-codes`. |
| Password change | The session identifier may have changed with the lost answer, so the next request may be refused: 401 `unauthenticated` for a `GET`, 403 `csrf_invalid` for a `POST`. Read `GET /me`, then sign in, trying the new password first. |
| Set password | The credential may be spent. Try signing in with the new password; if that fails, a new credential is needed. |
| Sign-up | Sign in. |
| Sign-out | `GET /me`. |

### 4.7 The five 401 codes

A 401 is one of two different things, and the code always says which.

| Code | About | Session afterwards | Sent by |
|---|---|---|---|
| `unauthenticated` | The session | None, or anonymous. Anything that was signed in or pending is gone. | Any `GET` that needs a session; any `POST` that passed the CSRF check but has no live, valid session in an accepted state; the fifth wrong code; a closed enrollment window |
| `mfa_required` | The session | Unchanged: still waiting for the second factor | `GET /me` and operations that need a full session |
| `mfa_enrollment_required` | The session | Unchanged: still waiting for enrollment | Same |
| `invalid_credentials` | The password in this request | **Unchanged.** A signed-in session stays signed in. | Sign-in; password change; recovery-code regeneration |
| `mfa_code_invalid` | The code in this request | **Unchanged.** A pending sign-in stays pending and may try again. | Second-factor verification; enrollment confirmation; recovery-code regeneration |

The server guarantees both directions: a lost session is never reported as `invalid_credentials` or `mfa_code_invalid`, and a wrong secret never ends a session, with the single stated exception of the fifth wrong code. What the frontend does with each code is in section 11.

**A lost session is not always a 401.** On a `POST`, the CSRF check comes before the session-state check (4.3), and a lost session has no valid token. So the page that was open when the session was lost normally sees 403 `csrf_invalid` on its next `POST`, and only learns `unauthenticated` from the `GET /me` that follows. `unauthenticated` appears directly on a `POST` only when the token is valid: a fresh anonymous token, or a revoked identity whose row survived. Both paths end the same way for the person, and neither ever checks or counts a secret.

## 5. Second factor for staff

Mandatory staff MFA and ten recovery codes are **[Approved, owner decision 3]**; implementation details are **[M1A design]**.

### 5.1 Rule

Administrators and super administrators cannot use any staff permission without a second factor. A staff account without one can only enroll. Donors have no second factor in M1.

### 5.2 Method

Time-based one-time passwords, RFC 6238, as authenticator applications implement them: SHA-1, six digits, 30-second steps. A code for the previous or next step is accepted, to tolerate clock drift.

Spring Security 7.1 provides the multi-factor framework (factor authorities, rules that require several factors). It does not ship a time-based code verifier, so that small piece is written with the JDK's own cryptography and covered by the RFC's published test vectors.

- The secret is 160 random bits, generated by the server.
- Stored encrypted with AES-256-GCM under a key that lives in the environment, not in the database. A database copy alone does not reveal secrets.
- **Replay and simultaneous use.** The last accepted time step is stored per account. Accepting a code is one conditional update, "set the last step to this one where it is lower", so of two requests carrying the same code at the same instant exactly one succeeds. A code for that step or an earlier one is refused afterwards.
- **Guessing.** The first four wrong codes of a pending sign-in answer 401 `mfa_code_invalid`. The fifth ends the pending session and answers 401 `unauthenticated`; the person starts again with the password. Attempts also count toward the account's rate limit (section 7).

Neither authenticator codes nor recovery codes are phishing-resistant: a convincing fake site can relay them in real time. They stop password guessing and password reuse, not that. Passkeys would; they are noted as future work in ADR 0002.

### 5.3 Enrollment

1. Staff signs in with the password. Answer: `mfa_enrollment_required`.
2. `POST /auth/mfa/enroll` returns the secret and an `otpauth://` address. The frontend draws the QR code itself, in the page; the address is never sent to another service.
3. The person adds it to their application and sends one code to `POST /auth/mfa/confirm`.
4. On success the factor is active, the session becomes `authenticated` under a new identifier, and ten recovery codes are shown once.

Rules:

- **One pending secret per account.** Starting enrollment again, from the same tab or another, replaces it. A code from a replaced or expired secret (10 minutes) answers 401 `mfa_code_invalid`; the frontend starts enrollment again.
- **Simultaneous confirmations.** Activation is one conditional update from "pending" to "active", so exactly one request succeeds. The guarantees are: one success, one active factor, one set of recovery codes, and no codes or secret disclosed to any other request. The error a losing request gets depends on what it presents, because the winner rotated the session identifier and the CSRF token:

  | The losing request arrives with | Answer |
  |---|---|
  | The same browser session, observed after it became signed in | 409 `already_signed_in` |
  | The CSRF token from before the rotation, or the session identifier from before the rotation, or both. A replaced identifier names a session that no longer exists, so its token cannot match either. | 403 `csrf_invalid` |
  | A fresh token of an anonymous session (the loser re-bootstrapped after losing its session) | 401 `unauthenticated` |
  | Another pending session of the same account (another browser) | 401 `mfa_code_invalid`: the pending secret it knew no longer exists. That session stays pending until it expires and can do nothing with it. |

  Each row has exactly one answer, fixed by the order of checks in 4.3. Tests assert the guarantees, and that each loser gets the answer of its row.
- **The enrollment window.** A staff account may enroll only during the 60 minutes after it redeemed an activation or staff-recovery credential (section 8). The window is checked when enrollment starts **and again when it is confirmed**. A confirmation after the window closed answers 401 `unauthenticated`, ends the pending session and activates nothing. Outside the window a staff account without a second factor cannot sign in at all: it is "not activated" again and needs a new credential. A password alone, however it was obtained, never opens enrollment.

### 5.4 Recovery codes

- A recovery code is a high-entropy, single-use substitute for the authenticator code. It is not a stronger factor and, like the authenticator code, it is not phishing-resistant.
- Ten codes of 80 random bits each, written `XXXX-XXXX-XXXX-XXXX`.
- Shown once. The server stores only a keyed hash of each (HMAC-SHA-256 with a server-side key), which is sufficient for random values of this size.
- Each works once, in place of an authenticator code at sign-in only. Using one is a conditional update of its "used" instant, so two simultaneous requests with the same code cannot both succeed.
- `POST /auth/mfa/recovery-codes` replaces all ten. It needs the password and a current authenticator code. A recovery code cannot be used to mint new ones.
- Using a recovery code is recorded in the audit log, and `recoveryCodesRemaining` tells the person how many are left.

### 5.5 Losing the second factor

A recovery code is the normal way back. With no authenticator and no recovery codes left, only the staff recovery of section 8.5 helps. A password reset never removes or bypasses the second factor.

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
| Staff recovery by the operator (8.5) | All, and the password stops working |
| Recovery codes regenerated | None; the action itself required password and code |
| Idle or absolute deadline reached | That one |

### 6.3 How it is guaranteed

Expiry alone is not relied on. Two independent mechanisms, **[M1A design]**:

1. **Delete.** Spring Session JDBC indexes sessions by account, so every event above deletes the account's session rows in the same database transaction as the change.
2. **Check on every request.** Each authenticated request loads the account row (one primary-key read). If the account is disabled, or its security version differs from the one stored in the session, the session is deleted and the answer is 401. The same read supplies the current role and `can_disburse`, so permissions are never stale.

If the first mechanism ever misses a row, the second still refuses it.

**A change that races a request.** Stated exactly, so it can be tested:

- A request whose check ran before the change committed may finish under the old privileges, if it is a read.
- Every operation that changes something re-reads the account inside its own transaction and takes a shared lock on that row. The change of role, permission or enabled state takes an exclusive lock on the same row. So a privileged write either commits before the change, or sees the change and is refused. It can never commit after the change under the old privileges.
- Every request that starts after the change committed is refused with 401.

## 7. Rate limiting

**[M1A design]**. Counters live in PostgreSQL. No other infrastructure.

### 7.1 Every check of a secret is limited

Not only sign-in. Each place a password, code or credential is checked draws on the counters of what is being guessed.

| Operation | Secret checked | Counters it draws on |
|---|---|---|
| `POST /auth/login` | Password | Email with client address; email; client address |
| `POST /auth/change-password` | Current password | The account's password counter; client address |
| `POST /auth/mfa/recovery-codes` | Current password and authenticator code | The account's password counter and second-factor counter; client address |
| `POST /auth/mfa/verify` | Authenticator or recovery code | The pending sign-in (5 tries); the account's second-factor counter |
| `POST /auth/mfa/confirm` | Authenticator code | The pending sign-in (5 tries); the account's second-factor counter |
| `POST /auth/set-password` | One-time credential | Client address |
| `POST /auth/register` | None | Client address |
| `GET /auth/csrf`, `GET /auth/options` | None | Client address |

### 7.2 Limits

| Counter | Limit | When exceeded |
|---|---|---|
| Password, per email and client address together | 5 failures in 15 minutes | 429 until the window ends |
| Password, per email or account | 20 failures in 1 hour | 429 for 15 minutes |
| Sign-in attempts, per client address | 30 in 15 minutes | 429 until the window ends |
| Protected password operations (change-password and recovery-codes), shared per client address and separate from sign-in | 30 requests in 15 minutes | 429 until the window ends |
| Second factor, per pending sign-in | 5 failures | Pending session ended, 401 `unauthenticated` |
| Second factor, per account | 10 failures in 1 hour | 429 for 15 minutes |
| One-time credential, per client address | 10 in 1 hour | 429 for 15 minutes |
| Sign-up, per client address | 5 in 1 hour | 429 for up to 60 minutes |
| CSRF token and options, per client address | 120 in 1 minute | 429 until the minute ends |

### 7.3 Rules

- **Atomic.** A counter is advanced by one statement, "insert the row or add one, and return the new count". The attempt is counted *before* the secret is checked, and the secret is checked only if the returned count is within the limit. Parallel requests therefore cannot all slip under the limit together. A successful sign-in clears the pair counter for that email and address.
- **Bounded.** `Retry-After` is never more than 15 minutes, 60 for sign-up. Counters do not escalate, and nothing an outsider does disables an account. The worst an attacker can do to a known address is delay its sign-in by 15 minutes at a time; staff are additionally protected by the second factor.
- **Same answer.** A throttled attempt is refused before the password is checked, and identically for existing and unknown addresses.
- **Hash saturation.** Limits are applied before hashing. Hashing itself is capped (section 3): four at once, a 2-second wait, then 503 with `Retry-After: 5`. A flood therefore costs the attacker rate-limit budget and never more than four slots of memory.
- **Private counters.** They store a keyed hash of the email and of the client address, not the values.
- **Client address.** Taken from the connection. A forwarded-for header is honored only when the connection comes from the configured reverse proxy; from anywhere else it is ignored. Behind the proxy, only the right-most address added by that proxy is used.

## 8. Operator procedures

Staff provisioning and recovery policies are **[Approved, owner decisions 4 and 5]**; implementation details are **[M1A design]**. Browser-based staff management belongs to milestone 5. Until then, staff accounts are managed by an operator command run on the server by someone who already has access to it.

### 8.1 The operator command

A mode of the same application (`java -jar … operator`), not a network endpoint. It prompts for what it needs on the terminal.

- No default account exists. No password is ever created, stored in a file, committed, or passed as a command-line argument or environment variable.
- The command never asks for and never prints a password. It prints a **one-time credential** to the terminal, once.
- Every command writes an audit record naming the operating-system user who ran it.
- Every command that changes staff runs under the serialization rule of 8.6.

| Command | Effect |
|---|---|
| `create-super-admin` | Creates a super administrator account, not activated. Prints an activation credential. |
| `create-admin` | Same for an administrator. |
| `set-can-disburse` | Grants or removes the disbursement permission. Ends the account's sessions. |
| `disable-account`, `enable-account` | Ends sessions on disable. |
| `issue-password-reset` | Prints a reset credential. Leaves the second factor untouched. |
| `recover-staff-access` | For a staff member who lost their second factor. See 8.5. |
| `list-staff` | Emails, roles and states. No secrets. |

There is deliberately no command that only removes a second factor.

### 8.2 One-time credentials

- 256 random bits. Stored only as a SHA-256 hash.
- Bound to one account and one purpose: activation, password reset, or staff recovery.
- Valid for 60 minutes (activation, staff recovery) or 30 minutes (reset). Works once: redemption is a conditional update, so two simultaneous redemptions cannot both succeed. Issuing a new one cancels the earlier ones for that account.
- It allows exactly one thing: choosing a password through `POST /auth/set-password`. It signs nobody in.
- Any text is accepted by the API schema; wrong, malformed, expired and used credentials all answer 400 `credential_invalid`.
- The operator gives it to the person through a channel established before the request (8.4), never the channel the request came in on.

### 8.3 First super administrator and staff activation

1. The owner runs `create-super-admin` on the server and receives the activation credential on the terminal.
2. In the browser, the activation page sends the credential and a new password to `POST /auth/set-password`. This opens the 60-minute enrollment window (5.3).
3. The owner signs in, is told `mfa_enrollment_required`, enrolls, and stores the recovery codes.

An administrator is created the same way with `create-admin`. Nobody but the person ever knows their password.

### 8.4 Verifying who is asking

Recovery is only as strong as this check, so its rule is strict.

- **Independent and established.** The operator confirms the request through a way of reaching the person that the operator already had before the request arrived and that does not pass through the account or the channel of the request: for staff, in person or a call to a number the owner already holds; for a donor, the same standard.
- **Not caller-supplied facts.** Details the requester provides (an address, a gift amount, a date, the answer to a question) prove nothing: they can be known to others. They are never the basis for recovery.
- **No proof, no recovery.** If no such established channel exists, the request is refused. A donor in that position creates a new account; in milestone 1 an account holds nothing that cannot be replaced. Nothing from the old account is moved to the new one.
- Every recovery is audited with the operator and the kind of check made.

### 8.5 Recovery

**Lost password** (any account). After the check of 8.4: `issue-password-reset`, credential passed on the established channel, the person sets a new password, all their sessions end. A staff member still needs their second factor to sign in; the reset did not touch it and cannot be used to get around it.

**Lost second factor** (staff, no recovery codes left). A stolen password must not be enough to attach a new authenticator. So `recover-staff-access` does all of this in one transaction, after the check of 8.4:

1. Removes the second factor and every recovery code.
2. **Invalidates the current password.** The account becomes "not activated".
3. Ends every session and cancels every outstanding credential of the account.
4. Issues one fresh staff-recovery credential, bound to that account, printed to the operator's terminal.

The staff member redeems the credential to choose a **new** password, which opens the enrollment window, then signs in and enrolls. Someone holding only the old password can do nothing: it no longer works, and no credential was given to them. Someone who intercepts nothing but knows the old password gains nothing.

This is one deliberate, separately audited request. Losing a password and losing a device remain different procedures, and a password reset alone never leads to enrollment.

### 8.6 The super administrator invariant

**Definitions.**

- A super administrator is **usable** when it is enabled, activated, and has an active second factor.
- The site is **bootstrapped** once a super administrator has become usable for the first time. This is recorded once, in a one-row table, and never unset.

**Invariant.** Once the site is bootstrapped, there is always at least one usable super administrator. Before that, there may be none: this is the only exception, and it is what allows the first account to be created and activated on an empty database.

**Enforcement.** A check at commit is not enough on its own: two transactions that each remove a different super administrator both see the other one still present and both pass. So:

1. **One guard, taken first.** Every transaction that could reduce the set of usable super administrators begins by locking the single row of `identity_guard` (`select … for update`). These are: disabling, demoting or deleting an account; `recover-staff-access`; any change of role; and the completion of enrollment that performs the bootstrap. They therefore run strictly one after another, at the default isolation level, with no retry needed.
2. **Then the check.** Holding the guard, the transaction makes its change and counts the usable super administrators. If the site is bootstrapped and the count would be zero, it fails and rolls back.
3. **And a backstop.** A constraint trigger repeats the count at commit, so a write that reached the tables without taking the guard (a future code path, a manual statement) is still refused when it alone would break the invariant. The guard is what makes concurrent writers safe; the trigger is what makes a forgotten guard visible.

Operations that only add to the set (creating an account, enabling one, completing an ordinary enrollment) cannot break the invariant; they take the guard as well, so that a count never races an addition.

**Consequences.**

- With one usable super administrator, `recover-staff-access`, `disable-account` and demotion are refused for that account. There is no override flag.
- **Recovery path when the only super administrator has lost everything:** someone with server access runs `create-super-admin`, the new account is activated and enrolled, and only then can the old one be recovered or disabled. Adding is always allowed, so the invariant never has to be broken to repair the site. This rests on server access, which is the same trust the first account rested on.
- The owner is advised to keep two usable super administrators, or to store the recovery codes offline.

## 9. CSRF and request rules

### 9.1 CSRF

**[Approved]** Spring Security's CSRF protection; details **[M1A design]**. The token is bound to the server-side session, not to a cookie the browser could be made to send from elsewhere.

**Mechanism.**

- The token is stored in the session on the server. It is not stored in a cookie, so there is nothing for a sibling site to overwrite.
- `GET /auth/csrf` returns `{ headerName: "X-CSRF-TOKEN", token }`. For a browser with no session it first creates the anonymous session of 4.1; that is the bootstrap, and it is why a visitor can submit sign-in or, later, a gift report.
- The frontend keeps the token in memory only. Never in storage, never in a URL.
- Every request using methods other than `GET`, `HEAD` and `OPTIONS` must carry `X-CSRF-TOKEN`. This includes sign-up, sign-in and sign-out, and will include every later public form.
- The value returned is masked differently on each call, so it does not repeat across responses; any value issued for the current session is accepted until that session's token is replaced.

**Invalidation.** The token dies with the session identifier it belongs to. It is replaced at sign-in, second-factor verification, completed enrollment and password change, and destroyed at sign-out and expiry. A token from another session, from before a rotation, or from another browser never matches.

**What 403 `csrf_invalid` guarantees.** The token is checked at step 4 of the order in 4.3: after the path, the origin and the size and declared type of the body, and before rate limits, before the session state, before the body is parsed and before any secret is looked at. The request changed nothing, counted against nothing, and no password or code in it was examined. It is checked before the session state on purpose: a request that cannot prove it came from this site's own page is told nothing about the session.

**What the frontend does after `csrf_invalid`.** A stale token usually means the session changed underneath the page, often in another tab: someone signed out, signed in as another account, or the session expired. The page cannot always tell whether the identity is still the one the person meant: in a pending state `GET /me` carries no account identifier, so two different pending sign-ins look the same. So there is one rule, with no exceptions:

1. Fetch a new token.
2. Read `GET /me` and show the state it reports. If that is not the state the page was showing, clear the page's private state (section 11). In particular, 401 `unauthenticated` here means the session was lost: clear, and show the sign-in screen.
3. **Never send the refused request again automatically.** This holds for every request using a method other than `GET`, `HEAD` and `OPTIONS`, which in this contract means every `POST`, sign-in, sign-up and sign-out included, so a password is never resubmitted without the person asking. Say that the page was out of date and let the person submit again. Text they typed may be kept where that is safe; passwords, codes and credentials are cleared.
4. A `GET` that failed for this reason may be repeated; `GET` requests do not need the token in the first place.

`SameSite=Lax` on the session cookie is a second, independent barrier, not a replacement.

### 9.2 Origin and fetch metadata

- **[Approved]** One origin. No CORS headers are sent at all, so no other site can read a response or send credentials with a scripted request.
- **[M1A design]** Every request using methods other than `GET`, `HEAD` and `OPTIONS` is checked at step 2 of the order in 4.3, before its body, its token and its session are looked at. It is refused with 403 `forbidden` when either of these holds:
  1. An `Origin` header is present and is not exactly the site's own origin (scheme, host and port). `Origin: null` is not the site's origin.
  2. A `Sec-Fetch-Site` header is present and its value is anything other than `same-origin`. This refuses `cross-site`, `same-site` and `none`.
- Both headers are evaluated; one failing is enough. Neither header present is allowed through to the later steps: older browsers and non-browser tools send neither, and the CSRF token still stands in their way.
- The refusal is `forbidden`, not `csrf_invalid`, so the frontend can tell "this request did not come from our own page" from "the token is stale". It changes nothing and counts against nothing.
- `GET`, `HEAD` and `OPTIONS` requests are not subject to this check: they change nothing, and public pages must stay linkable from anywhere.

### 9.3 Bodies

- **Operations that define a body:** `Content-Type` must be `application/json`; anything else is 415. This also blocks cross-site form posts, which cannot send that type without a preflight. At most 16 KiB; larger is 413, refused before parsing. The body is required.
- **Operations that define no body** (`POST /auth/logout`, `POST /auth/mfa/enroll`): send none. No `Content-Type` is needed. A non-empty body is refused with 400 `validation_error`. The JSON-only rule does not apply to them; the CSRF header and the origin check do, and those are what protect them.
- Unknown properties are refused with 400 and field code `unknown_field`. This is the defence against mass assignment: a request cannot smuggle `role`, `canDisburse` or `emailVerified`, and `npm run validate` proves the sign-up schema has no such property.
- Responses are JSON with `X-Content-Type-Options: nosniff`.

### 9.4 Response headers

| Header | Value | Where |
|---|---|---|
| `Cache-Control` | `no-store` | **Every** answer of an identity operation, at every status, errors included (400, 401, 403, 405, 409, 413, 415, 429, 500, 503). No `ETag`. |
| `Cache-Control` | `no-cache` with `ETag` | Successful answers of the seven public read endpoints only, which never depend on who asks. Unchanged from milestone 0B. |
| `Allow` | The supported methods | Every 405 |
| `Retry-After` | Seconds | Every 429, and every 503 of an identity operation |
| `X-Content-Type-Options` | `nosniff` | All |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'` | API responses |
| `Referrer-Policy` | `no-referrer` | API responses |
| `X-Frame-Options` | `DENY` | All |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Production, set at the reverse proxy |
| `Cross-Origin-Resource-Policy` | `same-origin` | API responses |

In `openapi.yaml`, identity operations use their own error responses (`Identity…`, `Unauthenticated`, `Forbidden` and so on), each declaring `no-store`, and public operations keep the shared ones. `npm run validate` fails if an identity operation refers to a response without `no-store`, if a public operation refers to one with it, if a 405 lacks `Allow`, or if a 429 lacks `Retry-After`; `tests/negative-contracts.json` holds the regression cases.

### 9.5 Methods and unknown routes

- A known path with a method it does not support: 405 `method_not_allowed`, with `Allow`.
- An unknown path: 404 `not_found`, whatever the method.
- Never a redirect to a sign-in page, and never an HTML error page, under `/api`.

**Deliberate change from milestone 0B.** There, a known public path called with another method answered 404, as a temporary choice recorded in the review. It becomes 405 in milestone 1B. Nothing else about the seven public endpoints changes: same paths, same parameters, same successful responses, same caching.

### 9.6 Reverse proxy

The application trusts forwarded headers (scheme, host, client address) only from the configured proxy address. It listens on loopback or the internal container network, never on a public address. The proxy strips incoming forwarded headers from clients before adding its own.

### 9.7 Errors

500 responses are the generic `internal_error` with a request identifier and nothing else. Details go to the server log, with secrets excluded as in section 3.

## 10. Data design

Proposed tables for milestone 1B. No migration is written yet. The `identity` module owns all but the last; `audit` owns the last.

| Table | Purpose | Key constraints |
|---|---|---|
| `account` | One row per account: identifiers, emails, display name, locale, role, `can_disburse`, enabled, activated, enrollment window end, email verified, security version | Unique normalized email. Role in a fixed list. `can_disburse` only when role is `admin`. Public identifier unique and immutable. |
| `account_password` | Current password hash and when it was set | At most one row per account; none when not activated. Separate table so ordinary account queries never select the hash. |
| `account_totp` | Encrypted authenticator secret, state (pending or active), pending expiry, last accepted time step | One per account. Activation and code acceptance are conditional updates. |
| `account_recovery_code` | Keyed hash of each code, used instant | Each used at most once, by conditional update. |
| `one_time_credential` | Hash, purpose, account, expiry, used instant | Hash unique. Used at most once, by conditional update. |
| `identity_guard` | One row, locked by every transaction that can change who the usable super administrators are; also records that the site is bootstrapped | Exactly one row. The bootstrapped flag is never unset. |
| `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` | Sessions, in the schema Spring Session documents for PostgreSQL | Created by a Flyway migration, never by automatic initialization. |
| `auth_throttle` | Rate-limit counters: scope, keyed hash of the key, window start, count | Unique on scope, key and window; advanced by one atomic statement. Old windows deleted by a scheduled task. |
| `audit_event` | Append-only record of security events | No update, no delete, same technique as the ledger. |

Deliberate choices:

- **Roles are a column, not tables.** Three fixed roles and one flag. The permission matrix lives in one place in code and is covered by tests. A role-and-permission schema can replace it later without touching the API.
- **Hashes and secrets live in their own tables**, so the query that builds an `Account` response cannot select them by accident. This mirrors `case_private` in milestone 0.
- **Single-use things are consumed by conditional updates**, not by read-then-write, so simultaneous requests cannot both succeed.
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
| Second factor enrolled, recovery codes regenerated | Account |
| Staff recovery performed | Account, operator, kind of identity check made |
| Sign-up refused because it is closed | Client address |
| Request refused as `already_signed_in`, or a state conflict | Account, operation |
| Account created, enabled, disabled | Account, operator |
| Role or `can_disburse` changed | Account, old and new value, operator |
| Session ended by the security-version check | Account, reason |
| Refused change that would leave no usable super administrator | Account, operator |
| Site bootstrapped (first usable super administrator) | Account |

Each record has: instant, event type, outcome, actor (account or operator), target account, client address, request identifier, and a small details object with a fixed set of keys per event type. Passwords, hashes, session identifiers, CSRF tokens, authenticator secrets, codes and one-time credentials are never written, and the details object is built from an allowlist, not from request data.

The same table will take financial administration events later: the columns are generic (actor, target type, target identifier, details).

### 10.2 Development data and real databases

The fixture loader deletes case, ledger and picture data before loading a fixture. Today it accepts an empty database or one whose content came from a fixture. That is not strict enough once accounts exist: an empty production database, before its first account, would look loadable. So the rule becomes explicit. **[M1A design]** for milestone 1B:

- **A database must be marked disposable before anything fictional can enter it.** The marker is a row written deliberately when a development or test database is created: by the initialization script of the local Compose database, and by the test setup. It is never written by the application at start-up, and never by a migration.
- The fixture loader and the development account seed run **only** on a database carrying that marker. An unmarked database, empty or not, is refused. They also keep the existing checks: `dev` or `test` profile only, local database only.
- **A real database can never become disposable.** The operator command refuses to run on a disposable database, and its first use marks the database `real`. The database refuses to change `real` to anything else.
- The loader **never** touches `account`, credentials, sessions, throttles, the guard or audit. Its list of tables is explicit, and a test asserts no identity or audit table is in it.
- Shared fixtures contain no accounts. Development sign-ins come from a separate development-only seed, with obviously fake accounts whose passwords are generated at start-up and printed to the developer's console, never committed.

## 11. Browser rules

For the frontend, which owns their implementation. **[M1A design]**, accepted by the frontend agent. Each has a test in `tests/security-acceptance.md`.

**Secrets.**

- No password, code, recovery code, authenticator secret, one-time credential or CSRF token is ever placed in a URL path or query, in `localStorage`, `sessionStorage`, IndexedDB or a cookie written by script, or in a log, console message, analytics event or error report.
- A one-time credential is typed or pasted into a form field. If it ever travels in a link, it is in the fragment (after `#`), which is not sent to the server or in a referrer, and the page removes it from the address bar and history before doing anything else.
- The authenticator QR code is drawn in the page from the `otpauth://` address. That address is never sent to any service, including an image service.
- Recovery codes and the authenticator secret are held only in the memory of the page that shows them and are gone when it is left.

**Storage.** `localStorage` may keep preferences that are not private: language and colour theme. Nothing about the account, the session or its permissions.

**Private state must not outlive the identity.** Everything the page holds about the account is cleared, and private screens are left, when the session is really gone:

- the person signs out;
- a request answers 401 `unauthenticated` (see the table below; the other 401 codes do not mean this);
- `GET /me` shows another account, or a state other than the one the page was showing;
- another tab signs out or signs in as someone else. Tabs tell each other, and each tab re-reads `GET /me` when it becomes visible again;
- the page is restored from the back-forward cache: it re-reads `GET /me` before showing anything private. `no-store` responses already keep private pages out of that cache in most browsers; the re-check covers the rest.

**What each 401 code means for the page.** Not every 401 is a lost session (4.7), and treating them alike would sign a person out for mistyping a password.

| Code | The page |
|---|---|
| `unauthenticated` on any request | The session is gone. Clear private state, then read `GET /me` once to confirm and to learn where to go, and show the sign-in screen. During a second-factor or enrollment step this means the pending sign-in ended (fifth wrong code, closed window): return to the password step with a message, keeping nothing. |
| `csrf_invalid` (403) on any `POST` | Not a verdict on the session yet. Fetch a token, read `GET /me`, then act on what it says: `unauthenticated` means the session was lost, so clear and show sign-in (from a challenge: back to the password step); a pending code means show that challenge; 200 means still signed in. In every case the refused request is not sent again; the person submits again (9.1). |
| `forbidden` (403) caused by the origin check | Should not happen from the site's own page. Show a generic error; do not resend. |
| `mfa_required` (from `GET /me` or a protected request) | Not a lost session. Show the second-factor challenge. Never the generic sign-in screen. |
| `mfa_enrollment_required` | Not a lost session. Show the enrollment screen. |
| `invalid_credentials` on sign-in | Stay on the sign-in screen and show the error. |
| `invalid_credentials` on password change or recovery-code regeneration | The person is still signed in. Stay on the screen, show the error on the current-password field, keep private state, do not call sign-out and do not navigate. |
| `mfa_code_invalid` on second-factor verification | The pending sign-in is kept. Stay on the challenge, show the error, clear the code field, let the person try again. |
| `mfa_code_invalid` on enrollment confirmation | The pending session is kept. Show the error; if a fresh code also fails, start enrollment again to get a new secret. |
| `mfa_code_invalid` on recovery-code regeneration | Still signed in. Show the error on the code field. |

`GET /me` is the one place the frontend learns the state at load: 200 means signed in; a 401 there is expected and routes by its code as above.

**Theme before first paint.** `index.html` currently sets the colour theme with an inline script. In milestone 1B that script must move to a self-hosted file so the page content policy can forbid inline script, while the theme is still applied before the first paint. The policy is not loosened for it.

**No fallback to mock data.** In real-API mode, a failed identity call is an error shown to the person. It never falls back to mock data, and a production build cannot contain the mock identity.

**Page content policy.** The page that hosts the application is served with a content security policy that allows scripts, styles, fonts and images only from the site itself, no inline script, no `eval`, connections only to the site itself, and no framing. The exact policy is written against the real production build, and a test loads every identity screen from that build with the policy enforced and expects no violation. The frontend agent owns the policy text.

**Screens.** Sign-in, sign-up, second factor, enrollment, recovery codes, set password and account are tested in English and French, in both colour themes, at phone width, with the keyboard alone, with error messages announced to assistive technology and tied to their fields, and with browser autofill and password managers working (`autocomplete` set to `username`, `current-password`, `new-password` and `one-time-code` as appropriate).

## 12. Threat model

What is defended, how, and what is left.

| Threat | Defence | Residual risk |
|---|---|---|
| Password guessing online | Rate limits; 15-character minimum; blocked-password list; second factor for staff | A weak but long, unlisted donor password |
| Stolen database | Argon2id; encrypted authenticator secrets with the key outside the database; hashed recovery codes and one-time credentials | Offline guessing of weak passwords, slowed by the work factor |
| Session theft through script injection | `HttpOnly` cookie; no token in storage; strict content policy on API responses | An injected script can still act as the person while the page is open. The frontend's own content policy and review are the defence. |
| Session fixation | New identifier at every privilege change; `__Host-` cookie | None known |
| Cross-site request forgery | CSRF token bound to the server-side session on every non-GET; `SameSite=Lax`; JSON-only bodies; origin check | None known |
| An action performed as the wrong identity after another tab changed the session | After `csrf_invalid` no `POST` is ever resent automatically; the page reads `GET /me` and the person submits again (9.1) | The person must act again |
| Privilege escalation by request fields | Closed request schemas; unknown fields refused; no role field on sign-up | None known |
| Reading another person's data | No identifier in M1 requests; later, lookups by session | To be re-examined for each new endpoint |
| Stale privileges after demotion | Security version checked on every request; sessions deleted | None known |
| Account enumeration | Identical sign-in errors and timing; identical sign-up answer; sign-up closed in production until address proof (proposed) | If sign-up is opened without address proof, sign-up followed by sign-in reveals existence |
| Preclaiming someone's address | Sign-up closed in production (proposed); an unverified address grants nothing; staff never come from sign-up | If opened early, a squatter can hold an address until proof exists |
| Lockout abuse | No permanent lockout; pair-keyed limits | A temporary delay can be imposed on a known address |
| Second-factor bypass through recovery | A password reset never touches the second factor. Staff recovery invalidates the old password and binds a fresh credential to the account, so a stolen password cannot enroll. Enrollment is possible only inside the window opened by a credential. | Depends on the operator's independent identity check (8.4) |
| Real-time phishing of codes | None in milestone 1 beyond short code life | Authenticator and recovery codes are not phishing-resistant |
| Losing every super administrator, or two removals racing | One serialization guard for all such writes, a commit-time backstop, and adding is always allowed (8.6) | None known |
| Code replay, including two simultaneous uses | Conditional update of the last time step; conditional consumption of recovery codes and credentials | None known |
| Compromised operator or server | Audit records; no stored passwords | Someone with server access can create a super administrator. This is the trust root and cannot be removed, only watched. |
| Secrets in logs | Identity bodies never logged; audit details from an allowlist | A future developer adding a log line; covered by a leak test |
| Cached private responses | `no-store`, no `ETag`, on every identity response | None known |
| Denial of service through hashing | Concurrent hash limit; rate limits; body size limit | A distributed flood needs the reverse proxy or the host to absorb it |
| Fictional data or seeded accounts in production | Loader and seed exist only in `dev` and `test`, on a local database explicitly marked disposable; a real database can never be marked so | None known |

Not in scope for M1: email-based flows, passkeys, device trust, single sign-on, self-service account deletion, and browser-based staff management.
