# Milestone 1A review

Contract and security design for identity. Nothing here is implemented: no Spring Security configuration, no controller, no migration, no page.

- Baseline: `main` at `a7fbab22f837bdadc495d307b7fafdf1e83554ad` (milestone 0B accepted).
- Contract version: 0.3.0. The seven public read endpoints are unchanged, apart from documenting 405.

## 1. What this pull request contains

| File | Content |
|---|---|
| `contract/openapi.yaml` | Eleven identity operations, their schemas, the session security scheme, new error codes, audiences on every schema |
| `contract/identity-and-security.md` | Permission matrix, accounts, passwords, sessions, second factor, revocation, rate limits, operator procedures, CSRF and request rules, data design, audit events, threat model |
| `contract/tests/security-acceptance.md` | The security test matrix milestone 1B must pass |
| `contract/tests/negative-responses.json` | 20 more wrong responses that must be rejected |
| `contract/tests/negative-requests.json` | 21 wrong requests that must be rejected |
| `contract/tools/check-openapi.mjs` | Audience boundaries and rules for identity operations |
| `contract/tools/generate-public-types.mjs` | Also generates `identity-api.ts` |
| `contract/tools/lib.mjs` | Finds the success example of operations that do not answer 200 |
| `src/api/generated/public-api.ts`, `identity-api.ts` | Regenerated output. See section 5. |
| `docs/adr/0002-identity-and-sessions.md` | Technical choices, compatibility evidence, alternatives |
| `backend/…/ExampleFixtureIT.java` | One line: the contract version it expects. See section 5. |

## 2. Operations

| Method | Path | Needs | Answers |
|---|---|---|---|
| GET | `/api/auth/csrf` | Nothing | 200 token |
| POST | `/api/auth/register` | CSRF | 202, always the same |
| POST | `/api/auth/login` | CSRF | 200 with a status, or 401 |
| POST | `/api/auth/logout` | CSRF | 204 |
| GET | `/api/me` | Full session | 200 account, or 401 naming the state |
| POST | `/api/auth/change-password` | Full session, CSRF | 204 |
| POST | `/api/auth/set-password` | CSRF, one-time credential | 204 |
| POST | `/api/auth/mfa/verify` | Session awaiting second factor, CSRF | 200 |
| POST | `/api/auth/mfa/enroll` | Staff session awaiting enrollment, CSRF | 200 secret, once |
| POST | `/api/auth/mfa/confirm` | Same, CSRF | 200 recovery codes, once |
| POST | `/api/auth/mfa/recovery-codes` | Full staff session, CSRF, password and code | 200 recovery codes, once |

The six operations the assignment proposed are all kept. The other five are the minimum for the two things milestone 1 cannot work without: staff cannot get a password without a shared one (`set-password`), and staff cannot be required to use a second factor without enrolling and verifying it.

Not added: email verification, self-service password reset, session listing, profile editing. None is needed for the agreed scope.

## 3. Contract tooling

| Requirement | How |
|---|---|
| Public privacy protections preserved | The original rules still apply to every `public` and `shared` schema, and now also forbid `role`, `permission`, `mfa` and `credential` there |
| Public and private schemas distinguished | Every schema declares `x-audience`; a missing one fails |
| Private properties cannot enter public responses | The checker follows references from every public read response and fails if it reaches an `account`, `request` or `credential` schema |
| What the client sends is never echoed | No response may reach a `request` schema, so a password type cannot appear in an answer |
| Secrets returned only on purpose | A `credential` schema may be returned only by the four operations named in the checker |
| Even the account's own view is limited | `account` schemas may not contain properties named like hashes, secrets, tokens, counters or lock state |
| Objects stay closed | Unchanged, for all audiences |
| Identity responses never cached | Each must declare `Cache-Control: no-store` |
| CSRF on every non-GET | Each must declare the header and a 403 |
| Sign-up cannot elevate | The checker fails if the sign-up schema gains `role`, `permissions`, `canDisburse`, `emailVerified`, `id` or `mfa` |
| TypeScript generation extended | Request bodies, `POST`, non-200 success; identity types in their own file |
| Existing public exports preserved | `PublicReadOperations` and `publicReadPaths` are byte-for-byte the same |
| Drift checks | `npm run check:types` now covers both generated files |
| Earlier tests kept | Fixtures, dataset, frontend parity: unchanged and passing |

## 4. Validation results

`cd contract && npm ci && npm run validate`, Node 24.22.3.

| Check | Result |
|---|---|
| Redocly lint | Pass, no errors or warnings |
| References, examples | 302 references, 37 examples including request bodies |
| Closed object schemas | 35 |
| Operations | 7 public, 11 identity |
| Wrong responses and requests rejected | 62 of 62 |
| Example fixture | Pass, unchanged |
| Full dataset | Pass, 1,806 responses |
| Frontend mock parity | Pass, 1,838 responses |
| Generated types match | Pass |
| Frontend build (`npm run build`) | Pass |

The checker was also run against damaged copies (a private property on a public schema, a public schema referring to the account, a role field on sign-up, a missing CSRF header, a request schema returned in a response); each failed as intended.

## 5. Changes beyond the assignment, and to shared files

For the frontend agent to accept or counter.

1. **`src/api/generated/public-api.ts` regenerated.** Three lines differ: the version comment, `ErrorCode` (new codes) and the field error codes. Nothing else.
2. **`src/api/generated/identity-api.ts` is new.** Generated; it lives beside the public file because the drift check needs a committed copy. No frontend code imports it yet.
3. **One backend test line** now expects contract version 0.3.0. Without it the backend workflow would fail on this branch. No backend behavior changed.
4. **405 documented on the seven public endpoints.** Additive: their success responses are untouched.
5. **Five field error codes added:** `too_short`, `invalid_format`, `unknown_field`, `compromised`, `same_as_current`.
6. **`Account.permissions`.** Not asked for; added so the frontend never derives permissions from the role itself.
7. **`Account.session`** with both deadlines, so the frontend can warn before a session ends.
8. **An origin check** on non-GET requests, in addition to the CSRF token.
9. **A cap on simultaneous password hashes**, to keep sign-in from exhausting memory.

## 6. Owner decisions needed

These are new. No milestone 0 decision is re-opened.

| # | Decision | Recommendation |
|---|---|---|
| 1 | Sign-up cannot fully hide whether an address is registered until the site sends email (see `identity-and-security.md` 2.2). Accept this for now, or keep sign-up closed until email exists? | Accept, with the rate limits. Accounts are optional for giving, so little depends on them yet. |
| 2 | Session lifetimes: donors 2 hours idle and 24 hours at most; staff 30 minutes idle and 12 hours at most. | Accept as proposed. |
| 3 | Mandatory second factor for administrators and super administrators, by authenticator application with ten recovery codes. | Accept. |
| 4 | Staff accounts, password resets and second-factor resets are done by you with a command on the server until milestone 5. | Accept. It means only someone with server access can create staff. |
| 5 | Password recovery is manual: you verify the person, then issue a 30-minute single-use credential. | Accept until email exists. |
| 6 | Password rules: at least 15 characters, no composition rules, a local blocked-password list. | Accept. Which blocked list to ship is a milestone 1B detail. |
| 7 | Email addresses are ASCII only for now. | Accept; revisit if a real person is refused. |

## 7. Limits and risks

- The second-factor design assumes a fit with Spring Security's multi-factor support that I have read about but not run. `docs/adr/0002-identity-and-sessions.md` lists this and two other points to confirm first in milestone 1B. The API contract does not depend on them.
- The threat model was written by the same author as the design. It needs the frontend agent's independent review, especially the script-injection row, whose defence is mostly in the frontend.
- Rate-limit numbers are starting values, not measured ones.
- The operator's identity check during recovery is a human procedure. The system can record it, not enforce it.
- Three new secrets will need safe storage and backup in production: the authenticator encryption key and two hashing keys.

## 8. Acceptance checklist

Frontend agent:

- [ ] The sign-up, sign-in, second-factor and account screens can be built from these operations.
- [ ] The CSRF rules (when to fetch, when to refetch, the single retry) are implementable.
- [ ] The nine items in section 5 are accepted or countered.
- [ ] `identity-api.ts` has the shape the frontend wants, or a change is requested.
- [ ] The browser-marked tests in `tests/security-acceptance.md` are feasible.

Backend agent:

- [x] Every rule can be enforced on the approved stack with no dependency upgrade.
- [x] Every identity response and request is covered by a closed schema and by negative cases.
- [ ] The three points in ADR 0002 are confirmed at the start of milestone 1B.

Project owner:

- [ ] The seven decisions in section 6.
- [ ] Authorization to merge this contract and to start milestone 1B.
