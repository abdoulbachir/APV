# APV-GAKASINAY backend

The public read API of contract version 0.2.0 (`contract/openapi.yaml`): seven `GET` endpoints backed by PostgreSQL.

There is no sign-in, no donation reporting, no payment handling, no upload and no administration here. Those are later milestones.

## What you need

| Tool | Version | Used for |
|---|---|---|
| Java (JDK) | 21 | Building and running. `backend/.java-version` pins it for CI. |
| Docker with Compose | any recent | Local PostgreSQL, and the integration tests |
| Node | 24 | Only for the contract checks in `contract/` |

Maven is not needed: `./mvnw` downloads the pinned Maven 3.9.16.

## Commands

Run the `docker compose` commands from the repository root and the `./mvnw` commands from `backend/`.

| Task | Command |
|---|---|
| Prepare local settings (once) | `cp .env.example .env` |
| Start PostgreSQL | `docker compose up -d` |
| Start the backend with sample data | `set -a; . ../.env; set +a; ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` |
| Run unit tests (no database, no Docker) | `./mvnw test` |
| Run everything, including PostgreSQL integration tests | `./mvnw verify` |
| Run the contract checks | `cd ../contract && npm ci && npm run validate` |
| Look inside the database | `docker compose exec postgres psql -U apv apv` |
| Stop PostgreSQL, keep the data | `docker compose down` |
| Reset the development data | Restart the backend in the `dev` profile, or `docker compose down -v` to delete the database volume |

Database migrations run by themselves when the backend starts (Flyway). There is no separate migration command. The application never drops or wipes a schema.

### Sample data

> **The `dev` profile is destructive.** Every time the backend starts in the `dev` profile it **deletes everything in the database** it is connected to and loads a fictional dataset. Use it only with the local development database from `compose.yaml`.

By default it loads the full shared dataset, `contract/fixtures/dataset.json`: 200 current cases, two completed, two drafts, the general fund and 292 ledger entries, all fictional. Set `APV_FIXTURES_PATH` to load another file of the same format, for example `../contract/fixtures/example.json` for the small one. The application clock is pinned to the instant written in the loaded file (2026-11-24 12:00 UTC for both), so responses are repeatable.

Three things stand between the loader and a database that matters:

1. **It does not exist outside development.** The loader is only created in the `dev` and `test` profiles. Without them there is no code path that loads a fixture, and a profile named `prod` or `production` refuses to start together with `dev` or `test`.
2. **Local databases only.** It refuses a database that is not on this machine (`localhost`, `127.0.0.1`, `[::1]`). A container host name can be allowed explicitly with `apv.fixtures.allowed-hosts`.
3. **Never over real data.** The database records whether its content came from a fixture. The loader replaces an empty database or fixture data, and refuses when the database holds cases that did not come from a fixture.

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

`src/main/resources/db/migration/`: `V1` creates the schema, `V2` adds the corrections from the milestone 0B review. These hold whatever the application does:

- The ledger is append-only: no update, no delete.
- A correction points at one original of the right kind, on the same case, not earlier, and the corrections of one entry never total more than it.
- A transfer is two entries of opposite kinds with the same amount and instant on two cases, checked at commit. A transfer reversal undoes both sides of one transfer.
- No money moves on a draft case. A published case never becomes a draft again.
- A disbursement, a transfer out or a transfer reversal is refused when it would leave the case below zero. A refund or a donation reversal is always recorded, even when it creates a shortfall.
- The general fund never records a disbursement. Its money is transferred to a case, and delivered from that case.
- One movement is at most 10^10 cents; the sum of all movements at most 10^15. The sum is kept in one counter row that every ledger insert updates, so inserts take its lock one after the other and the bound holds when two cases are written at the same time. The cost is that ledger inserts are serialized platform-wide, which is acceptable at this size.
- Both languages are required for the title and story of a published case.
- Category identifiers never change. Adding a category is inserting a row.

### What is deliberately not here yet

These need a signed-in person and arrive with the write endpoints:

- Who may confirm a donation, record a disbursement or make a transfer.
- Idempotency keys and provider references that stop the same correction being recorded twice from two requests. The cumulative limit above already stops over-correction.
- The audit log.
- A correction write API that tells "this record was wrong" (`donation_reversal`, no money moved) apart from "money really went back" (`refund`), and that never records a real new payment as a correction.
- Upload, storage, consent records and private file access. Only picture metadata exists now.
- Rate limiting (429) and maintenance mode (503). The contract defines these responses; nothing produces them yet.
- Proper `405 Method Not Allowed` answers. For now any method other than `GET` answers 404 `not_found`; to revisit in the security milestone.

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

The application never reads `example.expected.json`. It computes every total from ledger rows.

The application never reads `golden/acceptance.json` either.
