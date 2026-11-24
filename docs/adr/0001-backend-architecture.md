# ADR 0001: Backend architecture

- Status: proposed, awaiting review by the frontend agent and approval by the project owner
- Date: 2026-11-24
- Scope: decides the shape of the backend. No backend code exists yet.

## Context

APV-GAKASINAY is a small donation site run by a group of friends. It records money that moves outside the site, so its hardest requirement is financial correctness, not scale. Expected load is tens of visitors at a time and hundreds of cases. One person will maintain it. The approved React frontend sits at the repository root and talks to `/api` with cookie credentials.

## Decision

### Stack

| Choice | Version | Why |
|---|---|---|
| Java | 25 (long-term support release) | Newest long-term support release, supported by current Spring Boot. Java 21 is an acceptable fallback if the chosen host only offers it; nothing in the design needs a Java 25 feature. |
| Spring Boot | 4.1.x, exact patch pinned when milestone 0B starts | Current major line. Spring supports each minor for at least 12 months and each major for at least 3 years. See "Version verification" below. |
| PostgreSQL | 18, exact minor pinned when milestone 0B starts | Released September 2025 and supported for five years. Transactions, constraints, row locks and triggers are what the ledger relies on. |
| Build | Maven with the Maven Wrapper | The wrapper pins Maven itself, so every machine and CI builds the same way. |
| Migrations | Flyway, plain SQL files | Schema history is reviewable and repeatable. No schema is ever created by the application at start-up. |
| Data access | Spring JDBC (`JdbcClient`) with hand-written SQL | Financial totals, row locks and ratio sorting are clearer and safer as explicit SQL than through an object mapper. |
| Tests | JUnit, MockMvc, Testcontainers with real PostgreSQL | Constraints, locks and ordering must be tested on the real database, not on an in-memory substitute. |
| Local infrastructure | Docker Compose | One command starts PostgreSQL for development. |

Toolchain pinning: the Maven Wrapper, a Java version file read by CI, an exact Spring Boot version in the build file, and an exact PostgreSQL image tag in Compose and Testcontainers. Dependency updates arrive by pull request.

### Shape

A **modular monolith**: one deployable application, split into modules by package. A module exposes a small public interface and keeps its tables to itself. An architecture test fails the build when one module reaches into another's internals.

| Module | Responsibility |
|---|---|
| `site` | Site settings, platform totals |
| `cases` | Cases, the general fund, lifecycle, categories, featured rank, updates |
| `ledger` | Append-only money movements, balances, reconciliation |
| `donations` | Reports, confirmation, rejection, manual payments, corrections |
| `media` | One storage service for every kind of file, with a policy per kind |
| `identity` | Accounts, sessions, roles, permissions, profiles |
| `contact` | Contact messages |
| `audit` | Append-only record of administrative actions |
| `shared` | Error envelope, pagination, localization, money type |

Only `ledger` writes ledger rows. Every other module asks it to.

### Repository layout

```
/                 frontend (unchanged)
/backend          Spring Boot application
/contract         OpenAPI contract, financial terms, shared fixtures
/docs/adr         architecture decisions
/compose.yaml     local PostgreSQL
```

The frontend stays at the root so the approved build, CI and hosting metadata do not move.

### Deployment model

Frontend and API are served from **one origin**: a reverse proxy serves the static build and forwards `/api` to the application. This keeps session cookies first-party (`SameSite=Lax`), removes cross-origin configuration, and matches the frontend's `apiBaseUrl: '/api'`. The hosting provider is an open owner decision.

### Sessions and security foundation

- Server-side sessions stored in PostgreSQL, with an `HttpOnly`, `Secure`, `SameSite=Lax` cookie. Local development uses the Vite proxy, so the same cookie rules work over `localhost`.
- CSRF token required on every state-changing request.
- Deny by default: a route with no declared access rule is refused.
- These are built in milestone 1, before any privileged financial operation exists.

### Files

One storage interface with two parts: where bytes live, and who may read them. The first implementation writes to a persistent local directory. The interface does not expose file-system paths, so object storage can replace it later without changing callers.

Each kind of file has its own policy: profile pictures, public case covers, public case galleries, draft case pictures, private supporting documents, payment screenshots, disbursement evidence. Public pictures are served as re-encoded derivatives, never as the original upload. Private kinds are served only through authenticated routes. The service is built in milestone 2 and is not part of milestone 0.

### Localization

Localized text is stored per language in the database. The API picks the language from `Accept-Language` and falls back per field, as defined in the contract.

### Search

The normalized search text for each case is computed in application code with the exact algorithm in the contract and stored in a column. PostgreSQL's `unaccent` extension is not used, because it folds more characters than the frontend's algorithm and the mock and the real API must return the same results.

## What is deliberately not used

| Not used | Reason |
|---|---|
| Microservices, Kubernetes | One small application and one maintainer. |
| Kafka or another message broker | No asynchronous workflow needs one. Work that must happen together happens in one database transaction. |
| Redis | Sessions, rate-limit counters and idempotency keys fit in PostgreSQL at this size. |
| A separate search engine | Substring search over a few hundred cases is a PostgreSQL query. |
| An object mapper that generates the schema | The schema is financial record-keeping and is written by hand. |

Each can be reconsidered when a measured need appears.

## Consequences

- One process and one database are simple to run, back up and restore.
- Correctness rests on the database: constraints and transactions hold even if application code has a bug.
- Hand-written SQL means more code than an object mapper for simple forms, accepted in exchange for explicit money queries.
- Local file storage ties the first deployment to one server with a persistent disk and a backup of that disk.
- Horizontal scaling is not designed for. Nothing here prevents it later, but it is not a goal.

## Version verification

Checked on 2026-11-24 by web search, not against the vendors' own pages:

- Spring Boot 4.1.0 was reported as the latest stable release in mid-2026, and documentation for a 4.2 line exists. I could not confirm today's newest patch. The exact version is pinned from spring.io when milestone 0B starts.
- Spring Boot 4.x requires Java 17 or later.
- PostgreSQL 18 was released on 2025-09-25 with five years of support. I could not confirm whether PostgreSQL 19 has been released; 18 is chosen either way because it has a year of minor releases behind it.
- The development machine currently has Java 21 and Docker installed. Java 25 would need installing, or the build can run in a container.

## Alternatives considered

- **Node.js backend, sharing TypeScript with the frontend.** One language across the project, but the owner already works with Spring Boot and will maintain this.
- **JPA and Hibernate.** Faster for plain forms, weaker for explicit locking and aggregate queries, and it hides the SQL that matters most here.
- **A hosted backend service.** Fast to start, but the append-only ledger, row locking and database-level constraints are harder to guarantee, and it conflicts with the same-origin cookie model.
