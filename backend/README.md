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

The `dev` profile replaces everything in the database with the fictional dataset in `contract/fixtures/example.json` at every start, and pins the application clock to the instant written in that file (2026-11-24 12:00 UTC), so responses are repeatable. Point `APV_FIXTURES_PATH` at another file of the same format to load it instead.

Without the `dev` profile nothing is loaded: the fixture importer does not exist in the application at all, and a profile named `prod` or `production` refuses to start together with `dev` or `test`. A fresh production database contains the six initial categories, the default timezone and nothing else.

### Try the endpoints

With the backend running in the `dev` profile:

```bash
curl -s localhost:8080/api/site
curl -s localhost:8080/api/stats
curl -s 'localhost:8080/api/cases?status=active&sort=closest&pageSize=12'
curl -s -H 'Accept-Language: fr' localhost:8080/api/cases/HELP-901
curl -s localhost:8080/api/cases/HELP-901/updates
curl -s localhost:8080/api/cases/HELP-901/donors
curl -s 'localhost:8080/api/cases/HELP-901/ledger?page=1&pageSize=5'
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
| `APV_FIXTURES_PATH` | `../contract/fixtures/example.json` | `dev` profile only |
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
- Sample pictures: the addresses in responses are real, but the picture files are not in the repository yet, so they answer 404 until the shared test images are added under `contract/fixtures/`.
- With `example.json` the picture tokens are derived from the file names, so they are the same on every machine.

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

`src/main/resources/db/migration/V1__public_read_model.sql`. These hold whatever the application does:

- The ledger is append-only: no update, no delete.
- A correction points at one original of the right kind, on the same case, not earlier, and the corrections of one entry never total more than it.
- A transfer is two entries of opposite kinds with the same amount and instant on two cases, checked at commit. A transfer reversal undoes both sides of one transfer.
- No money moves on a draft case. A published case never becomes a draft again.
- A disbursement, a transfer out or a transfer reversal is refused when it would leave the case below zero. A refund or a donation reversal is always recorded, even when it creates a shortfall.
- One movement is at most 10^10 cents; the sum of all movements at most 10^15.
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

## Tests

| Kind | Command | Needs | What |
|---|---|---|---|
| Unit and architecture (`*Test`) | `./mvnw test` | Java only | Search normalization, deadlines and daylight saving, language negotiation, request validation, module boundaries |
| Integration (`*IT`) | `./mvnw verify` | Docker | The whole application against PostgreSQL 18 started by Testcontainers |

Integration tests never use an in-memory database. They cover:

- `ExampleFixtureIT`: all seven endpoints over `contract/fixtures/example.json`, each response validated against `contract/openapi.yaml`, compared with `example.expected.json`, and searched for private markers. The examples written in the contract are reproduced.
- `LedgerRulesIT`: the database rules above, and that all numbers of one read come from one snapshot while another connection commits.
- `SortingIT`: every sort, tie-breakers, and two funding ratios that differ by 10^-20.
- `NegativeFixturesIT`: every broken fixture in `contract/fixtures/negative-cases.json` is refused and leaves the data untouched.
- `ProductionProfileIT`: without a development profile there is no fixture loader and no fictional data.
- `DevProfileHttpIT`: the application started in the `dev` profile on a real port, called over HTTP.

The application never reads `example.expected.json`. It computes every total from ledger rows.

Still to add when the frontend agent delivers them: the full dataset (`contract/fixtures/dataset.json`), the shared test pictures and the golden request and response files.
