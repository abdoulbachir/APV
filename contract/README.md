# API contract

`openapi.yaml` in this folder is the single source of truth for the API between the frontend and the backend.

| File | Purpose |
|---|---|
| `openapi.yaml` | The contract. OpenAPI 3.1. |
| `financial-terms.md` | Exact meaning of every money field, and the ledger rules. |
| `media-and-privacy.md` | Picture addresses, caching, consent withdrawal, public-only responses. |
| `identity-and-security.md` | Roles and permissions, accounts, passwords, sessions, second factor, CSRF, rate limits, audit. |
| `donation-lifecycle.md` | Reporting a gift, private evidence, review, manual entry, corrections, payment methods, the general fund on a real database. |
| `fixtures/` | The shared bilingual test dataset: format, example, expected values, public projection. |
| `tools/`, `tests/` | The validation scripts and the deliberately wrong inputs they must reject. |
| `tests/security-acceptance.md` | The security tests milestone 1B must pass. |
| `tests/donation-acceptance.md` | The donation lifecycle tests milestone 2B must pass. |
| `M2A-review.md` | Review status and the owner decisions for the donation lifecycle (version 0.4.0). |
| `tests/negative-contracts.json` | Deliberately damaged copies of the contract that the checker must reject. |
| `M1A-review.md` | Review status and decisions for the identity contract (version 0.3.0). |
| `M0A-review.md` | Review status, decisions and the acceptance checklist for this version. |

## Validate everything

One command reproduces every check, locally and in CI:

```bash
cd contract
npm ci
npm run validate
```

It needs Node 24, the version pinned in `.nvmrc` at the repository root. The four tools it uses are pinned to exact versions in `package.json` and locked in `package-lock.json`. They live in this folder only; the frontend's `package.json` is not touched.

CI runs the same command in `.github/workflows/contract.yml` on every pull request and push to `main` that changes this folder.

What it checks:

| Area | Check |
|---|---|
| OpenAPI | Lint with Redocly, recommended rules |
| OpenAPI | Every `$ref` resolves |
| OpenAPI | Every example validates against its schema |
| Privacy | Every object schema is closed; no property name suggests private data; picture addresses use `MediaPath`; `draft` is not a public status; categories are not a fixed list |
| Privacy | 22 deliberately wrong responses are rejected (`tests/negative-responses.json`) |
| Fixtures | `example.json` validates against `fixture.schema.json` |
| Fixtures | Cross-record integrity: unique identifiers, category references, correction references and kinds, cumulative refund limits, transfer pairing, no money on drafts, no discretionary overspending, deadlines and timezones |
| Fixtures | Financial reconciliation, using the invariants in `financial-terms.md` |
| Fixtures | `example.expected.json` equals what the rules compute |
| Fixtures | `example.public.json` is up to date, valid, contains no private marker, and keeps every total |
| Both | Every response the rules produce from the fixture is valid against `openapi.yaml`, in both languages, with no private marker |
| Both | The examples written in `openapi.yaml` are exactly what the fixture produces |
| Fixtures | 53 deliberately broken fixtures are each rejected for the stated reason (`fixtures/negative-cases.json`, `fixtures/negative-public-cases.json`) |

`tools/lib.mjs` contains a small reference implementation of the read rules (totals, corrections, donor list, search, sorting, paging). It exists so examples and expected values are computed, not checked by eye. It is not production code, and the frontend mock and the backend are free to implement the rules their own way; the fixtures and golden results are what binds them.

## Scope of version 0.4.0

Version 0.4.1 changes one thing in it: the private `note` of a reversal and of a refund is required in the request body, as `donation-lifecycle.md` 8.1 always said (owner decision of 2026-11-28). Nothing else differs from 0.4.0.

Version 0.4.2 adds one optional property: `providerReference` on a manual entry (`DonationEntryRequest`), with the type and limits it has on a confirmation, as `donation-lifecycle.md` section 7 always said (owner decision of 2026-11-28).

Public read endpoints, unchanged since 0.2.0:

- `GET /api/site`
- `GET /api/stats`
- `GET /api/cases`
- `GET /api/cases/{code}`
- `GET /api/cases/{code}/updates`
- `GET /api/cases/{code}/donors`
- `GET /api/cases/{code}/ledger`

Identity endpoints, added in 0.3.0 and not implemented yet: `GET /api/auth/csrf`, `GET /api/auth/options`, `POST /api/auth/register`, `/login`, `/logout`, `/change-password`, `/set-password`, four under `/api/auth/mfa/`, and `GET /api/me`. Their rules are in `identity-and-security.md`.

Every schema declares an `x-audience` (`public`, `shared`, `account`, `request` or `credential`), and `npm run validate` fails if private data could reach a public response.

Donation lifecycle endpoints, added in 0.4.0 and not implemented yet: `GET /api/giving/methods`, `POST /api/donations`, `/donations/evidence`, `/donations/status`, and ten operations under `/api/admin/donations` (list, enter, detail, evidence read and delete, confirm, reject, reopen, reversals, refunds, listing). Their rules are in `donation-lifecycle.md`.

Profiles, category management and the contact form are added by later contract pull requests.

## Categories are data

The contract does not list the categories. `GET /api/site` does, with a stable identifier, an English and a French label, a display order and an active flag for each. A super administrator will be able to add, relabel, reorder, deactivate and reactivate categories with no code change, migration or redeployment. Those write endpoints belong to a later milestone.

- The six initial identifiers are `education`, `medical`, `housing`, `family`, `emergency` and `other`. The frontend keeps its approved look for these and uses a generic look for any other identifier.
- An identifier never changes and is never reused. A category used by a case is never deleted, only deactivated.
- A deactivated category still labels its cases and still works as a filter value. The frontend leaves it out of the filter list, and administrators cannot choose it for a case. An existing case keeps it until an administrator changes it.
- The fixtures contain a seventh category (`food`), a deactivated one still used by a public case (`transport`), and a deactivated one used only by a draft (`retired`), which must not be listed.

## How the contract changes

1. A change is a pull request from a `contract/<topic>` branch that touches only this folder and its workflow.
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

## Checks that come with implementation

Not part of this pull request, because they need code that does not exist yet.

| Check | What it catches | Owner |
|---|---|---|
| Generate TypeScript types (`openapi-typescript`) and fail if the committed file differs | Frontend types out of date | Frontend |
| Call every mock adapter method over the public projection and validate each result against the contract | Mock data that no real server would return | Frontend |
| Backend tests validate every real response against the contract | Server responses that drift from the contract | Backend |
| Golden queries: a fixed list of requests whose expected results are committed, run against both the mock and the real API | Different search, sort, null handling or paging between mock and real | Both |
| Breaking-change diff against `main` (`oasdiff`) on contract pull requests | Unannounced breaking changes | Backend |

One risk to settle in milestone 0B: some Java tooling validates OpenAPI 3.0 better than 3.1. The contract avoids 3.1-only features except nullable types written as `type: [x, 'null']`. If the chosen backend validator cannot read 3.1, we either pick another validator or convert the file for that one check; the source stays 3.1.

## Generated fixture lifecycle

The large full dataset, public projection and frontend copy are ignored build inputs, not tracked source. Run `npm run generate:dataset` here (or `npm run fixtures:generate` at the repository root). The generator uses only Node's standard library; schema validation still requires this folder's `npm ci`. Dataset and parity scripts regenerate their inputs automatically. Keep the small example, schemas, negative inputs, golden expectations and deterministic generator under version control. Never copy private fixtures into the frontend or production artifacts.
