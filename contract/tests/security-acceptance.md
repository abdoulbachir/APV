# Security acceptance tests for milestone 1B

The tests the identity implementation must pass before it is accepted. Each is written as an observable behavior, so the backend can implement it against PostgreSQL and the browser suite can repeat the marked ones end to end.

Where they run:

- **API**: the whole application over HTTP against real PostgreSQL (Testcontainers), with a cookie jar per simulated browser.
- **Browser**: a real browser driving the real frontend against the real backend, using only what a browser does with cookies. Marked **B**.
- **Unit**: no database.

"Same answer" always means the same status, the same body apart from `requestId`, and no timing difference outside normal noise.

## 1. Sign-up and sign-in

| # | Test | Expected | B |
|---|---|---|---|
| 1.1 | Sign up with a new address, then sign in | 202 `accepted`, no session cookie set by sign-up; sign-in 200 `authenticated`; `GET /me` shows role `donor`, `emailVerified` false | B |
| 1.2 | Sign up again with the same address and a different password | Same 202. The first password still works; the second does not. | |
| 1.3 | Sign up with the same address in different letter case and with surrounding spaces | Same 202; still one account | |
| 1.4 | Twenty simultaneous sign-ups for one address | All 202; exactly one account row | |
| 1.5 | Sign in with a wrong password, an unknown address, and a disabled account | Same 401 `invalid_credentials` for all three | B |
| 1.6 | Timing of 1.5 over many runs | Unknown address is not faster than a wrong password (a hash is computed in both cases) | |
| 1.7 | Sign-up answer for a new and an existing address | Same answer | |
| 1.8 | Sign in with an address in different case | Succeeds | |
| 1.9 | A not-yet-activated staff account signs in | 401 `invalid_credentials` | |

## 2. Passwords

| # | Test | Expected |
|---|---|---|
| 2.1 | 14 code points; 15; 128; 129 | Refused `too_short`; accepted; accepted; refused `too_long` |
| 2.2 | 15 emoji (30 UTF-16 units) | Accepted: length is counted in code points |
| 2.3 | A password with leading, trailing and inner spaces | Accepted and required exactly at sign-in |
| 2.4 | The same password in composed and decomposed Unicode form | Both sign in |
| 2.5 | A 128-character password differing from another only in the last character | Only the right one signs in: nothing is cut |
| 2.6 | A password on the blocked list; the site name repeated; the person's own email | Refused `compromised` |
| 2.7 | All lower-case letters, no digit, no symbol, 15 long, not on the list | Accepted: no composition rules |
| 2.8 | Stored hash | Starts with the Argon2id identifier; parameters at or above the minimum; two accounts with the same password have different hashes |
| 2.9 | Raise the work factor in configuration, sign in | Stored hash is upgraded; next sign-in works |
| 2.10 | Change password with a wrong current password | 401 `invalid_credentials`; password unchanged |
| 2.11 | Change password to the current one | Refused `same_as_current` |
| 2.12 | No outgoing network connection during any password check | Verified with the network disabled |

## 3. Sessions

| # | Test | Expected | B |
|---|---|---|---|
| 3.1 | Read a session identifier before sign-in, sign in, reuse the old identifier | The old identifier is not signed in (fixation) | |
| 3.2 | Identifier before and after second-factor verification, and after a password change | Different each time; the old ones are dead | |
| 3.3 | Idle for longer than the idle timeout (clock moved) | 401 `unauthenticated` | |
| 3.4 | Keep a session active past its absolute lifetime | 401 at the absolute deadline despite activity | |
| 3.5 | Donor and staff deadlines in `Account.session` | 2 hours and 24 hours; 30 minutes and 12 hours | |
| 3.6 | Sign out, then reuse the cookie | 401; the session row is gone | B |
| 3.7 | Sign out with no session, and twice | 204 both times | |
| 3.8 | Two browsers signed in; change the password in one | The other gets 401 on its next request; the first continues | B |
| 3.9 | Production profile: cookie attributes | `__Host-apv_session`, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, no `Domain`, no `Max-Age` | |
| 3.10 | Start outside `dev` and `test` with a non-`Secure` session cookie configured | The application refuses to start | |
| 3.11 | Scripts in the page try to read the session cookie | Not visible | B |
| 3.12 | A session identifier that was never issued | 401, no session created from it | |

## 4. CSRF

| # | Test | Expected | B |
|---|---|---|---|
| 4.1 | Each `POST` endpoint with no CSRF header | 403 `csrf_invalid`; nothing changed | |
| 4.2 | With a made-up token | 403 `csrf_invalid` | |
| 4.3 | Token fetched before sign-in, used after sign-in | 403; a fresh token works | B |
| 4.4 | Token from browser A used with browser B's cookies | 403 | |
| 4.5 | Token used after sign-out | 403; a fresh token works | |
| 4.6 | Sign-up, sign-in and sign-out without a session but without a token | 403: public mutations are protected too | |
| 4.7 | A cross-site form post (`application/x-www-form-urlencoded`) to sign-in | 415 or 403; never processed | |
| 4.8 | `POST` with `Origin` of another site | 403 | |
| 4.9 | `GET` endpoints with no token | Work | |
| 4.10 | The frontend's automatic single retry after `csrf_invalid` | One retry, then a visible error | B |

## 5. Authorization

| # | Test | Expected |
|---|---|---|
| 5.1 | Every route under `/api` that is not public, with no session | 401 with the contract's error body. Never a redirect, never HTML. |
| 5.2 | A route registered in the application with no access rule | Refused. A test enumerates all routes and fails if one is neither public by declaration nor protected. |
| 5.3 | `GET /me` as donor, admin, admin with `can_disburse`, super admin | `permissions` equals the matrix in `identity-and-security.md` for each |
| 5.4 | Sign-up body with `role`, `permissions`, `canDisburse`, `emailVerified` or `id` | 400 `unknown_field`; no account created |
| 5.5 | Any identity request with an extra property | 400 `unknown_field` |
| 5.6 | A donor calls a staff-only test route; an admin calls a super-admin-only test route | 403 `forbidden` |
| 5.7 | Account A tries to act on account B by any identifier in path, query, header or body | No M1 endpoint accepts one; extra fields are refused; A's own account is used |
| 5.8 | Demote an admin while signed in | Next request: 401. After signing in again: donor permissions only. |
| 5.9 | Remove `can_disburse` while signed in | Next request no longer has `disbursements.record` |
| 5.10 | Disable an account while signed in | Next request: 401; sign-in refused as `invalid_credentials` |
| 5.11 | A session whose row survived deletion but whose security version is old | 401, and the row is removed |
| 5.12 | A session in `mfa_required` or `mfa_enrollment_required` calls `GET /me` and any protected route | 401 with that state's code; no permission |

## 6. Second factor

| # | Test | Expected | B |
|---|---|---|---|
| 6.1 | Activated staff with no second factor signs in | `mfa_enrollment_required`; only the enrollment endpoints work | B |
| 6.2 | Enroll, confirm with a correct code | 200 with ten recovery codes; session `authenticated`; identifier rotated | B |
| 6.3 | Confirm with a wrong code; confirm after the pending secret expired | 401 `mfa_code_invalid`; factor not active | |
| 6.4 | Staff with a second factor signs in | `mfa_required`; verify with a correct code gives `authenticated` | B |
| 6.5 | Use the same code twice within its 30 seconds | Second use refused | |
| 6.6 | Codes for the previous and next step; a code two steps away | Accepted; refused | |
| 6.7 | Five wrong codes | Pending sign-in cancelled; a correct code afterwards is refused; password sign-in needed again | |
| 6.8 | Sign in with a recovery code; reuse it | Works once; refused the second time; `recoveryCodesRemaining` is 9 | |
| 6.9 | Regenerate recovery codes with password and code | Ten new codes; every old one refused | |
| 6.10 | Regenerate with a recovery code in place of the authenticator code, or with a wrong password | Refused | |
| 6.11 | A donor calls any second-factor endpoint | 401 or 403; no enrollment | |
| 6.12 | The verifier against RFC 6238 test vectors | Passes (unit) | |
| 6.13 | Database content after enrollment | No plain secret, no plain recovery code | |
| 6.14 | Password reset for staff, then sign-in | Still `mfa_required`: the reset did not remove the factor | |
| 6.15 | `reset-mfa`, then sign-in | `mfa_enrollment_required`; old codes and recovery codes refused; previous sessions dead | |

## 7. One-time credentials and operator command

| # | Test | Expected |
|---|---|---|
| 7.1 | `create-super-admin` on an empty database | Account not activated; credential printed once; nothing secret in logs |
| 7.2 | Set a password with the credential; use the credential again | 204; then 400 `credential_invalid` |
| 7.3 | Expired, malformed, unknown and already used credentials | Same 400 `credential_invalid` |
| 7.4 | Issue a second credential | The first no longer works |
| 7.5 | Two simultaneous redemptions of one credential | Exactly one succeeds |
| 7.6 | Setting a password signs nobody in | No session cookie; sign-in is still required |
| 7.7 | Reset for an account with sessions | All its sessions end |
| 7.8 | Database content | Only a hash of the credential |
| 7.9 | Disable, demote or delete the last enabled, activated super administrator | Refused by the database, also when two changes race |
| 7.10 | With two super administrators, disable one, then the other | First succeeds; second refused |
| 7.11 | The application starts on an empty database | No account exists; nothing can sign in |
| 7.12 | Process list and shell history after operator commands | No password or credential in arguments |

## 8. Rate limiting

| # | Test | Expected |
|---|---|---|
| 8.1 | Six failed sign-ins for one address from one client address | Sixth is 429 with `Retry-After`; the correct password is also refused until the window ends |
| 8.2 | Same, then from another client address | Works: the first attacker did not lock the person out |
| 8.3 | 21 failures for one address across many client addresses | 429 for at most 15 minutes, then sign-in works. The account is never disabled. |
| 8.4 | Throttled attempts for an existing and an unknown address | Same answer |
| 8.5 | Sign-up, one-time credential and CSRF limits | 429 at the documented counts |
| 8.6 | A forged forwarded-for header from an untrusted connection | Ignored: the connection's address is counted |
| 8.7 | Counters in the database | Hashes, not emails or addresses |
| 8.8 | Many simultaneous sign-ins | At most the configured number of hashes run at once; the rest wait or get 503; memory stays bounded |

## 9. Caching, cookies, headers and leaks

| # | Test | Expected | B |
|---|---|---|---|
| 9.1 | Every identity response, including errors | `Cache-Control: no-store`, no `ETag` | |
| 9.2 | The seven public endpoints, with and without a session cookie | Identical bodies; `no-cache` and `ETag` as before; no `Set-Cookie` | |
| 9.3 | Security headers on API responses | As listed in `identity-and-security.md` 9.4 | |
| 9.4 | No CORS headers on any response; a preflight from another origin | Not allowed | |
| 9.5 | Body over 16 KiB; body that is not JSON; `Content-Type` other than JSON | 413; 400; 415 | |
| 9.6 | Application logs and audit rows after the whole suite, searched for every password, credential, secret, code, session identifier and CSRF token used | None found | |
| 9.7 | Every response of the suite validated against `openapi.yaml` | All valid: closed schemas, so no extra field | |
| 9.8 | Forced server error in an identity endpoint | Generic 500 body; no stack trace, no SQL, no class name | |
| 9.9 | Browser storage after signing in | Nothing in `localStorage` or `sessionStorage` | B |
| 9.10 | Back button after signing out | The account page is not shown from cache | B |

## 10. Methods and routes

| # | Test | Expected |
|---|---|---|
| 10.1 | `POST /api/cases`, `DELETE /api/me`, `GET /api/auth/login` | 405 `method_not_allowed` with `Allow` |
| 10.2 | Any method on an unknown path | 404 `not_found` |
| 10.3 | `OPTIONS` on any path | No CORS grant |

## 11. Audit

| # | Test | Expected |
|---|---|---|
| 11.1 | Each event in `identity-and-security.md` 10.1 | Exactly one record with the listed fields |
| 11.2 | Update or delete an audit row | Refused by the database |
| 11.3 | A failed sign-in for an unknown address | Recorded without the address in clear |

## 12. Nothing public changed

| # | Test | Expected |
|---|---|---|
| 12.1 | The whole milestone 0B suite: 114 backend tests, contract checks, frontend parity | Still passes unchanged, apart from the 405 behavior in 10.1 |
| 12.2 | Full-dataset acceptance with a signed-in session cookie present | Same responses as without |
| 12.3 | The fixture loader | Deletes no account, credential, session, throttle or audit row; refuses a database marked `real` |
| 12.4 | The development seed | Absent outside `dev` and `test`; refuses a non-local or `real` database; prints generated passwords, commits none |
