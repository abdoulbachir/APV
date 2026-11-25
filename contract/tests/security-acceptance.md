# Security acceptance tests for milestone 1B

The tests the identity implementation must pass before it is accepted. Each is written as an observable behavior, so the backend can implement it against PostgreSQL and the browser suite can repeat the marked ones end to end.

Where they run:

- **API**: the whole application over HTTP against real PostgreSQL (Testcontainers), with a cookie jar per simulated browser.
- **Browser**: a real browser driving the real frontend against the real backend, using only what a browser does with cookies. Marked **B**.
- **Unit**: no database.

"Same answer" always means the same status, the same body apart from `requestId`, and no timing difference outside normal noise.

## 1. Sign-up and sign-in

Sign-up is open in these tests unless a row says otherwise.

| # | Test | Expected | B |
|---|---|---|---|
| 1.1 | Sign up with a new address, then sign in | 202 `accepted`, no sign-in by sign-up; sign-in 200 `authenticated` with an account; `GET /me` shows role `donor`, `emailVerified` false | B |
| 1.2 | Sign up again with the same address and a different password | Same 202. The first password still works; the second does not. | |
| 1.3 | Sign up with the same address in different letter case | Same 202; still one account | |
| 1.4 | An address with a leading or trailing space or tab, at sign-up and at sign-in | 400 `invalid_format`: the server does not trim | |
| 1.5 | Twenty simultaneous sign-ups for one address, each from a different client address so the per-address limit is not what is measured | All 202; exactly one account row | |
| 1.6 | Sign in with a wrong password, an unknown address, a disabled account and a not-activated staff account | Same 401 `invalid_credentials` for all four | B |
| 1.7 | Timing of 1.6 over many runs | An unknown address is not faster than a wrong password | |
| 1.8 | Sign-up answer for a new and an existing address | Same answer | |
| 1.9 | Sign in with an address in different case | Succeeds | |
| 1.10 | Sign-up closed: `GET /auth/options`, then `POST /auth/register` | `registrationOpen` false; 403 `registration_closed`; no account row | B |
| 1.11 | Start in the production profile with sign-up open and no address proof available | The application refuses to start | |
| 1.12 | A preclaimed account: sign up under an address, then check everything keyed on that address | `emailVerified` false; no gift, permission or data is attached because of the address | |

## 2. Passwords

| # | Test | Expected |
|---|---|---|
| 2.1 | 14 code points; 15; 128; 129 | Refused `too_short`; accepted; accepted; refused `too_long` |
| 2.2 | 15 emoji (30 UTF-16 units) | Accepted: length is counted in code points |
| 2.3 | A password with leading, trailing and inner spaces | Accepted and required exactly at sign-in |
| 2.4 | The same password in composed and decomposed Unicode form | Both sign in (NFC) |
| 2.5 | Two passwords that differ only by a compatibility character, for example the "ﬁ" ligature against "fi" | Different passwords: only the right one signs in (not NFKC) |
| 2.6 | 16 code points as sent that become 14 after NFC | Refused `too_short`: the minimum is measured after NFC |
| 2.7 | 129 code points as sent that would become 128 after NFC | Refused `too_long`: the maximum as sent is checked first |
| 2.8 | A 128-character password differing from another only in the last character | Only the right one signs in: nothing is cut |
| 2.9 | A password on the breached list | Refused `compromised` |
| 2.10 | `apv-gakasinay-2026`; the email's first part plus `123`; the display name twice | Refused `compromised`: fewer than 8 other characters remain |
| 2.11 | A long passphrase that happens to contain the site name with 8 or more other characters | Accepted |
| 2.12 | All lower-case letters, no digit, no symbol, 15 long, not on the list | Accepted: no composition rules |
| 2.13 | Stored hash | Argon2id; parameters at or above the minimum; two accounts with the same password have different hashes |
| 2.14 | Raise the work factor in configuration, sign in | Stored hash is upgraded; next sign-in works |
| 2.15 | Change password with a wrong current password | 401 `invalid_credentials`; password unchanged; counted (8.9); the session is still signed in and `GET /me` answers 200 |
| 2.16 | Change password to the current one | Refused `same_as_current` |
| 2.17 | No outgoing network connection during any password check | Verified with the network disabled |

## 3. Sessions

| # | Test | Expected | B |
|---|---|---|---|
| 3.1 | Obtain an anonymous session with `GET /auth/csrf`, sign in, reuse the earlier identifier | The earlier identifier is not signed in (fixation) | |
| 3.2 | Identifier before and after second-factor verification, enrollment confirmation and a password change | Different each time; the old ones are dead | |
| 3.3 | Idle for longer than the idle timeout (clock moved), then `GET /me`; then a `POST` with the old token | 401 `unauthenticated`; 403 `csrf_invalid` | |
| 3.4 | Keep a session active past its absolute lifetime, then `GET /me`; then a `POST` with the old token | 401 `unauthenticated` at the absolute deadline despite activity; 403 `csrf_invalid`. The deadline applies at once, before any cleanup of the row. | |
| 3.5 | Deadlines in `Account.session` for donor and staff; `pendingExpiresAt`; anonymous session | 2 h and 24 h; 30 min and 12 h; 10 min; 30 min and 2 h | |
| 3.6 | Sign out, then reuse the cookie on `GET /me` and on a `POST` with the old token | 401 `unauthenticated`; 403 `csrf_invalid`; the session row is gone | B |
| 3.7 | Sign out with no session, and twice | 204 both times | |
| 3.8 | Two browsers signed in; change the password in one | In the other, the next `GET` is 401 `unauthenticated` and the next `POST` is 403 `csrf_invalid` followed by 401 on `GET /me`; the first continues | B |
| 3.9 | Production profile: cookie attributes | `__Host-apv_session`, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, no `Domain`, no `Max-Age`. No other cookie is set. | |
| 3.10 | Start outside `dev` and `test` with a non-`Secure` session cookie configured | The application refuses to start | |
| 3.11 | Scripts in the page try to read the session cookie | Not visible | B |
| 3.12 | A session identifier that was never issued | 401, no session created from it | |
| 3.13 | An anonymous session calls `GET /me` and a protected route | 401 `unauthenticated`: it grants nothing | |
| 3.14 | Fully signed in, call `/auth/login`, `/auth/register`, `/auth/set-password` | 409 `already_signed_in` each; the session, the account and the target account are unchanged | B |
| 3.15 | With a pending second factor, sign in again as another account | The pending sign-in is discarded; the new sign-in proceeds | |
| 3.16 | Wait past `pendingExpiresAt`, then call `/auth/mfa/verify`, `/enroll`, `/confirm` with the token the page still holds | 403 `csrf_invalid` each: the session no longer exists, so its token is refused before the state is looked at. `GET /me` then answers 401 `unauthenticated`. | |
| 3.16a | Same, but each call made with a fresh token of a new anonymous session | 401 `unauthenticated` each. The code is not checked or counted. | |
| 3.17 | Drop the answer of each state-changing call on the way back, then follow the table in `identity-and-security.md` 4.6 | The frontend reaches the right state through `GET /me` and never resends a password, code or credential by itself | B |
| 3.18 | Every identity operation called in every session state, each with a valid token for that session | Exactly the answer in the table of `identity-and-security.md` 4.3 | |

## 4. CSRF

| # | Test | Expected | B |
|---|---|---|---|
| 4.1 | `GET /auth/csrf` with no cookie | 200 with `headerName` `X-CSRF-TOKEN`; the session cookie is set; no other cookie | |
| 4.2 | Each `POST` endpoint with no CSRF header | 403 `csrf_invalid`; nothing changed | |
| 4.3 | With a made-up token | 403 `csrf_invalid` | |
| 4.4 | Token fetched before sign-in, used after sign-in | 403; a fresh token works | B |
| 4.5 | Token from browser A used with browser B's session cookie | 403 | |
| 4.6 | A `POST` with the token of a session that was signed out, rotated by a password change, or has expired; with the old cookie, with a new cookie, and with no cookie | 403 `csrf_invalid` each; nothing changed or counted; a fresh token works | |
| 4.7 | Sign-up, sign-in and sign-out with no token | 403: public mutations are protected too | |
| 4.8 | Two tokens fetched in a row for one session | Different text, both accepted | |
| 4.9 | A wrong password sent with a bad token, six times | 403 each; no rate-limit counter moved; no audit record of a failed sign-in: the rejection came first | |
| 4.10 | A cross-site form post (`application/x-www-form-urlencoded`) to sign-in, as a browser sends it: `Origin` of the other site | 403 `forbidden`, from the origin check, before the content type is looked at. Never processed. | |
| 4.10a | The same body and content type sent with no `Origin` and no `Sec-Fetch-Site` | 415: the origin check passes, the declared type is refused next | |
| 4.11 | A `POST` with: `Origin` of another site; `Origin: null`; no `Origin` and `Sec-Fetch-Site` of `cross-site`, `same-site` or `none`; the site's own `Origin` with `Sec-Fetch-Site: cross-site` | 403 `forbidden` each, and never `csrf_invalid`, even when the token is also wrong | |
| 4.11a | A `POST` with the site's own `Origin` and `Sec-Fetch-Site: same-origin`; with the site's own `Origin` only; with neither header | Passes the origin check each time and is judged by the later steps | |
| 4.11b | A `GET` to a public endpoint and to `/me` carrying another site's `Origin` or `Sec-Fetch-Site: cross-site` | Not refused by the origin check | |
| 4.12 | `GET` endpoints with no token | Work | |
| 4.13 | Tab A shows account X. Tab B signs out and signs in as account Y. Tab A submits a password change. | The request is refused as `csrf_invalid`; the frontend fetches a token, reads `GET /me`, sees another identity, clears X's screens and resends nothing. Y's password is unchanged. | B |
| 4.14 | Stale token on **every** `POST` in turn: sign-in, sign-up, sign-out, password change, set password, and each second-factor operation, with the identity unchanged | Exactly one request per submission reaches the server: no automatic resend, for any of them. The frontend fetches a token, reads `GET /me`, says the page was out of date and waits. Password, code and credential fields are cleared. | B |
| 4.15 | After 4.14, the person submits again | The request carries the new token and succeeds | B |
| 4.16a | Tab A is on the second-factor challenge of account X. Tab B starts a sign-in as account Y and is also pending. Tab A submits its code. | Nothing is resent automatically. Tab A cannot tell the two pending identities apart from `GET /me`, which is why no `POST` is ever replayed. | B |
| 4.16b | A `GET` that fails while the token is stale | May be repeated; `GET` needs no token | |
| 4.17 | Where the token lives in the browser | In memory only: not in a cookie, storage or the address | B |

## 5. Authorization

| # | Test | Expected |
|---|---|---|
| 5.1 | Every route under `/api` that is not public, with no session | 401 with the contract's error body. Never a redirect, never HTML. |
| 5.2 | A route registered in the application with no access rule | Refused. A test enumerates all routes and fails if one is neither public by declaration nor protected. |
| 5.3 | `GET /me` as donor, admin, admin with `can_disburse`, super admin | `permissions` is exactly `account.read_own` and `account.change_password` for each. No planned permission is returned. |
| 5.4 | The permission function behind the planned matrix, for each role and `can_disburse` | Equals the matrix in `identity-and-security.md` 1.2 (unit) |
| 5.5 | Sign-up body with `role`, `permissions`, `canDisburse`, `emailVerified` or `id` | 400 `unknown_field`; no account created |
| 5.6 | Any identity request with an extra property | 400 `unknown_field` |
| 5.7 | A donor calls a staff-only test route; an admin calls a super-admin-only test route | 403 `forbidden` |
| 5.8 | Account A tries to act on account B by any identifier in path, query, header or body | No M1 endpoint accepts one; extra fields are refused; A's own account is used |
| 5.9 | Demote an admin, or remove `can_disburse`, while signed in | The session is ended first. Its very next `GET` is 401 `unauthenticated`; its next `POST` is 403 `csrf_invalid`, then 401 on `GET /me`. After signing in again, the server-side permission function no longer grants what was removed. |
| 5.10 | Disable an account while signed in | Next `GET`: 401 `unauthenticated`; next `POST`: 403 `csrf_invalid`; sign-in refused as `invalid_credentials` |
| 5.11 | A session whose row survived deletion but whose security version is old: `GET /me`; a password change with its still-valid token and a **wrong** current password; the same with the right one | 401 `unauthenticated` each, and the row is removed. The password is never checked: no failed attempt is counted and the right password changes nothing. |
| 5.12 | A privileged write and a demotion of its account, started together, many times | Never does the write commit after the demotion committed. Either the write committed first, or it was refused. |
| 5.13 | A read in flight when the demotion commits; a request starting just after | The first may finish; the second is 401 |

## 6. Second factor

| # | Test | Expected | B |
|---|---|---|---|
| 6.1 | Activated staff inside the enrollment window signs in | `mfa_enrollment_required` with `pendingExpiresAt`, no account in the answer; only the enrollment endpoints work | B |
| 6.2 | Enroll, confirm with a correct code | 200 with ten recovery codes; session `authenticated`; identifier rotated | B |
| 6.3 | Confirm with a wrong code; after the pending secret expired; with a code from a secret that was replaced by a second `enroll` | 401 `mfa_code_invalid` each; factor not active | |
| 6.4 | Two tabs start enrollment; the first tab confirms with its own code | 401 `mfa_code_invalid`: only the latest pending secret counts | |
| 6.5 | Simultaneous confirmations with a correct code, repeated many times, in each arrangement of `identity-and-security.md` 5.3 | Always exactly one 200, one active factor and one stored set of recovery codes. No losing answer contains codes or a secret. Each loser gets the one answer of its row: 409 `already_signed_in` on the now signed-in session with its current token; 403 `csrf_invalid` with the pre-rotation token, the pre-rotation session identifier, or both; 401 `unauthenticated` with a fresh anonymous token; 401 `mfa_code_invalid` from another pending session of the same account. | |
| 6.5a | Start enrollment inside the window, confirm with a correct code after the window closed (clock moved) | 401 `unauthenticated`; pending session ended; no factor active; the account needs a new credential | |
| 6.5b | Wrong code at confirmation, then a correct one for the same pending secret | 401 `mfa_code_invalid`, session still pending; then 200 | |
| 6.6 | Staff with a second factor signs in | `mfa_required`; verify with a correct code gives `authenticated` with an account | B |
| 6.7 | The same code in two simultaneous verification requests, many times | Exactly one succeeds each time | |
| 6.8 | The same code again within its 30 seconds, in a new sign-in | Refused | |
| 6.9 | Codes for the previous and next step; a code two steps away | Accepted; refused | |
| 6.10 | Four wrong codes, then a fifth, then a correct one | 401 `mfa_code_invalid` four times, the session still pending after each (`GET /me` answers 401 `mfa_required`); the fifth is 401 `unauthenticated` and the session is gone. The correct code sent next with the token the page still holds is 403 `csrf_invalid`; sent with a fresh anonymous token it is 401 `unauthenticated`. It is never accepted and never checked. | |
| 6.10a | Every way a session can be lost (expiry, sign-out elsewhere, disablement, security-version change, rotation), then every protected operation that checks a password or code (excluding operations accepting anonymous), assuming earlier checks other than the specified CSRF condition pass | Never `invalid_credentials` or `mfa_code_invalid`, and the secret is never checked or counted. The answer is fixed by what the request carries: 403 `csrf_invalid` with the lost session's token; 401 `unauthenticated` with a fresh anonymous token, or with the surviving token of a revoked identity. Public operations remain governed by 14.9, 14.17 and the state table. | |
| 6.10b | Every wrong password or code on a live session, except the fifth code | Always `invalid_credentials` or `mfa_code_invalid`, and the session is unchanged afterwards | |
| 6.11 | Sign in with a recovery code; reuse it | Works once; refused the second time; `recoveryCodesRemaining` is 9 | |
| 6.12 | One recovery code in two simultaneous requests, many times | Exactly one succeeds each time | |
| 6.13 | Authenticator method with a recovery-shaped code, and the reverse | 400 `validation_error` before any code is checked or counted | |
| 6.14 | Regenerate recovery codes with password and code | Ten new codes; every old one refused | |
| 6.15 | Regenerate with a recovery code in place of the authenticator code, or with a wrong password | Refused, and counted (8.9) | |
| 6.16 | A donor calls any second-factor endpoint | 409, 403 or 401 according to the state table; no enrollment | |
| 6.17 | The verifier against RFC 6238 test vectors | Passes (unit) | |
| 6.18 | Database content after enrollment | No plain secret, no plain recovery code | |
| 6.19 | Password reset for staff, then sign-in | Still `mfa_required`: the reset did not remove the factor | |
| 6.20 | `recover-staff-access`, then sign in with the **old** password | 401 `invalid_credentials`. The old password can neither sign in nor reach enrollment. | |
| 6.21 | After 6.20: redeem the recovery credential, choose a new password, sign in | `mfa_enrollment_required`; previous sessions, recovery codes and credentials are dead | |
| 6.22 | A staff account with a password and no second factor, after its enrollment window closed | Sign-in is 401 `invalid_credentials`; a new credential is required | |
| 6.23 | The QR code on the enrollment screen | Drawn in the page; no network request carries the secret or the `otpauth://` address | B |

## 7. One-time credentials, operator command and the super administrator invariant

| # | Test | Expected |
|---|---|---|
| 7.1 | The application starts on an empty database | No account exists; nothing can sign in; the site is not bootstrapped |
| 7.2 | `create-super-admin` on an empty database | Account not activated; credential printed once; nothing secret in logs |
| 7.3 | Activate and enroll that account | The site becomes bootstrapped exactly once |
| 7.4 | Two `create-super-admin` runs at the same time on an empty database, then both activated and enrolled at the same time | Two usable super administrators; bootstrapped recorded once; no error |
| 7.5 | Set a password with the credential; use the credential again | 204; then 400 `credential_invalid` |
| 7.6 | Expired, malformed (any text), unknown and already used credentials | Same 400 `credential_invalid` |
| 7.7 | A credential that is a number, or missing | 400 `validation_error` |
| 7.8 | Issue a second credential | The first no longer works |
| 7.9 | Two simultaneous redemptions of one credential | Exactly one succeeds |
| 7.10 | Setting a password signs nobody in | No sign-in; sign-in is still required |
| 7.11 | Reset for an account with sessions | All its sessions end |
| 7.12 | Database content | Only a hash of the credential |
| 7.13 | One usable super administrator: disable it, demote it, delete it, run `recover-staff-access` on it | Each refused |
| 7.14 | Two usable super administrators: remove one, then the other | First succeeds; second refused |
| 7.15 | **Two usable super administrators, each disabled by a different transaction at the same time, repeated many times**; also one disabled while the other is demoted, and one disabled while `recover-staff-access` runs on the other | Exactly one transaction succeeds every time. There is never a moment with no usable super administrator. |
| 7.16 | A second super administrator that is enabled and activated but has no second factor; remove the first | Refused: the second is not usable |
| 7.17 | A statement that changes a super administrator without taking the guard | Refused at commit by the backstop when it would leave none |
| 7.18 | The recovery path: one super administrator who lost everything | `create-super-admin` works; once the new one is usable, the old one can be recovered or disabled |
| 7.19 | Process list and shell history after operator commands | No password or credential in arguments |
| 7.20 | A donor recovery request supported only by facts the requester supplies | The procedure refuses it; documented, and the command records the kind of check made |

## 8. Rate limiting

| # | Test | Expected |
|---|---|---|
| 8.1 | Six failed sign-ins for one address from one client address | Sixth is 429 with `Retry-After`; the correct password is also refused until the window ends |
| 8.2 | Same, then from another client address | Works: the first attacker did not lock the person out |
| 8.3 | 21 failures for one address across many client addresses | 429 with `Retry-After` of at most 900 seconds, then sign-in works. The account is never disabled. |
| 8.4 | Throttled attempts for an existing and an unknown address | Same answer |
| 8.5 | Fifty wrong passwords for one address and client address sent at the same instant | At most five passwords are verified; the rest are 429. The counter was advanced before each check. |
| 8.6 | `Retry-After` on every 429 the suite produces | Present; never above 900, or 3600 for sign-up |
| 8.7 | Sign-up, one-time credential, CSRF and options limits | 429 at the documented counts |
| 8.8 | A forged forwarded-for header from an untrusted connection | Ignored: the connection's address is counted |
| 8.9 | Wrong current password on `change-password` and on `recovery-codes`; wrong code on `recovery-codes` and `confirm` | Each draws on the account's password or second-factor counter and reaches 429 at the documented count |
| 8.10 | Counters in the database | Hashes, not emails or addresses |
| 8.11 | More simultaneous sign-ins than hash slots, from many client addresses | At most four hashes at once; a request waiting more than 2 seconds gets 503 with `Retry-After: 5`; memory stays bounded |
| 8.12 | One client address trying to occupy all hash slots | Stopped by its rate limit before hashing |
| 8.13 | A successful sign-in after four failures | The pair counter is cleared |

## 9. Caching, headers and leaks

| # | Test | Expected | B |
|---|---|---|---|
| 9.1 | Every answer of every identity operation the suite produces, at every status: 2xx, 400, 401, 403, 405, 409, 413, 415, 429, 500, 503 | `Cache-Control: no-store`, no `ETag` | |
| 9.2 | The seven public endpoints, with and without a session cookie | Identical bodies; `no-cache` and `ETag` as before; no `Set-Cookie` | |
| 9.3 | Every 405; every 429; every identity 503 | `Allow`; `Retry-After`; `Retry-After` | |
| 9.4 | Security headers on API responses | As listed in `identity-and-security.md` 9.4 | |
| 9.5 | No CORS headers on any response; a preflight from another origin | Not allowed | |
| 9.6 | Body over 16 KiB; body that is not JSON; `Content-Type` other than JSON | 413; 400; 415 | |
| 9.7 | `POST /auth/logout` and `/auth/mfa/enroll` with no body and no `Content-Type`; with a non-empty body | Accepted; 400 | |
| 9.8 | Application logs and audit rows after the whole suite, searched for every password, credential, secret, code, session identifier and CSRF token used | None found | |
| 9.9 | Every response of the suite validated against `openapi.yaml` | All valid: closed schemas, so no extra field | |
| 9.10 | Forced server error in an identity endpoint | Generic 500 body; no stack trace, no SQL, no class name | |

## 10. Browser

All run in a real browser against the real backend and the production build of the frontend.

| # | Test | Expected |
|---|---|---|
| 10.1 | Address bar, history and every request URL during sign-up, sign-in, second factor, enrollment, set password and recovery codes | No password, code, recovery code, secret, credential or CSRF token appears |
| 10.2 | A one-time credential arriving in a link fragment | Removed from the address and history before anything else; never sent in a URL or a referrer |
| 10.3 | `localStorage`, `sessionStorage`, IndexedDB and script-written cookies after the whole journey | Only language and theme preferences. Nothing about the account, session or permissions. |
| 10.4 | Console output and any error or analytics reporting during the journey, including failures | No secret |
| 10.5 | Sign out | Account screens are left; in-memory account data, recovery codes and tokens are gone |
| 10.6 | The session is lost while a private screen is shown; then the page makes a `GET`, and separately a `POST` | After the `GET`'s 401 `unauthenticated`, or after the `POST`'s 403 `csrf_invalid` and the `GET /me` that follows it: private state cleared and the sign-in screen shown. The `POST` is not sent again. |
| 10.6a | Wrong current password on the password-change screen | The error is shown on that field. The person is still signed in, on the same screen, with private state intact; no sign-out, no navigation. `GET /me` still answers 200. |
| 10.6b | Wrong authenticator code at sign-in, attempts one to four | Each time the challenge stays, the error is shown, the code field is cleared; a correct code then signs in |
| 10.6c | The fifth wrong code, then the person types a correct one anyway | After the fifth: returned to the password step with a message; nothing of the pending sign-in remains. A further code cannot be submitted from the challenge, and if forced it is refused and not resent. |
| 10.6d | Wrong code on recovery-code regeneration | Error on the code field; still signed in |
| 10.6e | Load the application while the session is pending a second factor, and while pending enrollment | `GET /me` answers 401 `mfa_required` or `mfa_enrollment_required`; the challenge or the enrollment screen is shown, never the generic sign-in screen |
| 10.6f | A pending sign-in expires while its challenge is shown, then the person submits a code | The request answers 403 `csrf_invalid`; the frontend fetches a token and reads `GET /me`, which answers 401 `unauthenticated`; the person is returned to the password step. The code is not sent again. |
| 10.7 | Another tab signs out, or signs in as another account | This tab clears and shows the new state when it is next used, at the latest when it becomes visible |
| 10.8 | Back button after signing out, including restoration from the back-forward cache | No private screen is shown; `GET /me` is re-read first |
| 10.9 | Every identity screen loaded from the production build with the page content policy enforced | No policy violation; the policy allows no inline script, no `eval`, and no other origin |
| 10.9a | The colour theme under that policy | Applied before the first paint, in both themes, by a self-hosted script; no inline script and no flash of the wrong theme |
| 10.10 | Real-API mode with the backend stopped, or answering 500 | An error is shown. No mock account, no mock sign-in. |
| 10.11 | A production build | Contains no mock identity code path |
| 10.12 | Each identity screen in English and in French | Complete; no missing or untranslated text, including every error code's message |
| 10.13 | Each screen in the light and the dark theme, and at phone width | Usable; nothing clipped; contrast holds |
| 10.14 | Each screen with the keyboard only | Every control reachable in order; focus visible; focus moves to the first error |
| 10.15 | Errors with assistive technology | Announced when they appear; each tied to its field; not conveyed by colour alone |
| 10.16 | Browser autofill and a password manager | Fill and save work; `autocomplete` is `username`, `current-password`, `new-password`, `one-time-code` as appropriate; pasting is never blocked |

## 11. Methods and routes

| # | Test | Expected |
|---|---|---|
| 11.1 | `POST /api/cases`, `DELETE /api/me`, `GET /api/auth/login` | 405 `method_not_allowed` with `Allow`. For the public paths this deliberately replaces the 404 of milestone 0B. |
| 11.2 | Any method on an unknown path | 404 `not_found` |
| 11.3 | `OPTIONS` on any path | No CORS grant |

## 12. Audit

| # | Test | Expected |
|---|---|---|
| 12.1 | Each event in `identity-and-security.md` 10.1 | Exactly one record with the listed fields |
| 12.2 | Update or delete an audit row | Refused by the database |
| 12.3 | A failed sign-in for an unknown address | Recorded without the address in clear |

## 13. Nothing public changed, and development data stays in development

| # | Test | Expected |
|---|---|---|
| 13.1 | The whole milestone 0B suite: 114 backend tests, contract checks, frontend parity | Still passes. The only intended difference is 11.1. Every successful public response is byte-for-byte what it was. |
| 13.2 | Full-dataset acceptance with a signed-in session cookie present | Same responses as without |
| 13.3 | The fixture loader and the development seed on a database with no disposable marker, empty or not | Refused |
| 13.4 | The same on a database explicitly marked disposable | Work; delete no account, credential, session, throttle, guard or audit row |
| 13.5 | The operator command on a disposable database | Refused |
| 13.6 | After the operator command has run once on a database: mark it disposable | Refused by the database |
| 13.7 | The development seed | Absent outside `dev` and `test`; refuses a non-local database; prints generated passwords, commits none |

## 14. Precedence: one request, several things wrong

Each row is one request, judged by the order of checks in `identity-and-security.md` 4.3. The first failing check determines the refusal. Requests refused before step 8 do not execute the operation or check/count a submitted secret. Session cleanup, rate-limit bookkeeping and audit effects follow the applicable step and explicit row. Requests reaching step 8 or completing an operation follow their stated outcomes. No refused mutation is automatically replayed.

Stale and fresh credentials of the browser:

| # | The request | Expected |
|---|---|---|
| 14.1 | `POST /auth/change-password`: cookie of a signed-out session and that session's token | 403 `csrf_invalid` |
| 14.2 | The same with the cookie of an expired session whose row has not been cleaned up yet | 403 `csrf_invalid`: a passed deadline means the session does not exist |
| 14.3 | The same with the pre-rotation cookie and the pre-rotation token, after a password change elsewhere | 403 `csrf_invalid` |
| 14.4 | A live session's cookie with the token of its previous identifier | 403 `csrf_invalid` |
| 14.5 | A live session's cookie with another browser's valid token | 403 `csrf_invalid` |
| 14.6 | A stale cookie and no token at all | 403 `csrf_invalid` |
| 14.7 | No cookie, and a token that was valid for some session | 403 `csrf_invalid` |
| 14.8 | A fresh anonymous session and its valid token, to `POST /auth/change-password`, `/auth/mfa/verify`, `/auth/mfa/enroll`, `/auth/mfa/confirm`, `/auth/mfa/recovery-codes` | 401 `unauthenticated` each |
| 14.9 | A fresh anonymous session and its valid token, to `POST /auth/login` with correct credentials | 200: sign-in is a public operation and accepts `anonymous` |
| 14.10 | The same to `POST /auth/logout` | 204 |
| 14.11 | After 14.1: the frontend's follow-up | One `GET /auth/csrf`, one `GET /me` answering 401 `unauthenticated`, private state cleared, sign-in screen. The password change is not sent again. (B) |

Revoked identities:

| # | The request | Expected |
|---|---|---|
| 14.12 | Account disabled, session rows deleted: `GET /me`; a `POST` with the old token | 401 `unauthenticated`; 403 `csrf_invalid` |
| 14.13 | Security version raised, session row and token survived: `POST /auth/change-password` with a wrong current password | 401 `unauthenticated`. Not `invalid_credentials`. The password counter does not move. The row is deleted. |
| 14.14 | The same with the correct current password | 401 `unauthenticated`. The password is not changed. |
| 14.15 | The same situation: `POST /auth/mfa/recovery-codes` with a wrong code | 401 `unauthenticated`. Not `mfa_code_invalid`. The second-factor counter does not move. |
| 14.16 | A pending session whose account was disabled meanwhile, row survived: `POST /auth/mfa/verify` with a correct code | 401 `unauthenticated`. The code is not consumed. |
| 14.17 | A revoked identity, row survived, calling `POST /auth/login` | Its session is ended at the state check and the sign-in proceeds as from `anonymous`; it does not get 409 |

Several invalid conditions at once:

| # | The request | Expected |
|---|---|---|
| 14.18 | Unknown path, wrong origin, bad token, oversized body | 404 `not_found` |
| 14.19 | `PUT /api/auth/login`, wrong origin, bad token | 405 `method_not_allowed` with `Allow` |
| 14.20 | `POST /auth/login`: another site's `Origin`, a 1 MiB form-encoded body, no token, no session | 403 `forbidden` |
| 14.21 | `POST /auth/login`: own origin, 1 MiB body, wrong content type, no token | 413 |
| 14.22 | `POST /auth/login`: own origin, small body, `text/plain`, no token | 415 |
| 14.23 | `POST /auth/login`: own origin, JSON, no token, client address already rate-limited | 403 `csrf_invalid`: the token comes before the rate limit, and the refused request does not count toward it |
| 14.24 | `POST /auth/login`: valid token, client address rate-limited, session fully signed in, malformed body | 429 with `Retry-After` |
| 14.25 | `POST /auth/login`: valid token, not rate-limited, session fully signed in, malformed body, wrong password | 409 `already_signed_in` |
| 14.26 | `POST /auth/mfa/verify`: valid token of an anonymous session, malformed body | 401 `unauthenticated`: state before validation |
| 14.27 | `POST /auth/mfa/verify`: valid token, pending session, a code of the wrong shape | 400 `validation_error`; the attempt is not counted among the five |
| 14.28 | `POST /auth/change-password`: valid token, signed in, new password too short and current password wrong | 400 `validation_error`; the current password is not checked or counted |
| 14.29 | `POST /auth/change-password`: valid token, signed in, valid body, wrong current password | 401 `invalid_credentials`; counted once; still signed in |
| 14.30 | `POST /auth/register`: valid token, sign-up closed, malformed body | 403 `registration_closed`: the closed switch is part of the state check |
| 14.31 | `POST /auth/logout` with a non-empty body and a bad token | 403 `csrf_invalid` |
| 14.32 | `POST /auth/mfa/enroll`: valid token, session in `mfa_required` | 403 `forbidden`, from the state check; distinguishable from 14.20 only by context, and in both cases nothing is resent |
