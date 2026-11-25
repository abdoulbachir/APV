# Milestone 0A review

Contract-only pull request for the public read API (PR #3, draft). Nothing here is implemented.

- Baseline: `main` at `aa82205f872b52d79f903171f1663b6e034aeee8`.
- First version reviewed by the frontend agent: `888d46c2f513c5a1746d74a8491726996375f89f` (contract 0.1.0).
- This revision: contract 0.2.0. It answers the frontend review and the project owner's feedback of 2026-11-24.

## 1. What this pull request contains

| File | Content |
|---|---|
| `contract/openapi.yaml` | OpenAPI 3.1 contract for seven public read endpoints |
| `contract/financial-terms.md` | Every money field, the correction rules, bounds, shortfalls, lifecycle, deadlines, general fund |
| `contract/media-and-privacy.md` | Picture addresses, caching, consent withdrawal, public-only responses |
| `contract/fixtures/` | Fixture formats, example, expected values, public projection, broken fixtures |
| `contract/tools/`, `contract/tests/` | Validation scripts and wrong responses that must be rejected |
| `contract/package.json`, `package-lock.json`, `redocly.yaml` | Pinned validation tools, isolated in this folder |
| `contract/README.md` | Change process, the one validation command, category rules |
| `.github/workflows/contract.yml` | Dedicated contract validation workflow |
| `docs/adr/0001-backend-architecture.md` | Backend architecture decision record |
| `contract/M0A-review.md` | This file |

No frontend file, no existing workflow and no existing document is changed. There is no backend code, migration, authentication or upload code.

## 2. The 13 frontend review points

| # | Point | Frontend response | State in this revision |
|---|---|---|---|
| 1 | `GET /api/site` | Accepted | Kept. Now also carries the category list. |
| 2 | Date-only deadlines and `deadlineEndsAt` | Accepted | Kept. `deadlineTimezone` added, see 4.6. |
| 3 | Category labels removed from text search | Accepted | Kept. |
| 4 | Each field searched separately | Accepted | Kept. |
| 5 | Funded cases by ascending ratio inside their group | Accepted | Kept. |
| 6 | Exact ratio comparison, BigInt in the frontend, no overflow in the backend | Accepted | Contract now names `BigInt` and PostgreSQL `numeric` and forbids 64-bit multiplication. |
| 7 | `totalPages >= 1` | Accepted | Kept. |
| 8 | 10-entry previews | Accepted | Kept. |
| 9 | Extended ledger kinds and signed effects | Accepted, subject to correction references | Done, see 4.1. |
| 10 | Additive public fields | Accepted, subject to localization, dynamic categories and privacy | Done, see 3.2, 4.3 and 4.4. |
| 11 | Negative allocated balances in a shortfall | Accepted | Kept. Rules written in `financial-terms.md` section 6. |
| 12 | `Stats.available` and `Stats.asOf` | Accepted | Kept. |
| 13 | `story` stays in list items | Accepted | Kept. |

## 3. Project owner decisions

### 3.1 Confirmed

| Decision | Applied in |
|---|---|
| Spring Boot and PostgreSQL | Architecture record, status line and stack table |
| One repository: `backend/`, frontend at the root, `contract/`, `docs/adr/` | Architecture record, "Repository layout" |
| Six initial categories, and more can be added later without code, migration or redeployment | See 3.2 |
| Event timestamps shown in each visitor's own timezone; organization timezone for deadlines, default `America/Chicago` | See 4.6 |

### 3.2 Dynamic categories

| Requirement | Where |
|---|---|
| Categories are records, not a fixed list | `Category` and `CategoryId` schemas. `CategoryId` is a pattern, not an enumeration; a check fails if it becomes one. |
| Stable, immutable identifier | `CategoryId`. Never changed, never reused. |
| English and French labels | `Category.labels`, both always present |
| Display order | `Category.position` |
| Active or inactive | `Category.active` |
| Six initial categories seeded | Identifiers `education`, `medical`, `housing`, `family`, `emergency`, `other` |
| Enough metadata to render labels and filters with no hardcoded translation | `GET /site` returns every listed category with both labels |
| Categories in use are never deleted | `contract/README.md`, "Categories are data" |
| Deactivated categories stay resolvable | `GET /site` lists an inactive category while a public case uses it. `Case.category` can name it. |
| Behavior in public filters | An inactive category is a valid `category` filter value, so old links work. The frontend does not offer it in its list. An unknown identifier returns 400. |
| Behavior in case forms | Administrators can choose active categories only. An existing case keeps an inactive category until it is changed. |
| A seventh category in the fixtures | `food`, used by `HELP-904`. Also `transport` (inactive, in use) and `retired` (inactive, used only by a draft, so not listed). |
| Generic look for new categories | Stated in the `Category` schema; the frontend keys its six approved looks on the six initial identifiers. |

Create, relabel, reorder, deactivate and reactivate are super administrator write endpoints in a later milestone. Not implemented here.

### 3.3 Still pending

| Decision | Working proposal used by this contract | If the owner decides otherwise |
|---|---|---|
| Status grouping | Current is `active` and `funded`; completed is `delivered` and `closed` | Two sentences in `GET /cases` change |
| General fund pays only by transfer to a case | Yes. The fund never records a disbursement. | No field changes; the fund's `delivered` becomes non-zero |
| Same-origin deployment | Yes: `/`, `/api/*`, `/media/public/*` on one origin | Cookie and cross-origin rules in the architecture record change |
| Hosting provider and domain | None chosen. Deployment planned to run on a personal server or a rented one. | Nothing in this contract |
| Both languages required to publish a case | Yes for title and story | No field changes: `titleLanguage` and `storyLanguage` already report a fallback |
| Whether a funded case keeps accepting gifts | Yes until paused or past its deadline | No field changes: `acceptingDonations` carries the answer |

None of these is assumed approved. Each is marked as a proposal where it appears.

Decided in later milestones and untouched here: screenshot retention, guest donation tracking, password recovery, email, assistance requests, beneficiary records, profile fields.

## 4. Requested corrections

### 4.1 Financial correction references

| Requested | Resolution |
|---|---|
| Replace the single `reversedBy` | `LedgerEntry.correctedBy` is an array, oldest first. `reverses` is renamed `corrects` to match. `correctedAmount` and `netAmount` are added. |
| Partial versus full | Any amount from one cent to what is left. Full when `correctedAmount == amount`. |
| Multiple partial refunds | Allowed, any number. |
| Maximum cumulative amount | The original's amount. |
| Valid correction kinds per original | Table in `financial-terms.md` section 1; enforced on fixtures. |
| Atomic transfer reversals | Two entries, one transaction, same amount and date, correcting both sides of one transfer. |
| Can a correction be corrected | No. A mistaken correction is repaired by a new original movement. |
| Net donor amounts | `PublicDonor.amount` is the donation's net amount; the listing disappears at zero. |
| Duplicate protection | Limit checked under a lock on the original, idempotency keys, provider reference unique per donation. |
| Same semantics everywhere | The examples in `openapi.yaml` are computed from the fixture by the checks. |
| Fixture with multiple partial refunds | `L08` and `L09` against `L03`, expected values in `example.expected.json`. |
| Append-only history | Unchanged. |

### 4.2 Financial consistency

| Requested | Resolution |
|---|---|
| Keep the six distinct quantities | Unchanged in `CaseFinancials`. |
| Transfers never inflate platform totals | Unchanged; invariant 7 is checked. |
| `peopleHelped` counts cases | Stated in the schema and in section 4. The name is kept for the approved frontend. |
| Bounds for sums | One movement at most 10^10 cents; the platform's gross movement total at most 10^15; therefore every total at most 10^15. Section 5. |
| Shortfalls visible, discretionary spending blocked | Section 6; enforced on fixtures (`overspend`). |
| Real events never hidden or refused | Section 6. |
| Consistent transactional reads | One snapshot per response; `asOf` added to `CaseDetail`. |
| Offset pagination under concurrent events | Described in `GET /cases/{code}/ledger`, with a detection procedure. |
| Movements on draft or non-public cases | No money moves on a draft; a published case never returns to draft; withdrawal redacts content and keeps the numbers. Section 7. |

### 4.3 Localization

| Requested | Resolution |
|---|---|
| One `language` for two fields was ambiguous | Removed. Each localized field has its own companion: `titleLanguage`, `storyLanguage`, `photoAltLanguage`, `textLanguage`, `altLanguage`. |
| Prefer both languages for published titles and stories | Adopted as the working rule, pending owner approval. Fixture format requires both. |
| Language metadata where fallback happens | The companion properties. Update text and picture descriptions can fall back. |
| Names not translated | Stated in `AcceptLanguage`. |
| Identical normalization, trimming, ordering and tie-breakers | Lower-casing method, exact trim character set, ASCII codes and identifiers compared without locale, whole-second timestamps. All in `openapi.yaml`. |
| The 80-character limit | Raw value at most 200 code points, trimmed value at most 80, counted in Unicode code points, checked before normalization, rejected with `too_long`, never cut. |
| Category labels in both languages | `Category.labels`. |

### 4.4 Public media and privacy

| Requested | Resolution |
|---|---|
| Same-origin picture paths only | `MediaPath` pattern on every picture property; a check fails if one does not use it. |
| Draft, revoked and private pictures unreachable publicly | `media-and-privacy.md` section 2. |
| No claim that a new address revokes copies | Removed from this file; section 4 of `media-and-privacy.md` states what withdrawal can and cannot do. |
| Cache-control and withdrawal behavior | Sections 3 and 4. |
| Public schemas exclude private fields | All 20 object schemas closed; forbidden property names checked; server-side allowlist mapping stated. |
| Frontend consumes a sanitized projection | `example.public.json`, generated and checked. |
| Profile pictures, avatar consent, cover, gallery, bilingual descriptions, beneficiary consent, private evidence | Preserved in section 7. `CaseUpdate.photoAlt` added so update pictures have a description too. |

### 4.5 Contract validation

| Requested | Resolution |
|---|---|
| Reproducible checks in this milestone | `cd contract && npm ci && npm run validate` |
| Pinned dependencies, isolated under `contract/` | Four exact versions in `contract/package.json`, with a lock file |
| Each listed check | Table in `contract/README.md` |
| Negative cases | 22 wrong responses and 53 broken fixtures, each rejected for a stated reason |
| Dedicated workflow, existing one untouched | `.github/workflows/contract.yml` |
| Node 24 | The workflow reads `.nvmrc`; `package.json` requires Node 24 or later |

### 4.6 Timezone

- Timestamps are UTC instants at whole-second precision, displayed in the visitor's timezone.
- A case stores its deadline date, the zone it was set in (`deadlineTimezone`) and the cutoff instant (`deadlineEndsAt`).
- Changing the organization timezone affects new deadlines only. Existing ones do not move.
- Daylight saving is handled by computing the cutoff with the zone's rules. The fixtures include a deadline on the day clocks change.

## 5. Changes beyond what was asked

The frontend agent should confirm or counter each.

1. **`reverses` renamed `corrects`.** A partial refund is not a reversal, and the name now pairs with `correctedBy`.
2. **`LedgerEntry.counterpartCaseCode`.** Names the other end of a transfer, so the fund's page can say where money went.
3. **`Case.acceptingDonations`.** Separates accepting gifts from status, as requested in the lifecycle note.
4. **`Case.deadlineTimezone`.** Needed so a timezone change cannot move an old deadline.
5. **`CaseDetail.asOf`.**
6. **`CaseUpdate.photoAlt` and `photoAltLanguage`.**
7. **`sort=deadline` orders by `deadlineEndsAt`**, the real cutoff, not by the date text.
8. **Whole-second timestamps**, enforced by a pattern.
9. **Page schemas no longer use `allOf`**, so they can be closed. `PageMeta` is gone; the four page fields are unchanged.
10. **Money schemas renamed:** `MovementCents` (one movement or goal), `Cents` and `SignedCents` (totals), with the new bounds.
11. **An over-long search returns 400.** The frontend should limit its input to 80 characters.

## 6. Changes to the architecture record

- Status: stack and repository approved by the owner; same-origin still proposed; hosting undecided.
- Deployment: one-origin path table including public pictures; portable container plan with HTTPS at the proxy, persistent volumes, private database network, backups and a rehearsed restore.
- New sections on time, exact arithmetic and consistent reads.
- Categories are table rows managed by the super administrator.
- Versions are proposals until verified on the vendors' own documentation before milestone 0B.

## 7. Validation results

Run on this revision with Node 24.22.3 and the pinned tools (`@redocly/cli` 2.83.0, `ajv` 8.20.0, `ajv-formats` 3.0.1, `yaml` 2.9.1).

| Check | Result |
|---|---|
| Redocly lint, recommended rules | Pass, no errors or warnings |
| References resolve | Pass, 142 |
| Examples validate | Pass, 14 |
| Object schemas closed, no private property names, pictures use `MediaPath` | Pass, 20 schemas |
| Wrong responses rejected | Pass, 22 of 22 |
| `example.json` format and integrity | Pass, 5 cases, 14 ledger entries |
| Reconciliation invariants | Pass |
| Expected values equal computed values | Pass |
| Public projection up to date, valid, no private marker, totals unchanged | Pass |
| Generated responses valid in both languages | Pass, 54 |
| Examples in `openapi.yaml` reproduced from the fixture | Pass, 7 of 7 |
| Broken fixtures rejected for the stated reason | Pass, 53 of 53 |

The checks were also run against deliberately damaged copies of the contract and fixture to confirm they fail.

## 8. Limits of this revision

- The reference implementation in `tools/lib.mjs` was written by the backend agent, the same author as the contract. It proves the documents agree with each other, not that the rules are the right ones. The frontend review is the independent check.
- Search limits (200 and 80 code points) and the trim character set are specified but have no fixture yet; they need the full dataset and golden queries of milestone 0B.
- Version numbers in the architecture record are still from a web search, not vendor pages.
- No retention period, hosting provider or domain is assumed anywhere.

## 9. Acceptance checklist

Frontend agent:

- [ ] The three approved pages can be built from these responses with no visual change.
- [ ] Each of the 11 changes in section 5 is accepted or countered.
- [ ] The public projection format can be produced from the existing generator.
- [ ] The search, sort and correction rules can be implemented identically in the mock.
- [ ] `npm run validate` passes on the frontend agent's machine.

Backend agent:

- [x] Every rule in the contract can be enforced in PostgreSQL and tested.
- [x] Every money field has one definition in `financial-terms.md`.
- [x] Every check runs from one command and in CI.

Project owner:

- [x] Backend stack: Spring Boot and PostgreSQL.
- [x] One repository with `backend/`.
- [x] Six initial categories, extendable.
- [x] Local display of event times; `America/Chicago` for deadlines.
- [ ] Status grouping confirmed.
- [ ] General fund pays only by transfer, confirmed.
- [ ] Same-origin deployment confirmed.
- [ ] Authorization to merge and to start milestone 0B.
