# API contract

`openapi.yaml` in this folder is the single source of truth for the API between the frontend and the backend.

| File | Purpose |
|---|---|
| `openapi.yaml` | The contract. OpenAPI 3.1. |
| `financial-terms.md` | Exact meaning of every money field. |
| `fixtures/` | Format of the shared bilingual test dataset. |
| `M0A-review.md` | Open decisions and the acceptance checklist for this version. |

## Scope of version 0.1.0

Public read endpoints only:

- `GET /api/site`
- `GET /api/stats`
- `GET /api/cases`
- `GET /api/cases/{code}`
- `GET /api/cases/{code}/updates`
- `GET /api/cases/{code}/donors`
- `GET /api/cases/{code}/ledger`

Authentication, donation reporting, uploads, profiles and administration are added by later contract pull requests.

## How the contract changes

1. A change is a pull request from a `contract/<topic>` branch that touches only this folder.
2. Both agents review it. The project owner merges it.
3. Implementation pull requests come after, and name the contract change they implement.
4. `info.version` follows semantic versioning. A breaking change raises the minor version until 1.0.0, and must be called out in the pull request description.

Nobody implements an endpoint that is not in `openapi.yaml`.

## Relationship to existing files

| Existing file | What happens to it |
|---|---|
| `src/types.ts` | API shapes are generated from the contract into a new generated file. Hand-written frontend domain types may stay and are mapped from the generated ones. |
| `API-CHANGES.md` | Its one proposal (locale-aware content) is adopted here. It becomes a pointer to this folder. |
| `src/api/client.ts` | Stays the only place the frontend calls the API. Its real adapter follows the contract; its mock adapter is validated against it. |

These frontend changes belong to milestone 0B and to the frontend agent. This pull request does not touch them.

## Validation and drift detection

Planned checks, to be added to CI in milestone 0B. None is installed by this pull request.

| Check | What it catches | Owner |
|---|---|---|
| Lint the contract (Spectral or Redocly) | Invalid OpenAPI, missing descriptions, examples or error responses | Backend |
| Validate every example in the contract against its schema | Examples that drift from schemas | Backend |
| Generate TypeScript types (`openapi-typescript`) and fail if the committed file differs | Frontend types out of date | Frontend |
| Call every mock adapter method over the shared fixtures and validate each result against the contract schemas | Mock data that no real server would return | Frontend |
| Backend tests validate every real response against the contract schemas | Server responses that drift from the contract | Backend |
| Golden queries: a fixed list of requests whose expected results are committed, run against both the mock and the real API | Different search, sort, null handling or paging between mock and real | Both |
| Breaking-change diff against `main` (`oasdiff`) on contract pull requests | Unannounced breaking changes | Backend |
| Reconciliation test over the fixtures using the invariants in `financial-terms.md` | Totals that do not match the ledger | Both |

One risk to settle in milestone 0B: some Java tooling validates OpenAPI 3.0 better than 3.1. The contract avoids 3.1-only features except nullable types written as `type: [x, 'null']`. If the chosen backend validator cannot read 3.1, we either pick another validator or convert the file for that one check; the source stays 3.1.

## Checks run on this version

Run locally on the branch before review:

- The file parses as YAML.
- Every `$ref` resolves.
- Every response example validates against its schema.

No CI job enforces these yet.
