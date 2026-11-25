# ADR 0001: Backend architecture

- Status: approved by the project owner and the frontend agent. Implemented for the public read API in milestone 0B. The hosting provider and domain are deferred.
- Date: 2026-11-24
- Scope: decides the shape of the backend. Updated 2026-11-24 for milestone 0B with the approved decisions and the exact versions in use.

## Context

APV-GAKASINAY is a small donation site run by a group of friends. It records money that moves outside the site, so its hardest requirement is financial correctness, not scale. Expected load is tens of visitors at a time and hundreds of cases. One person will maintain it. The approved React frontend sits at the repository root and talks to `/api` with cookie credentials.

## Decision

### Stack

| Choice | Version | Why |
|---|---|---|
| Java | 21 (long-term support) | Approved by the owner. Nothing in the project needs a newer release. Spring Boot 4.1 requires Java 17 or later and supports up to Java 27. |
| Spring Boot | 4.1.3 | Stable 4.1 line, open-source support until 2027-07-31. Chosen over 4.2.0, released days before this work, and over 4.0.x, whose open-source support ends 2026-12-31. See "Versions in use". |
| PostgreSQL | 18.7 | Major version 18 is supported until 2030-11-14 and has a year of minor releases behind it. Chosen over 19.0, released 2026-11-19. Transactions, constraints, row locks and triggers are what the ledger relies on. |
| Build | Maven 3.9.16 through the Maven Wrapper | The wrapper pins Maven itself, so every machine and CI builds the same way. |
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
| `cases` | Cases, the general fund, lifecycle, categories (rows in a table, managed by the super administrator, never an enumeration in code), featured rank, updates |
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

Approved by the owner: frontend, API and public pictures are served from **one origin**.

| Path | Served by |
|---|---|
| `/` | The static frontend build |
| `/api/*` | The backend application |
| `/media/public/*` | Approved public pictures, through the backend's access rules |

This keeps session cookies first-party (`SameSite=Lax`), removes cross-origin configuration, and matches the frontend's `apiBaseUrl: '/api'`.

The hosting provider and the domain are undecided. The owner is considering a personal server. Nothing is bought or deployed in milestone 0, and local development does not depend on either. To stay portable between a personal server and a rented virtual server, the deployment is planned as:

- Containers: one image for the application, the official PostgreSQL image, and a reverse proxy. The same Compose file shape runs on either kind of host.
- HTTPS ends at the reverse proxy, which also serves the static frontend.
- Persistent volumes for the database and for uploaded files.
- The database listens only on the internal container network, never on a public address.
- Scheduled backups of the database and of the files volume, copied off the machine, with a restore procedure that is rehearsed before launch.

A personal server adds two things to settle before launch: a stable public address (or a tunnel), and who restarts it when the owner is away.

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

### Time

Every event is stored as a UTC instant and returned as an RFC 3339 UTC timestamp at whole-second precision. The frontend shows it in the visitor's timezone. Financial and audit records keep UTC.

Deadlines are business dates. The organization timezone is a setting, default `America/Chicago`. When a deadline is set, the case stores the date, the zone and the cutoff instant computed with that zone's daylight saving rules. Changing the setting later affects new deadlines only; see `contract/financial-terms.md` section 10.

### Exact arithmetic

Money is `bigint` cents in tables. Totals are summed as `numeric`. Funding ratios are compared by cross-multiplication in `numeric`, because the product of a total and a goal can exceed 64 bits. No floating point touches money or ordering. Bounds are in `contract/financial-terms.md` section 5.

### Consistent reads

Each public read runs in one read-only transaction at repeatable-read isolation, so every number in a response comes from the same snapshot.

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

## Versions in use

Verified on 2026-11-24 against the vendors' own published data, then pinned in `backend/pom.xml`, `backend/.mvn/wrapper/maven-wrapper.properties`, `backend/.java-version` and `compose.yaml`.

| Component | Version | Pinned by | Source checked |
|---|---|---|---|
| Java | 21 | `backend/.java-version`, `java.version` in the build | Spring Boot 4.1 system requirements: https://docs.spring.io/spring-boot/4.1/system-requirements.html |
| Spring Boot | 4.1.3 | Parent of `backend/pom.xml` | Spring's project data: https://api.spring.io/projects/spring-boot/releases and `/generations` (support dates) |
| Spring Framework | 7.0.11 | Managed by Spring Boot 4.1.3 | Spring Boot 4.1.3 dependency list on Maven Central |
| PostgreSQL | 18.7 | `compose.yaml`, `apv.postgres.image` in the build | PostgreSQL's version data: https://www.postgresql.org/versions.json |
| PostgreSQL JDBC driver | 42.7.14 | Managed by Spring Boot 4.1.3 | Same dependency list |
| Flyway | 12.4.0 | Managed by Spring Boot 4.1.3 | Same dependency list |
| Testcontainers | 2.0.5 | Managed by Spring Boot 4.1.3 | Same dependency list |
| JUnit | 6.0.3 | Managed by Spring Boot 4.1.3 | Same dependency list |
| Maven | 3.9.16 | Maven Wrapper | Maven release history: https://maven.apache.org/docs/history.html |
| ArchUnit | 1.5.1 | `backend/pom.xml`, test only | Maven Central |
| JSON Schema validator (networknt) | 1.5.9 | `backend/pom.xml`, test only | Maven Central |

Rules followed:

- Libraries that Spring Boot manages are left at the version it manages. That combination is the one Spring tests together, which is the compatibility evidence.
- Where a newer line existed (Spring Boot 4.2.0, PostgreSQL 19.0, Maven 3.10.x), the mature supported line was preferred.
- The two libraries Spring Boot does not manage are test-only and pinned to exact versions.

Not verified by running: Flyway 12.4.0 and the JDBC driver against PostgreSQL 18 are exercised by the integration tests in CI, not by a vendor compatibility statement I read.

Next review: before 2027-07-31, when open-source support for Spring Boot 4.1 ends.

## Alternatives considered

- **Java 25.** Newer long-term support release. Not installed: the owner approved Java 21 and nothing here needs more.
- **Node.js backend, sharing TypeScript with the frontend.** One language across the project, but the owner already works with Spring Boot and will maintain this.
- **JPA and Hibernate.** Faster for plain forms, weaker for explicit locking and aggregate queries, and it hides the SQL that matters most here.
- **A hosted backend service.** Fast to start, but the append-only ledger, row locking and database-level constraints are harder to guarantee, and it conflicts with the same-origin cookie model.
