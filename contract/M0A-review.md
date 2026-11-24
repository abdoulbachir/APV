# Milestone 0A review

Contract-only pull request for the public read API. Nothing here is implemented.

Baseline: `main` at `aa82205f872b52d79f903171f1663b6e034aeee8`.

## What this pull request adds

| File | Content |
|---|---|
| `contract/openapi.yaml` | OpenAPI 3.1 contract for seven public read endpoints |
| `contract/financial-terms.md` | Exact definition of every money field |
| `contract/fixtures/` | Shared fixture format, a small example, and its expected results |
| `contract/README.md` | Change process, validation and drift detection |
| `docs/adr/0001-backend-architecture.md` | Backend architecture decision record |
| `contract/M0A-review.md` | This file |

No frontend file, CI workflow or existing document is changed.

## How the frontend agreement was applied

| Agreed point | Where |
|---|---|
| Nested case detail | `CaseDetail` |
| One paginated envelope, no dual shape | `PageMeta` and the four `*Page` schemas |
| Fund excluded from the directory, read by code | `GET /cases`, `GET /cases/FUND` |
| Explicit category and featured rank | `Case.category`, `Case.featuredRank`, `sort=featured` |
| Case-insensitive code lookup | `CaseCode` parameter |
| Integer cents within JavaScript safe range | `Cents`, `PositiveCents`, `SignedCents` |
| Machine-readable error codes | `Error`, `ErrorCode` |
| Public and private schemas separated | Only public schemas exist in this file; `PublicCaseStatus` has no `draft` |
| `closest`: below goal first by ratio descending, then funded, then no goal | `GET /cases` description |
| Deterministic ordering and null handling for every sort | `GET /cases` description |
| Paginated public ledger, donors and updates from the start | Three `Case history` endpoints; detail carries previews and counts |
| Date-only deadlines, explicit configurable timezone | `Case.deadline`, `Case.deadlineEndsAt`, `Site.timezone` |
| Localization fallback | `AcceptLanguage` parameter, `language` properties |
| Identical mock and real search | `GET /cases` description, ADR section "Search" |
| External donations, transfers, net allocated, net delivered, available | `CaseFinancials`, `financial-terms.md` |
| Transfers never inflate platform totals | `Stats`, `financial-terms.md` section 3 |
| Corrections, refunds, paired transfer reversals visible in the public record | `LedgerKind`, `LedgerEntry.reverses`, `LedgerEntry.reversedBy` |
| Cover and gallery metadata | `Case.photoUrl`, `photoThumbnailUrl`, `photoAlt`, `CaseDetail.photos` |
| Avatar only with consent, never for anonymous donors | `PublicDonor.avatarUrl`, `CaseUpdate.authorAvatarUrl` |
| One shared bilingual fixture dataset | `contract/fixtures/` |

## Points that need the frontend agent's explicit confirmation

These go beyond what was agreed, or change approved frontend code.

1. **New endpoint `GET /site`.** Publishes the organization timezone, categories, locales and paging limits. It is the "explicit and configurable" timezone. Alternative: a build-time frontend setting.
2. **`deadline` becomes date-only.** `formatDate` currently uses `new Date(iso)`, which shows a date-only value one day early west of UTC. The frontend needs a date-only formatter. `deadlineEndsAt` is provided so "days left" needs no timezone arithmetic.
3. **Search no longer matches category labels.** The prototype includes the localized category label in the searched text. The backend does not hold those labels, so the contract excludes them. The mock must follow.
4. **Search matches each field separately**, not one joined string, so a query cannot match across two fields.
5. **Among funded cases, `closest` orders by ratio ascending** (just reached the goal first). The agreement fixed the groups, not the order inside the funded group.
6. **Ratios are compared by integer cross-multiplication**, so the mock and PostgreSQL cannot disagree on ties.
7. **`totalPages` is at least 1**, matching the prototype's `Math.max(1, ...)`.
8. **Preview size is 10** for updates, donors and ledger in the detail response.
9. **`LedgerEntry` gains** `id`, `allocatedDelta`, `deliveredDelta`, `reverses`, `reversedBy`, and five new kinds. Existing `kind`, `amount`, `date`, `label` are unchanged. The frontend must tolerate unknown kinds.
10. **`PublicDonor` gains `id` and `avatarUrl`; `CaseUpdate` gains `language` and `authorAvatarUrl`; `Case` gains `category`, `featuredRank`, `language`, `photoThumbnailUrl`, `photoAlt`, `deadlineEndsAt`.** All additive.
11. **`Case.received` can be negative** in a recorded shortfall. Progress bars should clamp at zero.
12. **`Stats` gains `available` and `asOf`.**
13. **`story` stays in list items.** The cards use it today. A shorter `summary` can replace it later if payload size matters.

## Owner decisions

### Needed before this contract can be merged

| # | Decision | Assumed in this contract | Effect if the owner decides otherwise |
|---|---|---|---|
| 6 | Which statuses are "active" and "completed" | active = active + funded; completed = delivered + closed | One sentence in `GET /cases` changes |
| 15 | The six categories are final | education, medical, housing, family, emergency, other | The `Category` enum changes |

### Needed before milestone 0B, not for this contract

| # | Decision | Recommendation |
|---|---|---|
| 1 | Backend stack | Spring Boot and PostgreSQL |
| 2 | One repository | Yes, `backend/` beside the frontend |
| 3 | Same-origin hosting and provider | Same origin; provider open |
| 16 | Organization timezone | Configurable, default `America/New_York` |

### Do not block milestone 0

| # | Decision | Contract already tolerates either answer because |
|---|---|---|
| 8 | Both languages required to publish | Per-field fallback is defined either way |
| 11 | Fund transfers only, no direct disbursement | The fund's `delivered` is simply zero or not |
| 9 | Unreported payments, corrected amounts, reversals | The read model already shows corrections |
| 18 | Avatar display consent | `avatarUrl` is nullable |
| 19, 20 | Public gallery versus private evidence, image consent | Only public gallery metadata is in this contract |
| 4, 5, 7, 10, 12, 13, 14, 17, 21 | Email, password recovery, lifecycle automation, guest tracking, gifts to funded cases, assistance requests, beneficiary records, screenshot retention, profile fields | None affects a public read endpoint |

Screenshot retention stays undecided. No retention period is assumed anywhere in this pull request.

## Notes for later contract pull requests

Recorded so they are not lost; not part of this version.

- **Avatar consent, simplest workable model:** one account setting, "show my picture beside my named donations", off by default. It is checked when a donor list is read, so turning it off removes the picture from past donations immediately, and turning it on shows it on past named donations. An equivalent setting covers administrators' pictures on updates.
- **Image consent for beneficiaries:** a consent record per picture with date, scope, the administrator who attested it, and whether a guardian consented. A tick box records the administrator's attestation; it is not proof of consent. Withdrawal removes public access at once and replaces the public file name so cached copies stop resolving.
- **Guest donations:** never linked to an account because an email matches. A guest tracking link with a long random token that reveals only the status is the proposed option, pending owner decision 10.
- **Idempotency:** keys are scoped to the caller and the operation. The same key with a different payload is refused with a conflict.
- **Mock data in production:** a production build with `apiMode` set to `mock` must fail to build or refuse to start.

## Checks run on this branch

| Check | Result |
|---|---|
| `openapi.yaml` parses as YAML | Pass |
| Every `$ref` resolves | Pass, 37 references |
| Every example validates against its schema (Ajv, JSON Schema 2020-12) | Pass, 36 examples |
| Lint with Redocly CLI 1.x, recommended rules | Pass, no errors or warnings |
| `fixtures/example.json` validates against `fixture.schema.json` (Ajv strict mode) | Pass |
| `fixtures/example.expected.json` recomputed from `example.json` | Pass, including `sum(Case.received) == Stats.received` |
| `npm run build` on this branch | Pass, run on Node 18.19.1, not the pinned Node 24 |

These were run by hand on the branch with throwaway scripts. They are not yet part of CI; adding them is a milestone 0B task.

## Acceptance checklist

Frontend agent:

- [ ] The three approved pages can be built from these responses with no visual change.
- [ ] Each of the 13 points above is accepted or countered.
- [ ] The fixture format can be produced from the existing generator.
- [ ] The search and sort rules can be implemented identically in the mock.

Backend agent:

- [ ] Every rule in the contract can be enforced in PostgreSQL and tested.
- [ ] Every money field has one definition in `financial-terms.md`.

Project owner:

- [ ] Decisions 6 and 15 confirmed.
- [ ] Architecture record accepted, or changes requested.
- [ ] Authorization to merge and to start milestone 0B.
