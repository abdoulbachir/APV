# M1B joint identity acceptance: evidence of 2026-11-25

Real browser, real production frontend build, real Spring Boot, real PostgreSQL. No identity response was mocked.

**This is not acceptance of milestone 1B.** The first run confirmed four defects; they and the later ones are followed in the update just below, which is the current state. Nothing here was merged into `main`.

## Update: corrections verified at `bfb1abb` (2026-11-25, later the same day)

Everything below this section describes the first run, at `cf0c883`, and is kept as the record of it. This section is the current state.

**Milestone 1B is still not accepted.** The first five findings are corrected. An independent review found six new defects, all failing closed; five have been corrected in the backend and confirmed by the reviewer's re-check, which found one new low-severity defect, R1; the backend corrected it, but the reviewer's third round found that the correction introduced a medium defect, R2, since corrected and verified by the coordinator (see "Third round" and "R2 and R3 corrected" below). The independent reviewer's fourth round confirmed that correction and found no new defect. Acceptance and the merge are the owner's decision.

- **Backend corrections.** `e74314c` and `1552dd0` were merged into the integration branch as `bfb1abb`. Findings 2, 3, 4 and 5 are corrected; finding 1 was corrected in the frontend before.
- **Harness re-run at `bfb1abb`.** All of `tests/acceptance/` again, same two environments: 79 checks pass, 2 are partial (10.15, 10.16), none fails. The log check that failed in the first run (9.8) passes, and so does staff sign-in in the `dev` profile with a code for the real time. The three reproductions now give: 40 of 40 corrupted tokens answered 403 `csrf_invalid`; 10 of 10 rounds of simultaneous token reads answered 200 twice; a real-time authenticator code accepted. Raw results: `tests/acceptance/results-2026-11-25-rerun-bfb1abb.json`. The only change to the harness was that `a1.mjs` no longer expects the pinned clock.
- **Independent review at `bfb1abb`.** Done by a separate Claude agent with no knowledge of the first run, using its own harness. It is the same model family as the coordinator, not another vendor's reviewer; three earlier attempts with another vendor's model stopped on that model's content filter before reporting. The report is held by the owner (`M1B Independent Acceptance Report (bfb1abb).md`). Its results:
  - Findings 1 to 5: corrected; none reproduced with its own inputs (2,592 forged tokens, about 1,300 simultaneous token reads, forced database errors naming a session, real-clock sign-in).
  - 42 browser scenarios: 40 PASS, 2 PARTIAL (10.15 screen reader, 10.16 password manager), 0 FAIL. It closed 10.8 (a real back-forward cache restoration) and 10.12 to 10.14 (enrollment and recovery-code screens in French, dark, 360 px), and scored 7.19 PASS.
  - Logs at INFO, DEBUG and TRACE: no secret and no usable session identifier among 9,225 values.
  - 20 unsafe start-up configurations refused; the last-super-administrator rule held under concurrent operator commands.
  - It agrees with both contract wording proposals, adding to the first that a 429 from an email or account counter is a step-8 answer.

### Defects found by the independent review

| # | Severity | Defect | Status |
|---|---|---|---|
| D2 | Medium | A `POST` refused as `csrf_invalid` with no session creates a session row and cookie, with no rate limit, against 4.1 | Recommended: correct. Owner decides. |
| D1 | Low | Sign-in racing a sign-out on one session answers 500, and the audit log records a sign-in success | Recommended: correct |
| D5 | Low | `truncate audit_event` succeeds for the application's role; the ledger tables, which use the same protection, were not tested | Recommended: correct, ledger included |
| D4 | Low | Some errors under `/api` are not in the contract's shape (an HTML 400 for encoded paths, the framework's body for failures in the security filters, a 500 for a malformed forwarded header from the trusted proxy) | Recommended: correct |
| D6 | Low | The log filter does not look inside hex-encoded values in driver error text; only values of rolled-back writes appeared | Recommended: correct |
| D3 | Low | A password change can sign out the person who made it when another request of that session is in flight (3 of 60 rounds) | Recommended: accept as a known limitation. Owner decides. |

Also from that review, not defects: scenario 13.6 says the database refuses re-marking after operator use, while what holds is that the flag cannot be unset and the `dev` profile then refuses to load; and Chromium itself resends a `POST` when a kept-alive connection is cut, so "exactly one request reaches the server" (3.17, 4.14) is true of the frontend's code, not of the browser. After a lost set-password answer the person can be told the credential could not be used although the password was set.

### Second round: corrections of D1, D2, D4, D5 and D6 at `28515a6`

The backend corrected D1, D2, D4, D5 and D6 in `2f03320`, `eaaeed7` and `9d49d27` (325 backend tests in CI), merged into the integration branch as `28515a6`. D3 is recorded in ADR 0002 as a known limitation, as recommended; the owner has not yet confirmed that.

- **Harness re-run at `28515a6`:** 79 checks pass, 2 partial (10.15, 10.16), none fails. Raw results: `tests/acceptance/results-2026-11-25-rerun-28515a6.json`. A first attempt at this re-run did not start, because a container and two volumes of the previous re-run had not been removed; `teardown.sh` now checks and reports what is left.
- **Direct checks by the coordinator, same environments:**
  - D2: 50 refused `POST /api/auth/login` with no cookie set no cookie and added no session row (6 before, 6 after).
  - D4: `%2F`, `%00`, `;x=1` and `//` paths answer 400 `validation_error` as JSON with a request identifier; `TRACE` answers 405 `method_not_allowed` on an API path and 404 `not_found` elsewhere, never an echo.
  - D5: `truncate audit_event` on the production-rules database is refused and its 165 rows remain. `truncate ledger_entry cascade` was started but its outcome was not captured, so the ledger half is **not confirmed here**; the backend's test asserts it.
  - D6 and finding 4: the production-rules backend log of the whole run holds no identifier-shaped text, no hex literal and no ERROR line.
  - D1 was not exercised by the coordinator; the backend's test covers it.
- **Left open by the backend, stated in its report:** the second factor and the password change racing a sign-out could still answer 500 (they fail closed; the reviewer saw no error there); the application's database role owns the tables and could drop the TRUNCATE trigger, which is a deployment decision; the backend's D2, D5 and most D4 tests were not run against the uncorrected code.
- **Not yet done:** the independent reviewer's targeted re-check of these five corrections.

### Independent re-check at `1d5a766`

The same reviewer re-checked the second round with its own environment and inputs (report kept outside the repository: "M1B Independent Re-check Report (1d5a766)"). `1d5a766` has the application code of `28515a6`. The reviewer did not run the backend's test suite and used neither it nor `tests/acceptance/` as evidence.

| Item | Result |
|---|---|
| D2 | Corrected. No session row, token row or cookie after a refused `POST`, on all nine operations, with no cookie, an unknown cookie and a session whose token row was deleted; 300 simultaneous refused sign-ins left nothing. |
| D1 | Corrected in both orders: 300 rounds, no 5xx, no refused sign-in recorded as a success. A stale or replayed cookie could not end another session. |
| D5 | Corrected for the seven protected tables, directly and through `CASCADE` from all 26 tables, as a non-superuser owning role. This also settles the ledger half the coordinator had not confirmed. |
| D4 | Corrected. No HTML or framework body under `/api`; the generic 500 for failures inside the filters; malformed `X-Forwarded-For` no longer 500; `TRACE` echoes nothing; no other method became reachable. |
| D6 | Corrected at INFO, DEBUG and TRACE. None of the 8,438 secret values the reviewer's clients saw is in any log. |
| Second factor or password change racing sign-out | No 500 in 244 rounds. One new defect, R1. |
| Regression | Findings 2 to 5 still corrected; 22 of 22 checks through the real frontend, including enrollment over HTTPS under production rules. |

**R1 (low; corrected afterwards, see below).** A sign-out that races a password change of the same session can answer 204 while the session stays signed in under the identifier the password change issued (25 of 210 rounds, sign-out sent 52 to 74 ms after the change). The sign-out request is refused the session at step 6 because the security version changed, finds no session, and never reaches the successor lookup added for D1. The session belongs to the same person; it needs two actions of one session within milliseconds. It breaks the rule stated for D1: after a sign-out answered 204 nobody is left signed in.

Stated by the reviewer, no action required for this milestone:

- The role that owns the tables can disable or drop the TRUNCATE trigger; a non-owning application role cannot. This is a deployment decision.
- Tables without a row-level rule (for example `account_password`, `auth_throttle`) can still be truncated; `DELETE` could already empty them.
- On a real database where the operator command has never run, the application's role can add the marker schema and then truncate. After the first operator command this is refused.
- A second factor racing a sign-out answers 200 `authenticated` for a session that no longer exists; the next request answers 401.
- A password change can answer 500 and still have changed the password if the session store fails at that instant.
- The forwarded-address check accepts strings that are not addresses from the trusted proxy.

Reviewer's verdict: acceptable from its side, with R1 to be fixed or accepted by the owner; not a statement of production readiness. Not tested there: D3, real-time expiry, other browsers, screen reader, password manager.

### R1 corrected at `29d623c`

The owner chose to have R1 fixed. The backend corrected it in `0d54613` (test first, failing in CI on the old code) and `54c0ff9` (328 backend tests), merged into the integration branch as `29d623c`. Sign-out now names the session by the identifier the request arrived with, so it also ends a session a password change has just renamed. No contract, error code or order of checks changed. The second-factor and enrollment paths did not have the gap.

- **Review of the change by the coordinator:** nothing wrong found. A request reaches the operation only with a live session and that session's own token at step 4, so a stale cookie cannot use the lookup to end another session, as the reviewer had established for D1.
- **Whole harness at `29d623c`:** 79 PASS, 2 PARTIAL (10.15, 10.16), 0 FAIL (`tests/acceptance/results-2026-11-25-rerun-29d623c.json`). Production-rule backend log: no identifier-shaped text, no hex literal, no ERROR line. `devclock.mjs` and `oldid.mjs` were throttled in the sequence and were repeated with cleared counters: both as expected.
- **R1 itself, `tests/acceptance/r1.mjs`, 150 rounds each:**

| Build | Both answered 204 | New cookie still signed in after a 204 sign-out | 5xx |
|---|---|---|---|
| `29d623c` (corrected) | 88 | 0 | 0 |
| `919515c` (before the correction), as control | 28 | 2 | 0 |

The control shows the script can see the defect; its rate is lower than the reviewer's 25 of 210. The check is statistical: the order cannot be forced from outside, which the backend's own test does with a held request.

### Third round by the independent reviewer at `7aa1679`: R1 corrected, R2 and R3 found

Report kept outside the repository: "M1B Independent R1 Report (7aa1679)".

- **R1:** corrected. 300 rounds, 185 with both answers 204, nobody left signed in. D1 still holds; the second-factor and enrollment paths have no gap; the new set-password message is shown in both languages.
- **R2 (medium, introduced by the R1 correction).** `POST /api/auth/logout` with two values of the session cookie, first a replaced identifier of another session, then the caller's own live session, and the caller's own token: 204, and the other session, living under its new identifier, is deleted. Deterministic, possible for an anonymous caller, on `dev` and under production rules; no audit record for the victim. It needs one replaced identifier of the victim and gives no access, only a forced sign-out. The session the request resolves is the caller's, so the token check passes, while the identifier the request "arrived with" is the first cookie value. Every single-cookie form is refused.
- **R3 (low, not new).** A session cookie that decodes to text containing a null byte answers 500 on every path, public reads included, with an ERROR line and stack trace per request.

The coordinator's review of the R1 correction (above) concluded that a stale cookie could not use the lookup. That was wrong for a request carrying two session cookies; the coordinator's check was by reading the code and did not consider that input.

### R2 and R3 corrected at `8a21b2e`

The backend corrected both in `87f433d` (tests first, two failing in CI on the old code) and `0ac6bff` (331 backend tests), merged into the integration branch as `8a21b2e`.

- **R2:** the first identity filter records the identifier of the stored session the request resolved, before the deadline check and step 4; sign-out ends that session and its successor and nothing else. Cookie text is no longer read by identity code (the second use, in the D1 correction, was changed too). A request with several session cookies is not refused: it resolves the first value that names a live session, and the token must be that session's. In production the `__Host-` prefix keeps a browser from holding two.
- **R3:** a cookie value that is not a lower-case UUID is dropped before any lookup, so it is a request with no session cookie.
- No contract, error code or order of checks changed.

Verified by the coordinator, this time by attack and not only by reading (`tests/acceptance/r2.mjs`, deterministic):

| Check | `8a21b2e` (corrected) | `d221564` (before), as control |
|---|---|---|
| Sign-out with a victim's retired identifier and the caller's live session, both orders, anonymous and signed-in caller, identifier replaced once or twice (12 arrangements) | Victim signed in after every one | Victim signed out whenever the retired identifier came first |
| Victim's live identifier with the caller's token; retired identifier alone | 403 `csrf_invalid`, victim untouched | Same |
| Cookie decoding to a null byte, on four `GET` paths and a `POST` | 200, 401, 200, 200, 403; no ERROR line | 500 on all five |
| Other malformed values (not Base64, 5 KB, upper-case identifier) | As an unknown cookie | As an unknown cookie |

- **R1 again (`r1.mjs`, 150 rounds):** 60 with both answers 204, none left signed in, no 5xx.
- **Whole harness at `8a21b2e`:** 79 PASS, 2 PARTIAL (10.15, 10.16), 0 FAIL (`tests/acceptance/results-2026-11-25-rerun-8a21b2e.json`). Both backend logs for the whole run: no identifier-shaped text, no hex literal, no ERROR line.

### Fourth round by the independent reviewer at `bf8c33d`

Report kept outside the repository: "M1B Independent R2-R3 Report (bf8c33d)". `bf8c33d` has the application code of `8a21b2e`.

- **R2:** corrected. 256 cookie arrangements over sign-out, sign-in, second factor and password change, anonymous and signed-in caller, `dev` and production rules: no request ended or altered a session other than the one whose token it showed.
- **Recorded identifier against the checked session:** 175 requests refused before the record or the operation ended nothing; 60 rounds of concurrent sign-outs clean; a bystander's live identifier as a second cookie never touched the bystander.
- **R3:** corrected. 448 requests with 28 malformed values on eight paths: every answer equals the unknown-cookie answer, no 5xx, no ERROR line.
- **R1 and D1:** still hold, 200 rounds each.
- **Frontend:** sign-in, password change and sign-out on `dev` and over HTTPS under production rules.
- **Logs:** none of 8,987 secret values, no hex literal, no password hash, no ERROR line.

No new defect. Reviewer's verdict: acceptable from its side at this commit; not a statement of production readiness. Not tested there: the framework's cookie and CSRF internals in source, other browsers, the backend test suite, CI. The races are statistical.

### Unstable backend tests corrected at `3a3d494` (tests only)

CI on `fe0080a`, a commit that changed only this document, failed in one of its two backend jobs while the other passed the same code. Two tests depended on the order of test classes: a log assertion matched the word "Exception" in TRACE lines left on by an earlier class, and the D1 race test counted an audit record for a sign-in refused at the token check, which correctly writes none. The backend corrected them and a third found by running other orders, in `3a3d494`: 332 tests, six full CI runs in default, reverse and random order after the last correction. `backend/src/main` and `backend/pom.xml` are identical to `0ac6bff`, the code of the fourth round, so neither the harness nor the reviewer was run again. The coordinator read the reworked race test: it now checks more than before, not less.

## After the merge: the owner's manual test (2026-11-25 and 26)

Milestone 1B was merged to `main` at `d296ac6`. The owner then tested by hand, as a super administrator created with the operator command, on a private site under production rules (`deploy/test/`, branch `integration/test-site`): Firefox on a Linux desktop and Safari on an iPhone. Not done: VoiceOver, other testers, Windows, macOS, Android. The site and its data were wiped afterwards.

Found and corrected on `feature/m1b-manual-test-fixes`:

| Found | Correction |
|---|---|
| The home page failed as a whole on a real, empty database: `/api/site` announces `fundCode`, and `GET /api/cases/FUND` answers 404 there. The acceptance run had never opened the home page under production rules. | The home page shows the fund panel as unavailable. Open for the backend at the start of milestone 2: a real database has no general fund. |
| A wrong password was reported twice, under the field and under the form (present since M1B). | Reported once, on the field, as an alert. Same for a wrong code or credential. |
| No sign that a new password is too short before submitting. | Red edge and a count on new-password fields under 15 characters; submitting is unchanged and the server still decides. |
| No way to an account in the header. | "Sign in", or "My account" for a signed-in visitor. A public page reads `GET /me` once for it; on identity screens the header takes the fact from the session store, so no page reads twice. Nothing is stored. |
| The header scrolled away. | It stays at the top; at phone width it leaves while scrolling down and returns on scrolling up. |
| The test site's proxy presented no certificate when addressed by a network address. | `default_sni` in `deploy/test/Caddyfile`. |

Asked and kept as they are, by the owner: the 15-character minimum, the mandatory authenticator for staff, and the session cookie that ends when the browser is quit.

Verification at `8347376` plus the harness files of this section: the whole harness, 79 PASS, 2 PARTIAL (10.15, 10.16), 0 FAIL; `a3.mjs`, 9 PASS; R1 150 rounds and R2 clean; both backend logs without identifier, hex literal or ERROR line (`tests/acceptance/results-2026-11-26-manual-test-fixes.json`). A first rerun failed 10.6, 14.11, 3.3 and 10.6f: the header then read `GET /me` beside the session store, where scenario 14.11 requires exactly one. The header was changed, not the harness. Public pages now make one identity request each, where they made none.

Not covered by an automated check: the home page on a database without a fund (checked by the coordinator in a browser on the test site), and the phone header's hiding (measured by the coordinator in a local preview at phone width; the owner tested on an iPhone afterwards and reported no problem with it, without confirming it specifically).

### Still open

- **Decided by the owner on 2026-11-25:** D3 is accepted as a known limitation (ADR 0002). Both contract wording proposals are approved, with the reviewer's sentence that a 429 from an email or account counter is a step-8 answer; `contract/identity-and-security.md` 4.3 now carries them, version unchanged at 0.3.0. The set-password `credential_invalid` message gains, in both languages, the hint to try signing in with the new password (`src/identity/copy.ts`). These three changes are not in the commit the reviewer is re-checking (`1d5a766`); the only application change among them is that message text.
- The owner's acceptance of the milestone and authorization to merge.
- Known and accepted or deferred: D3 (ADR 0002); the role owning the tables can remove the TRUNCATE protection (deployment decision).
- 10.15 and 10.16 need a real screen reader and a real password manager.
- Everything under "Not done" below that concerns production.

## Result of the first run (`cf0c883`)

| | Scenarios | PASS | PARTIAL | FAIL |
|---|---:|---:|---:|---:|
| Backend-asserted, browser repeat owed | 10 | 10 | 0 | 0 |
| Browser only | 32 | 26 | 6 | 0 |
| Partly covered (5.7, 7.19, 7.20) | 3 | 0 | 3 | 0 |
| Backend-asserted, not repeated in a browser | 175 | not re-run here | | |

Do not read this as "220 passed". The 175 backend-only scenarios rest on the backend CI run at `cf0c883`; two of them are contradicted by findings 2 and 3 below for inputs the backend suite does not send.

## What was tested

- Commit: `cf0c8833985e0eefa44305a02bfb2858c90789a1` (`integration/m1b-identity`), plus, from the second environment-A run on, the one-line proxy correction of finding 1.
- Browser: Chromium 143 (Playwright 1.57.0), headless.
- Environment A, "dev": `dev` profile, fictional fixtures, open sign-up, a new PostgreSQL 18.7 container marked disposable at creation, the project's `vite preview` with its HTTP policy headers, `http://127.0.0.1:4173`.
- Environment B, "production rules": no profile, a separate new PostgreSQL 18.7 container never marked disposable, test-only keys, the synthetic 100,000,005-entry list, `APV_ALLOWED_ORIGINS=https://localhost:8443`, staff created only with the operator command, an HTTPS front on `https://localhost:8443` (self-signed) that serves `dist` with `security/content-policy.mjs` headers and forwards `/api` unchanged.
- Both databases, their volumes, the keys and the certificate were created for this run and deleted after it. No existing database was touched.
- Harness and raw results: `tests/acceptance/` (`README.md` there says how to repeat it; `results-2026-11-25.json` is every recorded check, including the first-run failures explained below).

## Findings

### 1. Frontend, fixed here: the development and preview proxy made every identity POST fail

`vite.config.ts` gave the proxy target as a string. Vite then rewrites the `Host` header to the backend's address, so the backend saw `Origin: http://127.0.0.1:4173` on a request addressed to `127.0.0.1:8080` and answered every `POST` with 403 `forbidden`. Sign-up, sign-in and every other identity operation failed in `npm run dev` and `npm run preview` against a real backend. The intercepted browser tests in CI could not see this.

Fixed in `vite.config.ts` (`changeOrigin: false`). After the fix sign-up answers 202 through the preview. A production proxy needs the same care: forward `Host` unchanged, or set `APV_ALLOWED_ORIGINS`. `backend/README.md` says the origin check passes "through the Vite proxy"; that sentence was not true before this fix.

### 2. Backend, open: a corrupted CSRF token is answered 500, not 403 `csrf_invalid`

A token of the right length and alphabet with a few characters changed makes Spring Security's `XorCsrfTokenRequestAttributeHandler` throw `IllegalArgumentException: Decoding failed`. Nothing catches it.

- 33 of 40 such requests to `POST /api/auth/login` answered 500 with no error body; 7 answered 403 (`tests/acceptance/csrf500.mjs`). No session or credential is needed.
- Each one writes an ERROR with a 60-line stack trace.
- The request is refused before anything is processed, so it fails closed. The frontend treated the 500 as an unknown outcome, read `GET /me` and resent nothing.
- It contradicts `identity-and-security.md` 9.1 (a bad token is 403 `csrf_invalid`) and 9.7 (every error has the `Error` body).
- A genuine token of another session, which is what "stale" means in practice, is answered 403 correctly. That is why the backend suite did not find it.

Wanted: a refusal of 403 `csrf_invalid` for any token value, and a test that sends several hundred random tokens of the right shape to every `POST`.

### 3. Backend, open: two simultaneous first token reads on one session answer 500

After sign-in the session has no stored CSRF token until it is first asked for. When two requests ask at once, both insert it and one fails with `DuplicateKeyException` on `spring_session_attributes_pk`. The exception handler then fails too, because the response is already committed.

- 10 rounds of two simultaneous `GET /api/auth/csrf` right after sign-in: one 200 and one 500 in every round (`tests/acceptance/csrfrace.mjs`).
- The application causes this itself: when one tab signs in or out, the other tab is told and both read a token at the same moment. It happened in the two-tab part of the run, and is the likely reason a further two-tab journey there did not complete.

Wanted: one token per session whoever asks first, no 500, and a concurrency test.

### 4. Backend, open: exception text is not covered by the log filter

`SensitiveLogFilter` redacts the message of a log line, not the exception attached to it. The stack traces of finding 3 printed `Key (session_primary_id, attribute_name)=(<UUID>, ...)` at ERROR, 20 times in this run.

That value is the session row's internal key, not the identifier the cookie carries, so no usable session identifier was logged. But the owner's rule is that no session identifier reaches a log with no exception, and the same path would print one if a database error ever named `session_id`. Wanted: redaction applied to exception messages (and their causes) as well, with a test.

### 5. Backend, open, development only: the pinned clock breaks sign-in for staff and freezes rate limits

The `dev` profile pins the application clock to the fixture's instant so public responses are repeatable. Identity uses the same clock.

- A code from a real authenticator is refused; only the code for 2026-11-24 12:00 UTC is accepted (`tests/acceptance/devclock.mjs`). Each development staff account can therefore sign in two or three times per backend start, then never. `backend/README.md` tells developers to use an authenticator app or `oathtool`, which cannot work.
- Rate-limit windows never end: after 120 token or options reads (about 60 page loads) every identity form answers 429 until the backend is restarted. The harness had to clear the counters of its disposable database during environment A.
- `GET /me` reports session deadlines in the past.

Production is not affected: nothing pins the clock there. Wanted: identity on the real clock in every profile.

### Review of the final backend corrections (no defect; recommendations)

- **Breached-password list.** Format, size floor, sampled order, known-password check and SHA-256 behave as documented; with environment B's settings the server and the operator command both refused to start when the variable was unset, the server refused a copy of the list altered by one byte, and it accepted the synthetic file. Three things the code cannot prove and the deployment must: (a) the manifest's SHA-256 sits beside the file, so replacing both goes unnoticed unless the digest recorded at build time is compared out of band; consider an optional `APV_PASSWORD_BLOCKLIST_SHA256` that the server checks; (b) order is checked at 4,096 places, which is enough for a file made by the builder (it refuses unsorted input) but not for an arbitrary file; (c) a synthetic file passes every check, so "comprehensive" is established by provenance records, not by the software. No real corpus has been downloaded or installed.
- **Session identifiers in logs.** Holds for log messages at INFO in this run (zero cookie values, identifiers or tokens among 51 secret values searched). See finding 4 for exception text. DEBUG and TRACE were not enabled here.
- **Shared protected-password budget.** Not re-measured in a browser; the backend test stands.

## Contract wording: two proposals for the owner

**Approved by the owner on 2026-11-25 and applied to the contract.** Both describe what is implemented and tested. Neither changes a schema, an error code or which request is refused. They need the owner's approval because they change the words of an accepted contract.

1. **Rate limits keyed on the body** (`identity-and-security.md` 4.3). The table puts all rate limits at step 5, but the email is not known until the body is validated at step 7. Proposed: step 5 reads "Rate limits keyed on the client address"; step 8 gains "Counters keyed on what is being guessed (email, account, pending sign-in) are advanced first, before the secret is checked (7.3); when exceeded the answer is 429". Consequence to state openly: a request refused at step 6 or 7 does not touch those counters, and their 429 comes after a 400, 401 or 409, not before.
2. **When a pending session is discarded** (4.3: "discarded first"). Proposed: "discarded once the request has passed step 7, immediately before the operation's own checks. A request refused at steps 1 to 7 leaves the pending sign-in as it was." The other reading, discarding before validation, would let a malformed request end someone's pending sign-in.

## Scenario map

Env A or B as above. "Simulated time" means the stored session or pending sign-in was aged in the isolated database; nobody waited.

### Browser repeat owed (10)

| # | Result | Env | What was asserted |
|---|---|---|---|
| 1.1 | PASS | A | Sign-up in the UI: 202, not signed in; then sign-in 200 `authenticated`; account shows donor, not verified. After the fix of finding 1. |
| 1.6 | PASS | B | Wrong password, unknown address, staff not activated, disabled account with its correct password: same 401 `invalid_credentials` body and same message on the password field. |
| 1.10 | PASS | B | `registrationOpen` false; closed message and no form; a forced `POST` from the page is 403 `registration_closed`; no account row. |
| 3.6 | PASS | B | Sign out in the UI; old cookie then 401 `unauthenticated` on `GET /me`, 403 `csrf_invalid` on a `POST` with the old token; session row gone. |
| 3.8 | PASS | B | Three browsers signed in; password changed in one: it continues on a new identifier, exactly one signed-in session row remains, the others are ended (see 10.6). |
| 3.14 | PASS | B | Signed in: `/sign-in`, `/sign-up`, `/auth/set-password` lead to the account; forced `POST`s answer 409 `already_signed_in`; session unchanged. |
| 4.4 | PASS | B | Token fetched before sign-in is 403 after it; a fresh token is accepted. |
| 6.1 | PASS | B | Activated staff signs in: `mfa_enrollment_required`, `pendingExpiresAt`, no account; enrollment screen; private screens unreachable; `GET /me` 401 `mfa_enrollment_required`. |
| 6.2 | PASS | B | Correct code: ten codes shown once, `authenticated`, cookie value changed. |
| 6.6 | PASS | B | `mfa_required`, then a correct code gives `authenticated` with the account. |

### Browser only (32)

| # | Result | Env | What was asserted, and limits |
|---|---|---|---|
| 3.11 | PASS | A, B | `document.cookie` and the Cookie Store are empty; the cookie is `HttpOnly`. |
| 3.17 | PASS | A, B | Answer dropped after real server processing for sign-up, sign-in (two variants), second factor, enrollment start, enrollment confirmation, password change, set password, regeneration, sign-out. Right state reached through `GET /me`; the `POST` was sent exactly once each time. Limit: which cookie the browser held after a dropped answer could not be fully controlled by the tool. |
| 4.13 | PASS | B | Tab shows X; the same browser becomes Y unannounced; X's password change is refused `csrf_invalid`, token fetched, `GET /me` shows Y, form emptied, nothing resent, Y's password row unchanged. |
| 4.14 | PASS | A, B | A genuine token of another session on each of the nine `POST`s: one request, 403 `csrf_invalid`, one `GET /auth/csrf`, one `GET /me`, the out-of-date message, fields empty, no resend. With a corrupted token the server answers 500 instead (finding 2); the frontend still resent nothing. |
| 4.15 | PASS | A, B | The same person submits again after each of the nine; it succeeds with the new token. |
| 4.16a | PASS | B | Tab on X's challenge, browser becomes pending as Y, X's code submitted: one `POST`, `csrf_invalid`, `GET /me` 401 `mfa_required`, empty challenge again; Y's failure count stayed 0. |
| 4.17 | PASS | B | Token not in a cookie, storage, IndexedDB, the address, the DOM or a global. |
| 6.23 | PASS | B | The drawn QR, decoded offline, equals the server's `otpauth://totp/APV-GAKASINAY:<email>?secret=…&issuer=APV-GAKASINAY&algorithm=SHA1&digits=6&period=30`; manual secret matches; no request carries either; no external request. |
| 10.1 | PASS | A, B | No secret in any request URL, navigation URL or `Referer` (827 requests in A; 101 requests and 17 navigations in B, against 51 secret values including session identifiers). |
| 10.2 | PASS | B | A fragment on `/auth/set-password` is gone from the address and history before the app starts; never sent. |
| 10.3 | PASS | A, B | Storage holds `apv-locale` and `apv-theme` only; no IndexedDB, cache storage or script-readable cookie. |
| 10.4 | PASS | A, B | No secret in console output, failures included; no reporting request. |
| 10.5 | PASS | B | After sign-out: sign-in screen, no account data in the page, `/account` leads to sign-in. |
| 10.6 | PASS | B | Session ended elsewhere: a `GET` gives 401 and the sign-in screen; a `POST` gives 403 `csrf_invalid`, one token fetch, `GET /me` 401, sign-in screen, not resent. |
| 10.6a | PASS | B | Wrong current password: error and focus on that field, same screen, `GET /me` 200. |
| 10.6b | PASS | B | Wrong codes one to four: challenge stays, error on the field, field cleared and focused; then a correct code signs in. |
| 10.6c | PASS | B | Fifth wrong code: 401 `unauthenticated`, password step with a message, no session row; a forced correct code is 403 `csrf_invalid`. |
| 10.6d | PASS | B | Wrong code on regeneration: error on the code field, still signed in. |
| 10.6e | PASS | B | Reload while pending shows the challenge, or the enrollment screen. |
| 10.6f | PASS | B | Pending sign-in expired, then a correct code: 403 `csrf_invalid`, token, `GET /me` 401, password step, code sent once. Simulated time. |
| 10.7 | PASS | B | Another tab signs out, then signs in as another account: this tab follows without being used. Limit: the visibility fallback (no `BroadcastChannel`) was not exercised. The two tabs acting together is what triggers finding 3. |
| 10.8 | PARTIAL | B | Back after sign-out re-reads `GET /me` and shows sign-in. Restoration from the back-forward cache was not observed in headless Chromium. |
| 10.9 | PASS | A, B | 64 loads of eight screens under the HTTP policy in A, and every screen of B: no violation, no other origin. |
| 10.9a | PASS | A | With the application bundle blocked, `html[data-theme]` is already set by `theme-init.js`, both themes. |
| 10.10 | PASS | B | Backend process stopped: error and "Try again", no account data, no form, no mock. Restarted: "Try again" shows the same person signed in, nothing resent. A 500 answer was also seen (finding 2) and handled the same way. |
| 10.11 | PASS | build | `npm run test:production-build` passes; the bundle holds no fixture or mock client module. One `apiMode==="mock"` comparison and the preview notice text remain as dead branches. |
| 10.12 | PARTIAL | A | Eight screens in both languages: `lang` follows, no text identical in both. Not swept: the enrollment screen and the recovery-code display (seen in English in B). Error messages exist in both by type check, not each shown. |
| 10.13 | PARTIAL | A | Same eight screens, light and dark, 360 and 1440 px: no overflow, nothing clipped, lowest text contrast 5:1. Same two screens not swept. |
| 10.14 | PARTIAL | A | Seven screens: Tab reaches every control in order with visible focus; Enter submits; focus moves to the field in error. Same two screens not swept. |
| 10.15 | PARTIAL | A | `aria-invalid`, `aria-describedby`, error text and focus verified for five errors. No screen reader was run. |
| 10.16 | PARTIAL | A | `autocomplete` values, labels and paste verified on six forms. No password manager was run. |
| 14.11 | PASS | B | After a `POST` on a session that no longer exists: one `GET /auth/csrf`, one `GET /me` 401 `unauthenticated`, sign-in screen, nothing resent. The session had been ended by a password change elsewhere, not by sign-out. |

### Partly covered (3)

| # | Result | Note |
|---|---|---|
| 5.7 | PARTIAL | Unchanged: no staff-only or super-admin-only business operation exists yet. |
| 7.19 | PARTIAL | New: the process list was captured while an operator command waited for input and held the command name only. Shell history remains a manual check of a real operator session. |
| 7.20 | PARTIAL | Unchanged: the command asks for and records the identity check; whether a person performs it is a procedure. |

### Backend-asserted scenarios also seen in the browser

Not re-scored. 2.9 (a password on the list is refused `compromised`, synthetic list), 3.3 and 3.4 (idle and absolute expiry, simulated time), 3.5 (30 min and 12 h for staff, 2 h and 24 h for a donor, 10 min pending), 3.9 (`__Host-apv_session`, `Secure`, `HttpOnly`, `SameSite=Lax`, host-only, session lifetime, the only cookie, over HTTPS), 6.8 (replayed code refused inside its window), 6.11, 6.14, 6.16, 6.19, 7.2, 7.3, 7.6 (an unknown credential only), the audit half of 9.8 (200 rows, no secret).

## First-run failures that were not application defects

Kept in the raw results. Each was repeated with the corrected harness and passed.

- 10.6, 3.3, 10.6f: the expected request sequence left out the sign-in screen's own `GET /auth/options`.
- 4.14 in B: the token was altered character by character, which is finding 2, not a stale token.
- "public": a browser global was used in Node.
- 1.1, 3.17, 4.14, 4.15, 6.16 in A (first run): finding 1, before its fix.

## Not done

- Independent acceptance by Traycer (assignment below).
- Re-run after the backend corrections of findings 2 to 5.
- A screen reader, a password manager, Firefox and Safari, a phone.
- Real waiting for expiry; DEBUG and TRACE log levels in the browser run.
- A real breach corpus, production host, proxy and TLS, production keys, Argon2 calibration, backups.

## Assignment for Traycer

Independent runtime security acceptance of milestone 1B. Work from the commit named in PR #12 at the time you start, and state it. Do not merge anything. Do not use `tests/acceptance/` as your evidence; you may read it.

1. Build the backend and the real-mode frontend from that commit. Use only databases you create for this purpose: one marked disposable for the `dev` profile, one unmarked for production rules. Test-only keys. Fictional accounts.
2. Every identity request must be a real request with a real cookie. Do not stub any `/api` answer. Dropping an answer after the server has processed it is allowed.
3. Reproduce or refute findings 2, 3, 4 and 5 above, each with your own inputs. If the backend has corrected them, verify the corrections and try to break them: random tokens of valid shape on every `POST`; many simultaneous first token reads; a forced database error that names a session identifier; staff sign-in in the `dev` profile with a real authenticator.
4. Verify the proxy correction of finding 1, and state what a production reverse proxy must do for the origin check.
5. Re-run the 42 browser scenarios by ID. Give your own PASS, PARTIAL or FAIL for each. Close the six PARTIAL rows where you can: back-forward cache restoration, the enrollment and recovery-code screens in French, dark and 360 px, a screen reader, a password manager.
6. Attack what this run did not: two tabs racing a sign-in against a sign-out; a session cookie replayed after rotation at each of the three rotations; the old identifier during a password change; `POST`s from another origin and from a framed page; a `text/plain` body; `X-Forwarded-For` from an untrusted address; the 30-request shared budget across change-password and regeneration; the fifth wrong code sent five times at once.
7. Production configuration: start-up refusals (no keys, no list, a list that is too small, unsorted, altered, or lacking the known passwords; a non-`Secure` cookie; open sign-up), the cookie over HTTPS, and the operator command (process list, what it prints, what it logs, the last-super-administrator rule under concurrency).
8. Logs at INFO, DEBUG and TRACE after your whole run, searched for every secret you used and for every session identifier, including those in exception text.
9. Give an opinion on the two contract wording proposals.
10. Report: commit, environment, each scenario's result with what you asserted, every defect with a reproduction, and what you could not test.
