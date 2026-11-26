# APV-GAKASINAY backend

The public read API of contract version 0.2.0 (`contract/openapi.yaml`): seven `GET` endpoints backed by PostgreSQL.

There is no sign-in, no donation reporting, no payment handling, no upload and no administration here. Those are later milestones.

## What you need

| Tool | Version | Used for |
|---|---|---|
| Java (JDK) | 21 | Building and running. `backend/.java-version` pins it for CI. |
| Docker with Compose | any recent | Local PostgreSQL, and the integration tests |
| Node | 24 | Generate development/test fixtures and run contract checks; not needed for a production backend build |

Maven is not needed: `./mvnw` downloads the pinned Maven 3.9.16.

## Commands

Run the `docker compose` commands from the repository root and the `./mvnw` commands from `backend/`.

| Task | Command |
|---|---|
| Prepare local settings (once) | `cp .env.example .env` |
| Start PostgreSQL | `docker compose up -d` |
| Start the backend with sample data | `node ../contract/tools/generate-dataset.mjs && (set -a; . ../.env; set +a; ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev)` |
| Run unit tests (no database, no Docker) | `./mvnw test` |
| Run everything, including PostgreSQL integration tests | `node ../contract/tools/generate-dataset.mjs && ./mvnw verify` |
| Run the contract checks | `cd ../contract && npm ci && npm run validate` |
| Look inside the database | `docker compose exec postgres psql -U apv apv` |
| Stop PostgreSQL, keep the data | `docker compose down` |
| Reset the development data | Restart the backend in the `dev` profile: it reloads the fictional dataset. Deleting the volume is rarely needed; read [Deleting the development database](#deleting-the-development-database) first. |
| Run an operator command (real database) | `java -jar target/apv-backend-0.1.0-SNAPSHOT.jar operator <command>`; see [Accounts and sign-in](#accounts-and-sign-in) |

Database migrations run by themselves when the backend starts (Flyway). There is no separate migration command. The application never drops or wipes a schema.

### Sample data

> **The `dev` profile is destructive.** Every time the backend starts in the `dev` profile it **deletes everything in the database** it is connected to and loads a fictional dataset. Use it only with the local development database from `compose.yaml`.

Generate the fixtures before starting the development backend or running integration tests. The generator uses only Node's standard library, and CI runs it automatically. The generated datasets are intentionally absent from a clean checkout and from the packaged backend. By default the development profile loads the full shared dataset, `contract/fixtures/dataset.json`: 200 current cases, two completed, two drafts, the general fund and 292 ledger entries, all fictional. Set `APV_FIXTURES_PATH` to load another file of the same format, for example `../contract/fixtures/example.json` for the small one. The clock of the public read responses is pinned to the instant written in the loaded file (2026-11-24 12:00 UTC for both), so those responses are repeatable. Only that clock is pinned: sign-in, authenticator codes, sessions and rate limits always run on the real time.

Three things stand between the loader and a database that matters:

1. **It does not exist outside development.** The loader is only created in the `dev` and `test` profiles. Without them there is no code path that loads a fixture, and a profile named `prod` or `production` refuses to start together with `dev` or `test`.
2. **Local databases only.** It refuses a database that is not on this machine (`localhost`, `127.0.0.1`, `[::1]`). A container host name can be allowed explicitly with `apv.fixtures.allowed-hosts`.
3. **Never over real data.** The database records whether its content came from a fixture. The loader replaces an empty database or fixture data, and refuses when the database holds cases that did not come from a fixture.
4. **Only a database marked disposable.** Since milestone 1B the loader and the development accounts need a marker that only the creation of a development or test database writes: the schema `apv_disposable_database`, created by `backend/dev/initdb/01-disposable-database.sql` when the Compose database is first created, and by the test setup. The application and the migrations never create it. A database without it is refused, empty or not. A database the operator command has been used on is real for ever and is refused even with the marker.

> **If your development database was created before milestone 1B** it has no marker, and the `dev` profile will refuse to load data into it. Nothing needs to be deleted. After checking that it really is your local development database (next section, steps 1 to 3), mark it:
>
> ```bash
> docker compose exec postgres psql -U apv apv -c 'create schema if not exists apv_disposable_database'
> ```

#### Deleting the development database

`docker compose down -v` deletes the database volume of this Compose project, with everything in it, and cannot be undone. It is only ever for the local development database that `compose.yaml` in this repository created. **Never run it, or any other volume or database deletion, on a server, against a production deployment, or in a directory whose `compose.yaml` is not this one.** Before running it, verify all of the following:

1. You are on your own development machine, in the root of this repository, and `docker compose ls` shows the project `apv-dev`.
2. The database is disposable and has never been used for real: both of these print `t`.
   ```bash
   docker compose exec postgres psql -U apv apv -Atc "select exists (select 1 from information_schema.schemata where schema_name = 'apv_disposable_database')"
   docker compose exec postgres psql -U apv apv -Atc "select not database_real from identity_guard"
   ```
   A database created before milestone 1B has neither yet; then check instead that it holds only fixture data: `select data_origin from organization_settings` prints `fixture`.
3. Nothing in it is worth keeping. Development accounts you created yourself are lost; the three `dev.*@example.test` accounts are recreated at the next start.

Only then: `docker compose down -v`, followed by `docker compose up -d`. If any check fails or you are unsure, stop: restarting the backend in the `dev` profile already resets the fictional data without deleting anything else.


A fresh production database contains the six initial categories, the default timezone and nothing else.

### Try the endpoints

With the backend running in the `dev` profile:

```bash
curl -s localhost:8080/api/site
curl -s localhost:8080/api/stats
curl -s 'localhost:8080/api/cases?status=active&sort=closest&pageSize=12'
curl -s -H 'Accept-Language: fr' localhost:8080/api/cases/HELP-014
curl -s localhost:8080/api/cases/HELP-014/updates
curl -s localhost:8080/api/cases/HELP-101/donors
curl -s 'localhost:8080/api/cases/HELP-101/ledger?page=1&pageSize=5'
curl -s localhost:8080/api/cases/FUND
```

### Settings

All settings come from environment variables. `.env.example` lists them with safe placeholder values. `.env` is ignored by git.

| Variable | Default | Meaning |
|---|---|---|
| `APV_DATABASE_URL` | `jdbc:postgresql://localhost:5432/apv` | Database address |
| `APV_DATABASE_USER` | `apv` | Database user |
| `APV_DATABASE_PASSWORD` | none | Database password. No default on purpose. |
| `APV_PORT` | `8080` | Port the backend listens on |
| `APV_BIND_ADDRESS` | `127.0.0.1` | Address the backend listens on. This machine only by default. |
| `APV_FIXTURES_PATH` | `../contract/fixtures/dataset.json` | `dev` profile only: the dataset that replaces the database content at every start |
| `APV_FIXTURES_MEDIA_ROOT` | `../contract/fixtures` | `dev` profile only: folder sample pictures are served from |
| `APV_MFA_ENCRYPTION_KEY` | none | 32 random bytes, Base64. Encrypts authenticator secrets. **Required outside `dev` and `test`**; there it is generated at each start when unset. |
| `APV_HASH_KEY` | none | 32 random bytes, Base64. Keys the hashes of recovery codes and rate-limit counters. **Required outside `dev` and `test`.** |
| `APV_SESSION_COOKIE_NAME` | `__Host-apv_session` | Session cookie name. Outside `dev` and `test` it must start with `__Host-`. |
| `APV_SESSION_COOKIE_SECURE` | `true` | Outside `dev` and `test` the backend refuses to start with `false`. |
| `APV_REGISTRATION_OPEN` | `false` | Public sign-up. The backend refuses to start with `true` outside `dev` and `test`, because it cannot verify an email address yet. |
| `APV_TRUSTED_PROXIES` | empty | Comma-separated addresses of reverse proxies whose `X-Forwarded-For` (right-most entry) is believed. Empty: the connection's address is used. |
| `APV_ALLOWED_ORIGINS` | empty | The exact origins accepted on state-changing requests, comma-separated. Empty: the origin must equal the scheme, host and port the request was addressed to. **Set it in production behind a TLS-terminating proxy** (`https://your.site`), where the backend itself sees plain HTTP. A proxy that leaves it empty must forward the `Host` header unchanged; one that rewrites `Host` to the backend's address makes every `POST` answer 403 `forbidden`. |
| `APV_ARGON2_MEMORY_KIB` / `APV_ARGON2_ITERATIONS` | `19456` / `2` | Argon2id cost. May be raised, never lowered: lower values refuse to start. Existing hashes are upgraded at the next sign-in. |
| `APV_HASH_CONCURRENCY` | `4` | Password hashes computed at once: 1 to 4, on their own threads so the rest of the server keeps answering. A request waiting more than 2 seconds gets 503 with `Retry-After: 5`. The backend refuses to start with more than 4 at once or a longer wait, in every profile. |
| `APV_PASSWORD_BLOCKLIST_PATH` | empty | The comprehensive local breached-password list: a file of sorted SHA-1 hashes with its manifest. **Required outside `dev` and `test`**: the server and the operator command refuse to start when it is missing, unreadable or unsuitable. Source, format, verification, size and updates: [`docs/backend-password-blocklist.md`](../docs/backend-password-blocklist.md). In `dev` and `test` the small bundled list is enough. Never looked up over the network. |
| `APV_PASSWORD_BLOCKLIST_SHA256` | empty | Optional, recommended in production: the SHA-256 printed when the list was built. When set, the server and the operator command refuse a list whose manifest states another digest, so replacing the list together with its manifest is noticed. Update it with the list. |

Generate a key with `openssl rand -base64 32`. Keep both keys outside source control and back them up with the database: without `APV_MFA_ENCRYPTION_KEY` no enrolled authenticator can be verified, and staff would need `recover-staff-access`.

## Accounts and sign-in

Milestone 1B adds the twelve identity operations of `contract/openapi.yaml` 0.3.0 (`/api/auth/*` and `/api/me`). The rules are in `contract/identity-and-security.md`; the decisions, and the notes on how this implementation fills the gaps the contract left, in `docs/adr/0002-identity-and-sessions.md`; which acceptance scenario is covered by which test in `docs/m1b-backend-acceptance-coverage.md`.

### In development

The `dev` profile uses the cookie `apv_session` without `Secure` (plain HTTP on localhost), opens public sign-up, and creates three fictional accounts at every start, on a disposable database only. Their passwords and authenticator secrets are generated at that moment and **printed to the console**, never stored in a file:

```
DEVELOPMENT ACCOUNTS (fictional, this start-up only; passwords are regenerated at every start):
  donor  dev.donor@example.test  password: dev-...
  admin  dev.admin@example.test  password: dev-...  authenticator secret: ...
  super_admin  dev.owner@example.test  password: dev-...  authenticator secret: ...
```

Add the secret to any authenticator app (manual entry, time-based, SHA-1, 6 digits, 30 seconds), or compute a code with `oathtool --totp -b <secret>`. Codes are checked against the real time in every profile, the `dev` profile included, so what the app or `oathtool` shows now is what is accepted (the machine's clock has to be right, as for any authenticator). Because the keys are regenerated at each start in development unless you set them, sessions and enrolled authenticators of accounts you created yourself do not survive a restart; the three accounts above are recreated each time.

A browser session, by hand:

```bash
curl -s -c jar localhost:8080/api/auth/csrf                 # {"token": "...", "headerName": "X-CSRF-TOKEN"}
curl -s -b jar -c jar -H 'Content-Type: application/json' -H 'X-CSRF-TOKEN: <token>' \
     -d '{"email":"dev.donor@example.test","password":"<printed password>"}' localhost:8080/api/auth/login
curl -s -b jar localhost:8080/api/me
```

Every sign-in step replaces the session identifier and the CSRF token: fetch `/api/auth/csrf` again after each one.

### Operator commands (real databases)

Staff accounts are never created over HTTP and no account ever has a default password. The operator runs a command on the server, against the real database, with the same environment variables as the backend:

```bash
java -jar apv-backend.jar operator create-super-admin
```

Nothing listens on the network while it runs: it is a separate small start of the application without the web server. The command name is the only argument; the email, the display name and, for recoveries, how the person's identity was confirmed are asked for on the terminal, so nothing sensitive reaches the process list or the shell history. The command prints a **one-time credential**: it is shown once, stored only as a hash, works once, and only lets its holder choose a password.

| Command | What it does |
|---|---|
| `create-super-admin`, `create-admin` | Creates a staff account that cannot sign in yet, and prints an activation credential (60 minutes). After choosing a password the person has 60 minutes to set up their authenticator. |
| `issue-password-reset` | Prints a reset credential (30 minutes) for any account. The second factor is kept. Existing sessions end when it is used. |
| `recover-staff-access` | For staff who lost their authenticator and recovery codes: removes the password and the second factor at once, ends sessions, and prints a recovery credential (60 minutes). The old password stops working immediately. |
| `disable-account`, `enable-account` | Disabling ends the account's sessions at once. |
| `set-can-disburse` | Grants or removes the disbursement permission of an administrator. Ends that account's sessions. |
| `list-staff` | Staff accounts and their state. |

Rules the command and the database enforce:

- The first use on a database marks it **real**, for ever. Fictional data can never be loaded into it afterwards. The command refuses a database marked disposable.
- Once a super administrator has been usable (activated, enabled, authenticator enrolled), no command and no SQL statement can leave the site with none: the change is refused, also when two are attempted at the same time. To replace the only one, create and enroll a second one first.
- Recovery requires confirming the person through a channel you already had before the request. Facts supplied by the requester do not count. What you answer is written to the audit log.

First start of a real site: set the database variables and the two keys, build the breached-password list and set `APV_PASSWORD_BLOCKLIST_PATH` (`docs/backend-password-blocklist.md`), start the backend once (it migrates the database), run `operator create-super-admin`, hand the credential over, and let the person choose a password and enroll at the frontend.

### Logging

Logs never contain a session identifier (live, replaced or expired, in the stored form or as the cookie carries it), a password hash or a raw request, at any level. This does not depend on the logging configuration: a filter installed with the logging system (`SensitiveLogFilter`, `logback-spring.xml`) drops everything below INFO from the loggers that print raw requests, cookies, bound SQL values or a replaced session identifier (the embedded server, the database driver, Spring's SQL parameter logger, the session fixation strategy), and replaces identifier shapes and Argon2 hashes in every other message with `[redacted-id]` and `[redacted-hash]`. The exception attached to a log line is treated the same way: its message and those of its causes are searched (a database error quotes the row it failed on), and when something is replaced the stack trace is printed from a redacted copy, shown as `SensitiveLogFilter$Redacted: <original class>: <message>` with the original frames. Bytes written as a hexadecimal literal (`'\x…'::bytea`, which is how the database driver quotes a stored session attribute when a write fails) are replaced whole with `[redacted-bytes]`, whatever they hold. Setting those loggers to DEBUG or TRACE has no effect. Passwords, codes and one-time credentials have no recognisable shape; they are kept out by never being given to a logger. The default level, INFO, is the one to run with.

### What the server keeps

Sessions are rows in PostgreSQL (`spring_session`), so they survive a backend restart and can be ended from any instance. Passwords are Argon2id hashes; authenticator secrets are encrypted with a key that is not in the database; recovery codes, one-time credentials and rate-limit keys are stored as hashes. `audit_event` is append-only and never holds a password, code, secret, token or credential.

## For the frontend

- Base address in development: `http://localhost:8080`. The API is under `/api`, pictures under `/media/public`.
- Proxy both paths from Vite so the browser sees one origin, which is what production will be:

  ```ts
  // vite.config.ts, server section
  proxy: {
    '/api': 'http://localhost:8080',
    '/media': 'http://localhost:8080',
  }
  ```

- Send `Accept-Language: en` or `fr`. Read `Content-Language` if you need to know what was chosen.
- Every response matches `contract/openapi.yaml` exactly; the backend tests validate each one against it.
- Errors always look like `{ "error": { "code", "message", "requestId" } }`, plus `fields` on a 400.
- That holds for every error under `/api`, including the ones the application never sees: a path the server or the security firewall refuses (an encoded slash, a semicolon, a doubled slash, `..`) is 400 `validation_error` with no `fields`, a failure inside a filter is 500 `internal_error`, and any method the path does not have, `TRACE` included, is 405 `method_not_allowed` with `Allow`. There is no HTML error page and no `/error` route.
- A `POST` sent with no session, or before a token was read, is 403 `csrf_invalid` and sets no cookie. Only `GET /api/auth/csrf` starts a session.
- A sign-in can be answered 403 `csrf_invalid` when the same browser signed out while it was being checked. Treat it like every other `csrf_invalid`.
- A method other than `GET`, or a path that is not in the contract, answers 404 `not_found`.
- Start the frontend against it with `VITE_API_MODE=real VITE_SAMPLE_DATA=true npm run dev`.
- Sample pictures are served from `contract/fixtures/images/` in the `dev` profile, with `Cache-Control: public, max-age=300`. The display and thumbnail addresses return the same original file for now; resized copies come with the real storage service in a later milestone.
- With fixture data the picture tokens are derived from the file names, so they are the same on every machine. A real server will use random tokens.
- A picture is served only when its database row is published. Pictures on draft cases, and profile pictures without consent, answer 404 even when the file exists. A file reached through a symbolic link that leaves the picture folder is refused.

## How it is built

One Spring Boot application, split into modules by package. A module's `internal` package is private to it; `ArchitectureTest` fails the build if another module reaches in.

| Package | Responsibility |
|---|---|
| `shared` | Error envelope, paging, language negotiation, search normalization, deadlines, clock |
| `site` | `GET /api/site`, `GET /api/stats` |
| `cases` | Cases, the general fund, categories, updates, gallery; the five `/api/cases` endpoints |
| `ledger` | Totals, ledger entries, corrections, donor listings |
| `media` | Public picture addresses; development-only serving of sample pictures |
| `fixtures` | Development and test only: loads a shared fixture file |

Modules do not read each other's tables. The ledger publishes two database views, `case_balance` and `ledger_entry_detail`, and media publishes `public_media`; other modules read only those.

Key rules and where they live:

| Rule | Where |
|---|---|
| Every number in a response comes from one database snapshot | Read services are `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` |
| Public responses contain only public fields | Response records in each module, filled field by field; private beneficiary details are in a separate table no public query reads |
| Names of anonymous donors and pictures without consent never leave the database | The `select` lists in `LedgerRepository` and `CaseRepository` |
| Draft cases do not exist publicly | Every statement in `CaseRepository` excludes them |
| Exact funding-ratio order | `CaseRepository.RATIO`, with the proof in its comment |
| Search identical to the frontend's | `SearchText`, stored in `search_*` columns when a case is written |
| Deadlines survive a timezone change | The date, zone and cutoff are stored on the case |

### What the database enforces by itself

`src/main/resources/db/migration/`: `V1` creates the schema, `V2` adds the corrections from the milestone 0B review, `V3` and `V4` the identity and audit tables, `V5` the TRUNCATE rule. These hold whatever the application does:

- The ledger is append-only: no update, no delete.
- A correction points at one original of the right kind, on the same case, not earlier, and the corrections of one entry never total more than it.
- A transfer is two entries of opposite kinds with the same amount and instant on two cases, checked at commit. A transfer reversal undoes both sides of one transfer.
- No money moves on a draft case. A published case never becomes a draft again.
- A disbursement, a transfer out or a transfer reversal is refused when it would leave the case below zero. A refund or a donation reversal is always recorded, even when it creates a shortfall.
- The general fund never records a disbursement. Its money is transferred to a case, and delivered from that case.
- One movement is at most 10^10 cents; the sum of all movements at most 10^15. The sum is kept in one counter row that every ledger insert updates, so inserts take its lock one after the other and the bound holds when two cases are written at the same time. The cost is that ledger inserts are serialized platform-wide, which is acceptable at this size.
- Both languages are required for the title and story of a published case.
- Category identifiers never change. Adding a category is inserting a row.
- The audit log is append-only, accounts and the identity guard are never deleted, and a bootstrapped site always keeps a usable super administrator.
- `TRUNCATE` is refused on the ledger, the audit log, accounts, second factors and the guard, directly or through `CASCADE`, unless the database is disposable (marked when created, operator command never used). That is how the fixture loader and the tests reset; on a real database nothing is truncated. The application's role owns these tables, so this stops a statement, not an owner who drops the trigger first.

### What is deliberately not here yet

These need a signed-in person and arrive with the write endpoints:

- Who may confirm a donation, record a disbursement or make a transfer.
- Idempotency keys and provider references that stop the same correction being recorded twice from two requests. The cumulative limit above already stops over-correction.
- The audit log of financial actions. (The security audit log exists since milestone 1B.)
- A correction write API that tells "this record was wrong" (`donation_reversal`, no money moved) apart from "money really went back" (`refund`), and that never records a real new payment as a correction.
- Upload, storage, consent records and private file access. Only picture metadata exists now.
- Maintenance mode (503). Rate limits (429) exist on the identity operations since milestone 1B; the public read endpoints have none yet.
- Email: no message is ever sent. Addresses are unverified, which is why public sign-up is closed outside development, and why password resets go through the operator.
- Housekeeping runs every ten minutes in the web application: rate-limit windows and cooldown markers older than two hours (longer than any window or cooldown lasts) and pending sign-ins that ended over an hour ago are removed, a bounded number of rows per run. Expired sessions are removed by Spring Session's own task. One-time credentials are kept: a used or expired credential is only a hash with its dates, and how long to keep that record is the owner's decision.

Since milestone 1B a known path called with a method it does not support answers `405 method_not_allowed` with an `Allow` header (it was 404 before). Unknown paths still answer 404.

Concurrency, stated exactly:

- Tested: two simultaneous inserts on two different cases cannot together pass the platform bound (`LedgerRulesIT`).
- By construction, not by a concurrency test: two simultaneous corrections of one entry, and two simultaneous withdrawals from one case, wait for each other on a row lock. The single-connection tests cover the rules; a test with two competing connections for each belongs to the financial write milestone.
- Two transfers between the same two cases in opposite directions can deadlock. PostgreSQL detects it and aborts one; the write API will have to retry. Not tested yet.
- Under repeatable-read or serializable writers, a concurrent ledger insert fails with a serialization error instead of waiting. The write API will have to retry. Not tested yet.

Flyway 12.4 and PostgreSQL 18: the migrations run in every integration test, so the combination works here. I have not read a vendor statement that Flyway 12.4 supports PostgreSQL 18; that part stays unverified.

## Tests

| Kind | Command | Needs | What |
|---|---|---|---|
| Unit and architecture (`*Test`) | `./mvnw test` | Java only | Search normalization, deadlines and daylight saving, language negotiation, request validation, module boundaries |
| Integration (`*IT`) | `./mvnw verify` | Docker | The whole application against PostgreSQL 18 started by Testcontainers |

Integration tests never use an in-memory database. They cover:

- `FullDatasetIT`: all seven endpoints over the full dataset `contract/fixtures/dataset.json`, against `golden/acceptance.json`: 200 current cases on 17 pages, every sort, categories, both languages, search, reconciliation of every case and of the platform, privacy, deadlines, history pages, and every public picture.
- `FixtureSafetyIT`: the loader refuses a database holding real data, and never publishes a picture attached to a draft.
- `ExampleFixtureIT`: all seven endpoints over `contract/fixtures/example.json`, each response validated against `contract/openapi.yaml`, compared with `example.expected.json`, and searched for private markers. The examples written in the contract are reproduced.
- `LedgerRulesIT`: the database rules above, and that all numbers of one read come from one snapshot while another connection commits.
- `SortingIT`: every sort, tie-breakers, and two funding ratios that differ by 10^-20.
- `NegativeFixturesIT`: every broken fixture in `contract/fixtures/negative-cases.json` is refused and leaves the data untouched.
- `ProductionProfileIT`: without a development profile there is no fixture loader and no fictional data.
- `DevProfileHttpIT`: the application started in the `dev` profile on a real port, called over HTTP.
- `JointAcceptanceFindingsIT`, `DevProfileIdentityClockIT`: regression tests for the defects the first real-browser run found: any token of valid shape on every `POST` is 403 `csrf_invalid`; simultaneous first token reads after a session change give one token and no error; a real database error naming a session is logged without its identifier; in the `dev` profile identity runs on the real time while the public responses keep the fixture's clock.
- `IndependentReviewFindingsIT`, with `ApiErrorReportValveTest` and parts of `RealDatabaseIT`, `SensitiveLogFilterTest` and `ClientAddressTest`: regression tests for the defects the independent review found: a refused `POST` creates no session; a sign-in racing a sign-out is refused and not recorded as a success; `TRUNCATE` is refused on a real database; malformed paths, errors inside filters and `TRACE` are answered in the contract's shape; hexadecimal byte literals never reach the log.
- `SignOutDuringSessionChangeIT`: the defect of the independent re-check (R1). A sign-out is held after it has read its session while a password change, a second factor or an enrollment confirmation runs as a real request; after the sign-out answers 204 nobody is signed in.
- `ThirdRoundFindingsIT` and `SessionCookieResolverTest`: the defects of the third independent round. A sign-out with two values of the session cookie ends the caller's own session and never another one (R2); a session cookie that is not an identifier is treated as no cookie, never a 500 (R3).
- `identity.*IT`: the identity operations over real HTTP with one cookie jar per simulated browser, against PostgreSQL. Each test method is named after the scenario of `contract/tests/security-acceptance.md` it covers (`a6_7_...` is scenario 6.7). After every test, every identity response it received is validated against the contract's closed schemas and checked for `no-store`. `RealDatabaseIT` and `OperatorModeIT` run without a development profile on a database that was never marked disposable.

`docs/m1b-backend-acceptance-coverage.md` maps all 220 scenarios to these tests, and says which ones need a browser or are not automated.

The application never reads `example.expected.json`. It computes every total from ledger rows.

The application never reads `golden/acceptance.json` either.
