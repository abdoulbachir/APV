# M1B joint identity acceptance harness

Drives the real production frontend build in Chromium against the real Spring Boot backend and real PostgreSQL. No identity response is mocked. It is run by hand; no CI workflow runs it. Results of the run of 2026-11-25 and what they mean: `docs/m1b-acceptance-evidence.md`.

It needs the backend, so it runs on the integration branch, not on the frontend branch alone.

## Run

```bash
tests/acceptance/setup.sh            # about three minutes; creates everything, all of it new
cd tests/acceptance
node b1.mjs && node b2.mjs && node b3.mjs && node b4.mjs    # production rules, HTTPS; about 20 minutes
node a1.mjs && node a2.mjs                                   # dev profile, HTTP preview; about 3 minutes
./teardown.sh
```

Needs Docker, Java 21, Node 24 and `openssl`. Ports 8080, 8081, 4173, 8443, 55432 and 55433 must be free. Chromium comes from `npx playwright install chromium` if it is not already cached.

Run the scripts in that order and one at a time: `b2` to `b4` continue with the accounts `b1` created, and all of them write `evidence/results.json`.

## What setup creates

- Two PostgreSQL 18.7 containers with their own names, ports and volumes: `apv-m1b-accept-dev` (marked disposable when created) and `apv-m1b-accept-prod` (never marked). `setup.sh` refuses to run if either name already exists, and never connects to any other database.
- `env/`: random database passwords and keys for this run, a self-signed certificate, and a synthetic breached-password list of production size made by the backend's own test generator (2 GB; not breach data).
- Four processes: the `dev` backend on 8080, the production-rule backend on 8081, `vite preview` on 4173, and `tls-proxy.mjs` on 8443, which serves `dist` with the project's policy headers and forwards `/api` and `/media/public` unchanged.

`teardown.sh` removes the processes, the two containers, their two volumes and `env/`. `env/` and `evidence/` are ignored by git; `evidence/` holds logs that print the development accounts' passwords.

## Files

| File | Covers |
|---|---|
| `b1.mjs` | Closed sign-up, operator provisioning, credential redemption, enrollment and the QR decoded offline, cookie attributes, sign-out, second-factor challenge, code and recovery-code replay |
| `b2.mjs` | Regeneration, password rules and change across three browsers, signed-in refusals, token lifetime and location, idle, absolute and pending expiry, the fifth wrong code |
| `b3.mjs` | A stale token on every `POST`, a lost answer after every state change, one browser changing identity under an open tab, announced changes between tabs |
| `b4.mjs` | The four refused sign-ins, the process list during an operator command, a whole staff journey searched for leaks (URLs, referrers, console, storage, server logs, audit rows), backend stopped and restarted |
| `a1.mjs`, `a2.mjs` | Sign-up, policy headers, framing, theme before paint, eight screens in two languages, two themes and two widths, contrast, keyboard, field semantics, public pages with a session cookie |
| `csrf500.mjs`, `csrfrace.mjs`, `devclock.mjs`, `oldid.mjs` | Plain HTTP reproductions of findings 2, 3 and 5, and the check that an old identifier is dead after a password change. They talk to the `dev` backend on 8080. After `a1` and `a2` the address is throttled: clear `auth_throttle` in the harness's `dev` database before `devclock` and `oldid`. |
| `r1.mjs` | R1: sign-out racing a password change, 150 rounds; clears the counters itself. Exit 1 if a 204 sign-out leaves the new cookie signed in. |
| `lib.mjs`, `ui-checks.mjs` | Browser profiles with full observation, fault injection on real requests, TOTP, QR decoding, presentation checks |

## How faults are produced

- **Stale token**: the page's next `POST` is sent on with a genuine token the server issued to another session. The server answers.
- **Lost answer**: the page's next `POST` is really sent and processed; its answer is discarded before the page sees it.
- **Time**: the stored session's last-access time or deadline is moved into the past with SQL in the harness's own database.
- **Rate limits**: counters in the harness's own databases are cleared between groups, because every request comes from one address. In the `dev` profile they are cleared continuously (finding 5).

## Known limits

- The first run of 2026-11-25 was done from a scratch directory. The whole sequence was then run from this location at `bfb1abb` (`results-2026-11-25-rerun-bfb1abb.json`).
- `b1` and `b3` wait for a new 30-second step before each authenticator code, which is most of their running time.
- Headless Chromium only. No back-forward cache restoration, screen reader or password manager.
