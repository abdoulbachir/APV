# Shared fixtures

One deterministic, bilingual dataset used by both sides:

- the backend seed script loads the full file into PostgreSQL for integration and golden-query tests;
- the frontend mock adapter serves the public projection of the same file.

Using the same inputs is what makes "mock and real return the same thing" testable.

## Files

| File | Status |
|---|---|
| `fixture.schema.json` | Format of the full dataset. |
| `fixture.public.schema.json` | Format of the public projection. |
| `example.json` | A small valid dataset that shows every feature of the format. |
| `example.expected.json` | Values every implementation must compute from `example.json`. |
| `example.public.json` | Public projection of `example.json`. Generated; do not edit by hand. |
| `negative-cases.json` | Changes to `example.json` that must each be rejected, with the reason. |
| `negative-public-cases.json` | Changes to the projection that must each be rejected. |
| `dataset.json`, `dataset.public.json` | Generated, ignored by Git. Full fictional dataset and its public projection from `contract/tools/generate-dataset.mjs`. |
| `golden/` | Expected responses for the fixed list of requests. Produced in milestone 0B. |

All fixtures are checked by `npm run validate` in the `contract` folder. On a clean checkout, the large datasets do not exist until generated. The small example, schemas, negative cases, golden expectations and symbolic artwork remain tracked.

## Principles

1. **Inputs only.** The dataset holds what an administrator and donors would enter: categories, cases, bilingual text, ledger entries, donor listings, updates and pictures. It never holds totals. `received`, `delivered` and every count are computed from the ledger entries by each implementation, so a wrong calculation shows up as a test failure.
2. **Fixed clock.** `clock` is the instant both implementations treat as "now". Nothing in a response may depend on the real date.
3. **Both languages inline.** Localized text is `{ "en": "...", "fr": "..." }`. Case titles, case stories and category labels need both. Update text and picture descriptions may have one, written as an absent key, which is how fallback is tested.
4. **Fictional.** Every person, story and amount is invented and must be recognizable as a sample.
5. **Stable identifiers.** Identifiers are written in the file, never generated at load time.

## The public projection

The full file contains draft cases, hidden donors, names of anonymous donors and `private` blocks of fake markers. They exist so leak tests can prove none of it reaches a public response. A frontend bundle is public, so the frontend must never import the full file.

`npm run project` writes `example.public.json`. To project another file: `node tools/project-public.mjs fixtures/dataset.json`.

The projection is the same inputs with everything private removed:

| Removed or changed | Rule |
|---|---|
| Draft cases, and every ledger entry and update on them | Removed. |
| `private` block of each case | Removed. |
| Donor of a hidden donation | Removed. The ledger entry stays, because its amount is public. |
| Name of an anonymous donor | Set to null. |
| Donor picture | Kept only for a named donor who agreed to show it. |
| Author picture on an update | Kept only when the author agreed to show it. |
| Consent flags and donor visibility | Removed. The projection already reflects them. |
| Inactive categories used by no public case | Removed. |

Nothing that affects a total is removed, so the mock computes the same numbers as the backend. The check fails if the committed projection is out of date, contains a private marker, or changes any total.

## What `example.json` demonstrates

| Feature | Where |
|---|---|
| A seventh category added after launch | `food`, used by `HELP-904` |
| A deactivated category still used by a public case | `transport`, used by `HELP-903` |
| A deactivated category used only by a draft, so not listed | `retired`, used by `HELP-902` |
| Two partial refunds against one donation | `L08` and `L09` correct `L03` |
| A partial transfer reversal, written as an atomic pair | `L10` and `L11` correct `L05` and `L06` |
| A shortfall caused by a refund after delivery | `HELP-903`, entry `L14` |
| A case delivered without reaching its goal | `HELP-903` |
| A deadline whose cutoff falls on a daylight saving change | `HELP-904`, 2027-03-14 |
| A passed deadline | `HELP-903`, so `acceptingDonations` is false |
| Named, anonymous and hidden donations on one case | `L02`, `L03`, `L04` |
| Picture consent given, refused, and irrelevant | Donors `D1`, `D2`, `D3` |
| An update and a picture description in one language only | `U1`, `photo-1` |
| A draft case | `HELP-902` |

## What the full dataset must contain

- The existing 200 active cases, so the approved directory scenario is preserved (17 pages of 12, last page of 8).
- The existing case `HELP-014` with its current amounts: 567500 received, 320000 delivered.
- The general fund `FUND`, funded by donations and by overflow transfers, with no direct disbursement.
- At least one case in each public status: `active`, `funded`, `delivered`, `closed`.
- At least two draft cases, to prove they never appear.
- The six initial categories, at least one added category, one deactivated category still in use, and at least three cases with a `featuredRank`.
- Sorting edge cases: no goal, no deadline, exactly at goal, over goal, zero received, equal ratios, equal creation times, two deadlines on the same day.
- Ledger edge cases: a transfer between two cases, a reversed donation, several partial refunds of one donation, a fully refunded donation, a reversed transfer, a reversed disbursement, and one case with a shortfall.
- Donor visibility: named, anonymous and hidden donations on the same case, a named donor with picture consent and one without.
- Localization: an update written in one language only, and a picture description in one language only.
- Search: titles with French accents, a public name that differs only by accents from another, a query padded with white space, and a query of exactly 80 characters.
- Pictures: a case with a main picture and a gallery, a case with neither.
- Private data markers: every case carries a `private` block with obviously fake values.

## M0B full dataset

The full dataset preserves 200 current cases and all original directory case codes. HELP-101 and HELP-102 reuse the contract example's edge scenarios; selected generated cases cover funded, over-goal, paused, no-goal and equal-ratio cases. Two completed and two draft cases are additional. HELP-014 retains its original received and delivered amounts, and the fund retains its available balance with transfers rather than direct disbursements. All records are fictional. The existing approved symbolic WebP artwork is reused as fixture media, not as a photo of a real beneficiary.

Generate from the repository root with `npm run fixtures:generate` or `node contract/tools/generate-dataset.mjs` (Node 24; no npm dependencies needed). From `contract/`, use `npm run generate:dataset`. The generator writes the full fixture, its public projection and the identical browser-only copy in `src/api/dataset.public.json`; all three outputs are Git-ignored. The public projection excludes private and unpublished data. `npm run validate` regenerates and checks both datasets. Root `npm test`, `npm run dev` and `npm run build:mock` generate fixtures automatically. Before backend development startup or `./mvnw verify`, generate the fixtures explicitly; CI does this automatically. Normal `npm run build` is real mode and neither requires nor bundles the generated data. The frontend test locks the accepted fixture blob digests; an intentional fixture change requires review and updating the expected hashes. `golden/acceptance.json` contains initial independent expected values for cross-agent testing; more exhaustive request/response and error tests belong in the integration milestone.
