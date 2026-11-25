# Milestone 1A review

Contract and security design for identity. Nothing here is implemented: no Spring Security configuration, no controller, no migration, no page.

- Baseline: `main` at `a7fbab22f837bdadc495d307b7fafdf1e83554ad` (milestone 0B accepted).
- First revision: `bd162cdeb3b98d38aa92f8a71162cab48abc0943`. Reviewed in pull request 8: changes required.
- Second revision: `022b576a6212ecde4b61f9c358ad4c1fde420644`, answering that review finding by finding (section 2). Accepted, with two narrow clarifications requested.
- This revision makes those two clarifications (section 2a).
- Contract version: 0.3.0. The seven public read endpoints are unchanged, apart from documenting 405.

File names below are in `contract/` unless they start with another folder. "I&S" is `identity-and-security.md`; "tests" is `tests/security-acceptance.md`.

## 1. Operations

| Method | Path | Accepted session states | Answers |
|---|---|---|---|
| GET | `/api/auth/csrf` | Any | 200 token; creates an anonymous session if there is none |
| GET | `/api/auth/options` | Any | 200 `registrationOpen` |
| POST | `/api/auth/register` | Not fully signed in | 202, always the same; 403 when closed |
| POST | `/api/auth/login` | Not fully signed in | 200 signed-in or pending form; 401 |
| POST | `/api/auth/logout` | Any | 204 |
| GET | `/api/me` | Fully signed in | 200 account; 401 naming the state |
| POST | `/api/auth/change-password` | Fully signed in | 204 |
| POST | `/api/auth/set-password` | Not fully signed in | 204 |
| POST | `/api/auth/mfa/verify` | Awaiting second factor | 200 signed-in form only |
| POST | `/api/auth/mfa/enroll` | Awaiting enrollment | 200 secret, once |
| POST | `/api/auth/mfa/confirm` | Awaiting enrollment | 200 recovery codes, once |
| POST | `/api/auth/mfa/recovery-codes` | Fully signed in, staff | 200 recovery codes, once |

Twelve operations: the eleven of the first revision and `GET /api/auth/options`, added so the frontend can know that sign-up is closed (finding 2).

## 2. Findings of the review and their resolution

### Finding 1: the last super administrator

| Point | Resolution | Where |
|---|---|---|
| A deferred trigger alone does not serialize competing removals | Every transaction that can change the set of usable super administrators first locks one guard row. The commit-time trigger stays as a backstop for a write that forgot the guard. Serializable isolation with retries was considered and not chosen. | I&S 8.6; `docs/adr/0002-identity-and-sessions.md` |
| Which writes | Disable, demote, delete, any role change, staff recovery, and the enrollment that performs the bootstrap; additions take it too | I&S 8.6 |
| Bootstrap exception | Zero is allowed only until a super administrator first becomes usable; that moment is recorded once and never unset | I&S 8.6 |
| "Usable" | Enabled, activated, with an active second factor | I&S 8.6 |
| Recovery path | Adding is always allowed: create a new super administrator, make it usable, then recover or disable the old one. No override flag. | I&S 8.6 |
| Tests | Concurrent removal of two administrators (three variants), concurrent first activation, unenrolled administrator not counted, write without the guard, recovery path | tests 7.3, 7.4, 7.13 to 7.18 |

### Finding 2: sign-up, preclaimed addresses and recovery

| Point | Resolution | Where |
|---|---|---|
| Keep public sign-up disabled in production until address proof | Proposed as owner decision 1. A server setting, closed by default; production refuses to start with it open. The contract, development path and tests remain. | I&S 2.2; `openapi.yaml` `POST /auth/register`, `GET /auth/options`, `registration_closed` |
| Preclaimed addresses | An unverified address proves and grants nothing; staff never come from sign-up; proof of the address detaches a preclaimed account and never merges it | I&S 2.2; tests 1.12 |
| Donor manual recovery only with established independent proof | A channel the operator already had before the request; facts supplied by the requester prove nothing; with no such channel, recovery is refused and the person makes a new account | I&S 8.4; tests 7.20 |
| Staff second-factor reset must not let a stolen old password enroll | `reset-mfa` is removed. `recover-staff-access` removes the factor, invalidates the old password, ends sessions, cancels credentials and issues one fresh credential bound to the account. Enrollment is possible only within 60 minutes of redeeming a credential. | I&S 5.3, 8.1, 8.5; tests 6.20 to 6.22 |
| A password reset alone must not bypass the second factor | Unchanged and restated | I&S 8.5; tests 6.19 |

### Finding 3: CSRF and the session lifecycle

| Point | Resolution | Where |
|---|---|---|
| Session-bound CSRF, anonymous bootstrap, cookie and header details, invalidation | The token lives in the server-side session, not in a cookie. `GET /auth/csrf` creates a short anonymous session for a browser that has none. Header `X-CSRF-TOKEN`. One cookie only. Replaced at every identifier change, destroyed at sign-out and expiry. | I&S 4.1, 4.2, 9.1; `openapi.yaml` `getCsrfToken`, `CsrfHeader`, `CsrfToken` |
| `csrf_invalid` guarantees rejection before any change | Checked before rate limits and before the body is read; an explicit order of checks | I&S 4.3, 9.1; tests 4.9 |
| Never replay account-sensitive mutations after an identity change in another tab | Superseded by clarification 2 in section 2a: no `POST` is ever resent automatically | I&S 9.1; tests 4.13 to 4.16b |
| Sign-in, sign-up, set-password when already signed in | 409 `already_signed_in`; nothing changes | I&S 4.3; `openapi.yaml` `AlreadySignedIn`; tests 3.14 |
| Expiry during a pending second factor | 401 `unauthenticated`; `pendingExpiresAt` in the pending result | I&S 4.4; tests 3.16 |
| Pending enrollment concurrency | One pending secret per account, the latest wins; simultaneous confirmations: exactly one succeeds (the loser's error is refined in section 2a) | I&S 5.3; tests 6.3 to 6.5 |
| Refresh after a lost success response | A table for every state-changing call; never resend a secret, read `GET /me` | I&S 4.6; tests 3.17 |
| Concurrent code and recovery-code consumption; security-version race | Single-use things are consumed by conditional updates. Privileged writes re-read and lock the account row. | I&S 5.2, 5.4, 6.3, 10; tests 5.12, 5.13, 6.7, 6.12, 7.9 |

### Finding 4: OpenAPI

| Point | Resolution | Where |
|---|---|---|
| `LoginResult` allowed pending with an account, and authenticated with none | Now `oneOf` two closed forms: `SignedIn` (constant status, account required) and `SecondFactorPending` (no account property, `pendingExpiresAt`) | `openapi.yaml` |
| Generated TypeScript | `LoginResult = SignedIn \| SecondFactorPending`, a discriminated union | `src/api/generated/identity-api.ts` |
| `verifyMfa` success authenticated only | Its 200 is `SignedIn` | `openapi.yaml`; checker rule |
| Method-specific code shapes | `MfaVerifyRequest` is `oneOf` `TotpVerification` (six digits) and `RecoveryCodeVerification` (recovery pattern) | `openapi.yaml` |
| Negative cases | 7 responses and 8 requests added; 41 contract mutations | `tests/negative-responses.json`, `negative-requests.json`, `negative-contracts.json` |
| Deterministic 401 and 403, pending and full state rules | `x-session-state` on every identity operation; one table of answers per state; an order of checks | `openapi.yaml`; I&S 4.3; tests 3.18 |
| Generator exposes the state requirement | `allowedStates` replaces `needsSession`; `SessionState` type exported | `tools/generate-public-types.mjs` |
| Bodyless POST against the JSON-only rule | The rule applies where a body is defined. Bodyless operations send none; a non-empty body is 400. | `openapi.yaml` conventions; I&S 9.3; tests 9.7 |
| Email trimming against the pattern | The server does not trim; surrounding white space is `invalid_format`; the frontend trims | `openapi.yaml` `EmailAddress`; I&S 2.1; tests 1.4 |
| Malformed credential error | The credential schema has no pattern, so any text reaches the server and gets the same `credential_invalid` | `openapi.yaml` `OneTimeCredential`, `SetPasswordRejected`; tests 7.6, 7.7 |
| Status after the fifth second-factor failure | 401 `unauthenticated`; the pending session is ended | `openapi.yaml` `verifyMfa`; I&S 5.2; tests 6.10 |
| Stale pending enrollment | 401 `mfa_code_invalid`; start enrollment again | `openapi.yaml` `confirmMfaEnrollment`; I&S 5.3; tests 6.3 |

### Finding 5: test matrix, rate limits, headers

| Point | Resolution | Where |
|---|---|---|
| 1.4: twenty sign-ups against five per address | The twenty come from twenty client addresses | tests 1.5 |
| 5.9: revocation should give 401 first | Rewritten: the next request is 401 | tests 5.9 |
| 5.3: operational permissions only | `Permission` now lists only the two operational ones; every role gets exactly those in M1; planned names stay in the document, outside the schema | `openapi.yaml` `Permission`; I&S 1.2; tests 5.3, 5.4 |
| 9.9: approved storage | Language and theme may be stored; nothing private | I&S 11; tests 10.3 |
| Atomic rate limits | One statement counts the attempt and returns the count, before the secret is checked | I&S 7.3; tests 8.5 |
| Bounded cooldown | `Retry-After` at most 15 minutes, 60 for sign-up; no escalation, no lockout | I&S 7.2, 7.3; tests 8.3, 8.6 |
| Hash saturation | Four at once, 2-second wait, then 503 with `Retry-After: 5`; limits applied before hashing | I&S 3, 7.3; tests 8.11, 8.12 |
| Limits across all password and second-factor checks | A table naming every operation that checks a secret and the counters it draws on | I&S 7.1; tests 8.9 |
| Headers on authentication errors | Every identity answer at every status is `no-store`; every 405 has `Allow`; every 429 and identity 503 has `Retry-After` | I&S 9.4; tests 9.1, 9.3 |
| **Shared responses lacked `no-store` although the checker passed** | Confirmed, and it was a real gap: the checker only looked at 2xx, 401 and 403. Identity operations now use their own error responses, each declaring `no-store`; the checker examines every status; 13 regression mutations cover it. | `openapi.yaml` `Identity…` responses; `tools/check-openapi.mjs`; `tests/negative-contracts.json` |
| Preserve public successes; document the deliberate 404 to 405 | Stated in the contract and the document; public operations keep the shared responses and the checker forbids them a `no-store` one | `openapi.yaml` conventions; I&S 9.5; tests 11.1, 13.1 |

### Finding 6: passwords and recovery codes

| Point | Resolution | Where |
|---|---|---|
| NFC, not NFKC | Changed, with the reason | I&S 3; tests 2.4, 2.5 |
| Lengths before and after normalization | At most 128 as sent; 15 to 128 after NFC | I&S 3; `openapi.yaml` `NewPassword`; tests 2.6, 2.7 |
| Context-specific blocklist behavior | An exact rule: site names, email first part, display name, refused when fewer than 8 other characters remain | I&S 3; tests 2.10, 2.11 |
| Recovery codes are substitutes, not phishing-resistant | Stated for both recovery and authenticator codes | I&S 5.2, 5.4; threat model |

### Finding 7: browser, and the real-database guard

| Point | Resolution | Where |
|---|---|---|
| No secret in URL, storage or logging; local QR only | Rules and tests | I&S 11; tests 10.1 to 10.4, 6.23 |
| Clear private state on sign-out, expiry, identity switch in another tab, back-forward cache | Rules and tests | I&S 11; tests 10.5 to 10.8 |
| Page-level content policy with real production assets | Required and tested against the production build; the policy text is the frontend agent's | I&S 11; tests 10.9 |
| English and French, both themes, mobile, accessible errors, keyboard, autofill | Tests | tests 10.12 to 10.16 |
| No real-to-mock fallback | Rule and tests | I&S 11; tests 10.10, 10.11 |
| The real-database guard must be explicit | Fictional data can only enter a database explicitly marked disposable when it was created. An unmarked database is refused, empty or not. A real database can never be marked disposable. | I&S 10.2; tests 13.3 to 13.6 |

## 2a. Final clarifications, after the review of `022b576`

### Clarification 1: a 401 is not always a lost session

| Point | Resolution | Where |
|---|---|---|
| "Any 401 clears private state" conflicted with wrong-password and wrong-code answers | The five 401 codes are split into two kinds: three describe the session, two describe only the secret sent, with the session unchanged. The server guarantees both directions. | I&S 4.7; `openapi.yaml` conventions, `Unauthenticated`, `InvalidCredentials`, `MfaCodeInvalid`, `changePassword` |
| Per-error handling in the browser | A table: what the page does for each code on each kind of screen. Only `unauthenticated` clears private state. | I&S 11 |
| Reconcile through `GET /me` on real loss | On `unauthenticated`: clear, read `GET /me` once, route by what it says | I&S 11; tests 10.6 |
| Pending challenge kept on retryable errors | `mfa_code_invalid` keeps the pending session, on verification and on enrollment confirmation | I&S 4.7, 5.3; `openapi.yaml`; tests 6.5b, 6.10, 10.6b |
| `GET /me` 401 while pending routes to the challenge | Stated; never the generic sign-in screen | I&S 11; tests 10.6e |
| Browser tests: wrong current password, wrong code, fifth failure | Added, with expiry during a challenge and regeneration | tests 10.6a to 10.6f; API-level 6.10a, 6.10b, 2.15 |

### Clarification 2: no automatic resubmission, and concurrent enrollment

| Point | Resolution | Where |
|---|---|---|
| Identity comparison cannot work in pending states | Removed. `GET /me` has no account identifier while pending, so the rule no longer depends on comparing identities. | I&S 9.1 |
| Automatic replay of sign-in, sign-up and sign-out was still permitted | Removed. After `csrf_invalid` no `POST` is ever sent again automatically. Fetch a token, read `GET /me`, the person submits again. A `GET` may be repeated. | I&S 9.1; `openapi.yaml` conventions and `getCsrfToken`; tests 4.13 to 4.16b |
| The loser of a concurrent confirmation | The guarantees are fixed (one success, one active factor, one recovery set, nothing disclosed to a loser). The loser's error depends on what it presents, in a four-row table: 409, 403, 401 `unauthenticated`, or 401 `mfa_code_invalid`. | I&S 5.3; `openapi.yaml` `confirmMfaEnrollment`; tests 6.5 |
| Enrollment-window expiry at confirmation | The window is checked again at confirmation; closed means 401 `unauthenticated`, pending session ended, nothing activated | I&S 5.3; `openapi.yaml`; tests 6.5a |
| Frontend note: inline theme initializer | Recorded for milestone 1B: move to a self-hosted script, keep pre-paint behavior, do not loosen the policy | I&S 11; tests 10.9a |

No schema, operation, generated type or tooling changed in this revision: the two generated files are identical to `022b576`. Only descriptions in `openapi.yaml`, the design document, the test matrix and this file changed. The fixture storage strategy is untouched.

## 2b. Precedence corrections, after the independent acceptance review of `549bb55`

Conditional pass, with two precedence inconsistencies confirmed by the frontend agent. Both are resolved by fixing one order of checks and stating which session exists at each step.

| # | Requirement | Resolution | Where |
|---|---|---|---|
| 1 | Keep CSRF validation before session-state authorization | Kept, and made explicit as an eight-step table: path and method, origin and fetch metadata, body size and type, CSRF, rate limits, session state, body validation, the operation's own check. A secret is examined only in the last step. | I&S 4.3; `openapi.yaml` conventions |
| 2 | Expired-session 403 against 401 contradiction | A session that was deleted, rotated or has passed a deadline does not exist, from that instant and at every step. A `POST` still carrying its token is 403 `csrf_invalid`. 401 `unauthenticated` on a `POST` happens only with a valid token: a fresh anonymous one, or a revoked identity whose row survived. A `GET` is 401 directly. | I&S 4.3, 4.4, 4.7, 9.1; `openapi.yaml` conventions, `Unauthenticated`, `Forbidden`, `MfaCodeInvalid`, `verifyMfa` |
| 3 | Sweep all related tests and prose, not only 6.10a and 10.6f | Revised: tests 3.3, 3.4, 3.6, 3.8, 3.16, 3.18, 4.6, 5.9, 5.10, 5.11, 6.5, 6.10, 6.10a, 10.6, 10.6c, 10.6f; added 3.16a. Prose: I&S 4.4 (pending expiry), 4.6 (lost answer of a password change), 4.7 (table and a new paragraph), 5.3 (the loser table: a pre-rotation identifier is 403, a fresh anonymous token is 401), 11 (a `csrf_invalid` row in the per-code table). | I&S; tests |
| 4 | Deterministic Origin and fetch-metadata precedence | Step 2, before the body and the token. Refused with 403 `forbidden` when `Origin` is present and not the site's own (`null` included), or `Sec-Fetch-Site` is present and not `same-origin`. Either is enough; neither present passes to the later steps. Never `csrf_invalid`. Not applied to `GET`. | I&S 9.2; `openapi.yaml` `Forbidden`; tests 4.10, 4.10a, 4.11, 4.11a, 4.11b |
| 5 | Focused scenarios: stale cookies, stale tokens, fresh anonymous tokens, revoked identities, several invalid conditions | A new group of 32 tests | tests 14.1 to 14.32 |
| 6 | Keep the ban on automatic resubmission | Unchanged, and restated in the header of the new group and in the per-code table | I&S 9.1, 11; tests 14.11 |

Two edge cases became explicit while writing the scenarios: a revoked identity calling an operation that accepts `anonymous` has its session ended and proceeds as anonymous (not 409), and a closed sign-up is refused at the session-state step (I&S 4.3; tests 14.17, 14.30).

No schema, operation, generated type or tooling changed: both generated files are identical to `022b576`. Only description text in `openapi.yaml`, the design document, the test matrix and this file changed. Successful public responses and the fixture storage strategy are untouched.

## 3. Contract tooling

| Requirement | How |
|---|---|
| Public privacy protections preserved | The original rules apply to every `public` and `shared` schema, and also forbid `role`, `permission`, `mfa` and `credential` there |
| Public and private schemas distinguished | Every schema declares `x-audience` |
| Private properties cannot enter public responses | The checker follows references from every public read response |
| What the client sends is never echoed | No response may reach a `request` schema |
| Secrets returned only on purpose | `credential` schemas only from four named operations |
| Identity answers never cached, at any status | Checked for every response of every identity operation |
| Public answers never borrow an identity response | Checked |
| `Allow` and `Retry-After` | Checked |
| Session states | `x-session-state` checked against the security requirement and the documented 401 and 409 |
| Discriminated sign-in result | Checked |
| The rules themselves are tested | `tests/negative-contracts.json`: 41 damaged contracts, each must be rejected for the stated reason |
| TypeScript generation | Request bodies, `POST`, non-200 success, unions, `allowedStates` |
| Existing public exports preserved | `PublicReadOperations` and `publicReadPaths` unchanged |

## 4. Validation results

`cd contract && npm ci && npm run validate`, Node 24.22.3.

| Check | Result |
|---|---|
| Redocly lint | Pass, no errors or warnings |
| References, examples | 340 references, 45 examples including request bodies |
| Closed object schemas | 38 |
| Operations | 7 public, 12 identity |
| Wrong responses and requests rejected | 76 of 76 |
| Wrong contracts rejected | 41 of 41 |
| Example fixture | Pass, unchanged |
| Full dataset | Pass, 1,806 responses |
| Frontend mock parity | Pass, 1,838 responses |
| Generated types match | Pass, both files |
| Frontend build (`npm run build`) | Pass |
| Backend unit tests | Pass, 38 |

## 5. Changes to shared files, for the frontend agent

1. `src/api/generated/public-api.ts`: compared with `main`, three lines differ (version comment, `ErrorCode`, field error codes). Compared with the first revision, one line: two more error codes (`already_signed_in`, `registration_closed`).
2. `src/api/generated/identity-api.ts`: regenerated. `needsSession` is replaced by `allowedStates`; `LoginResult` and `MfaVerifyRequest` are unions; `Permission` has two members; `AuthOptions` and `getAuthOptions` are new; the CSRF header name is `X-CSRF-TOKEN`.
3. One backend test line expects contract version 0.3.0 (unchanged from the first revision).

## 6. Owner decisions needed

All are proposals. None is treated as approved anywhere in this pull request.

| # | Decision | Recommendation |
|---|---|---|
| 1 | Keep public sign-up closed in production until the site can verify an email address? While closed, only staff have accounts; donors give and report without one, as today. | Yes, keep it closed. This follows the independent review and replaces my earlier recommendation to open it. |
| 2 | Session lifetimes: donors 2 hours idle and 24 hours at most; staff 30 minutes idle and 12 hours at most. | Accept. |
| 3 | Mandatory second factor for administrators and super administrators, by authenticator application with ten recovery codes. | Accept. |
| 4 | Staff accounts and recoveries are done by you with a command on the server until milestone 5. | Accept. |
| 5 | Recovery only after you confirm the person through a way of reaching them you already had. Otherwise refuse; a donor makes a new account. | Accept. |
| 6 | Password rules: at least 15 characters, no composition rules, a local blocked list. | Accept. |
| 7 | Email addresses are ASCII only for now. | Accept; revisit if a real person is refused. |
| 8 | Keep at least two usable super administrators, or store recovery codes offline. With one, losing its second factor needs server access to repair. | Advice, not a system rule. |

## 7. Limits and risks

- Three points of fit with Spring Security and Spring Session are read from documentation, not run; a fourth was added for the session-stored CSRF token. They are listed in `docs/adr/0002-identity-and-sessions.md` and are the first task of milestone 1B. The API contract does not depend on them.
- Authenticator and recovery codes are not phishing-resistant.
- The operator's identity check is a human procedure. The system records it and cannot enforce it.
- Rate-limit and hashing numbers are starting values, not measured ones.
- Session-bound CSRF creates a short-lived session row for each visitor who opens a posting form. Bounded by expiry and rate limits, not eliminated.
- Three new production secrets need safe storage and separate backup.
- The disposable-database marker changes how the existing fixture loader decides (today it accepts an empty database). That is milestone 1B work and will touch milestone 0B code and tests.

## 8. Acceptance checklist

Frontend agent:

- [ ] Each of the seven findings is resolved, or countered.
- [ ] The CSRF rule after `csrf_invalid` (I&S 9.1) and the per-code handling of 401 (I&S 4.7, 11) are implementable as written.
- [ ] `identity-api.ts` has the shape the frontend wants.
- [ ] The browser tests (section 10 of the test matrix) are feasible.

Backend agent:

- [x] Every rule can be enforced on the approved stack with no dependency upgrade.
- [x] Every identity response and request is covered by a closed schema and negative cases, and the checker's rules by contract mutations.
- [ ] The four points in ADR 0002 are confirmed at the start of milestone 1B.

Project owner:

- [ ] The decisions in section 6.
- [ ] Authorization to merge this contract and to start milestone 1B.
