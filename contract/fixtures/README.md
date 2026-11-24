# Shared fixtures

One deterministic, bilingual dataset used by both sides:

- the frontend mock adapter serves it;
- the backend seed script loads it into PostgreSQL for integration and golden-query tests.

Using the same inputs is what makes "mock and real return the same thing" testable.

## Files

| File | Status |
|---|---|
| `fixture.schema.json` | The format. Delivered in milestone 0A. |
| `example.json` | A small valid dataset that shows every feature of the format. Delivered in milestone 0A. |
| `dataset.json` | The full dataset. Produced in milestone 0B by the frontend agent from the existing generator in `src/api/directory-fixtures.ts`, then reviewed by both agents. |
| `golden/` | Expected responses for the fixed list of requests. Produced in milestone 0B. |

## Principles

1. **Inputs only.** The dataset holds what an administrator and donors would enter: cases, bilingual text, ledger entries, donor listings, updates and pictures. It never holds totals. `received`, `delivered` and every count are computed from the ledger entries by each implementation, so a wrong calculation shows up as a test failure.
2. **Fixed clock.** `clock` is the instant both implementations treat as "now". Nothing in a response may depend on the real date.
3. **Both languages inline.** Localized text is `{ "en": "...", "fr": "..." }`. A missing language is written as an absent key, which is how fallback is tested.
4. **Fictional.** Every person, story and amount is invented and must be recognizable as a sample.
5. **Stable identifiers.** Identifiers are written in the file, never generated at load time.

## What the full dataset must contain

- The existing 200 active cases, so the approved directory scenario is preserved (17 pages of 12, last page of 8).
- The existing case `HELP-014` with its current amounts: 567500 received, 320000 delivered.
- The general fund `FUND`, funded by donations and by overflow transfers, with no direct disbursement.
- At least one case in each public status: `active`, `funded`, `delivered`, `closed`.
- At least two draft cases, to prove they never appear.
- Every category, and at least three cases with a `featuredRank`.
- Sorting edge cases: no goal, no deadline, exactly at goal, over goal, zero received, equal ratios, equal creation times.
- Ledger edge cases: a transfer between two cases, a reversed donation, a refund, a reversed transfer, a reversed disbursement, and one case with a shortfall.
- Donor visibility: named, anonymous and hidden donations on the same case, a named donor with picture consent and one without.
- Localization: an update written in one language only, and a picture description in one language only.
- Search: titles with French accents, and a public name that differs only by accents from another.
- Pictures: a case with a main picture and a gallery, a case with neither.
- Private data markers: every case carries a `private` block with obviously fake values, so leak tests can search responses for them.

## Private data in fixtures

The `private` block exists only so tests can assert its contents never appear in a public response.
The frontend mock adapter must drop it when loading. It is never real information.
