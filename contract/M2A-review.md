# Milestone 2A review

The contract for the donation lifecycle: reporting a gift, private evidence, review, manual entry, corrections, payment methods, and the general fund on a real database. Contract and decisions only; no runtime code.

- Baseline: `main` at `70950b7` (M1B and the manual-test fixes merged).
- Branch: `feature/m2a-donation-contract`.
- Contract version: 0.4.0 (minor bump; additive, nothing accepted in M0/M1 changed).
- Status: **accepted.** On 2026-11-26 the owner approved all fifteen decisions below, each as the recommended option (A), with the specifics noted on decisions 3, 7, 11 and 15. The contract documents are updated to match.

Deliverables in this pull request:

| File | What |
|---|---|
| `openapi.yaml` | 0.4.0: 14 new operations (4 giving, 10 review), their schemas, parameters and responses; new error codes; the `donations.confirm` permission; the `card_number` field code |
| `donation-lifecycle.md` | The rules, in the style of `identity-and-security.md` |
| `tests/donation-acceptance.md` | The acceptance scenarios M2B must pass, in the style of `tests/security-acceptance.md` |
| `tools/check-openapi.mjs`, `tools/generate-public-types.mjs` | Extended for the new tag family, binary uploads and path/query parameters on non-public operations |
| `src/api/generated/*.ts` | Regenerated (additive) |
| `M2A-review.md` | This file |

"DL" is `donation-lifecycle.md`. "I&S" is `identity-and-security.md`.

## 1. What the contract adds

### 1.1 Operations

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/giving/methods` | Anyone | Active payment methods and the recipient details to pay to |
| POST | `/donations` | Anyone | Report a gift paid outside the site |
| POST | `/donations/evidence` | Anyone (with the receipt's token) | Attach one screenshot |
| POST | `/donations/status` | Anyone (with the tracking code) | The state of one gift |
| GET | `/admin/donations` | Super admin | The review queue and list |
| POST | `/admin/donations` | Super admin | Manual entry (confirmed at once) |
| GET | `/admin/donations/{id}` | Super admin | Everything about one gift |
| GET | `/admin/donations/{id}/evidence` | Super admin | The stored screenshot |
| POST | `/admin/donations/{id}/evidence/delete` | Super admin | Delete a screenshot early |
| POST | `/admin/donations/{id}/confirm` | Super admin | Confirm receipt |
| POST | `/admin/donations/{id}/reject` | Super admin | Reject a report |
| POST | `/admin/donations/{id}/reopen` | Super admin | Put a rejected gift back in the queue |
| POST | `/admin/donations/{id}/reversals` | Super admin | Repair a record; no money moved |
| POST | `/admin/donations/{id}/refunds` | Super admin | Record money that went back |
| POST | `/admin/donations/{id}/listing` | Super admin | Show less of a donor |

### 1.2 How it stays inside the accepted design

- **Nothing public before confirmation** (DL 9.2), **reported and confirmed amounts separate** (DL 1.2), **`donation_reversal` vs `refund`** (DL 8), **a new payment is never a correction** (DL 7, 8): the settled requirements of `../docs/decisions.md`, written as endpoints.
- **The site takes no payment** (DL intro, 12.4, 17): no payment field, no provider SDK, card-shaped text refused.
- **The order of checks is I&S 4.3 unchanged** (DL 11.2): nothing inserted before or between its steps.
- **Evidence follows `media-and-privacy.md`**: no public token, re-encoded, metadata dropped, served only to `donations.confirm`.
- **The ledger is written only by the ledger module** (ADR 0001), under the correction rules of `financial-terms.md` section 2.
- **`donations.confirm` becomes operational** the way I&S 1.2 announced a permission would: it is now returned in `GET /me` for a super administrator.

### 1.3 Tooling

`check-openapi.mjs`: the new tags `Giving` and `Donation review` join the non-public family (no-store, session states, may serve `account`/`credential`); `reportDonation` is added to the credential operations; a `POST` marked `x-binary-upload` may take `image/*` bodies; binary responses need no JSON example. `generate-public-types.mjs`: non-public operations may now carry path and query parameters, emitted as `query`/`path` shapes like the public operations. `npm run validate` passes: Redocly lint clean, 27 non-public and 7 public operations, 82 examples, 659 refs, 67 closed object schemas, the 76 negative request/response cases and 41 contract mutations still rejected, the full fixture dataset and golden checks unchanged. (The frontend-parity step needs Node ≥ 22 for `--experimental-strip-types`; it covers only the unchanged public read model and runs in CI.)

## 2. Decisions

**Accepted 2026-11-26.** The owner approved every decision as the recommended option (A). Each heading below is marked accepted; the question, options and recommendation are kept for the record. Specifics the owner fixed: decision 3 — include the optional `contact` field; decision 7 — the fund name and description are approved exactly as written and ship in the migration unchanged; decision 11 — `financial-terms.md` section 9 is confirmed; decision 15 — the recommended retention periods. The page wording of decisions 2 and 13 is the frontend's to write in 2B for the owner to approve.

Each names the question, the options, and the recommendation (the first option, which the contract text follows).

### Decision 1 — Can a guest follow a reported gift, and how?

*Accepted 2026-11-26: option A.*

- **A recommended. A tracking code shown once on the receipt (DL 5).** 20 characters, 60 secret bits, looked up by `POST /donations/status`, rate-limited; the status shows an amount, a case and a state, never a personal detail; wrong, malformed and unknown all answer 404 alike.
- B. No tracking at all. A donor who wants to check contacts the organisers and quotes the reference.
- C. Tracking by the reference alone (no secret). Simpler, but anyone who sees a reference learns that gift's amount and case.

Recommendation (accepted): **A.** It is what a donor expects ("did it go through?") without email, and it leaks nothing to someone who does not hold the code. B is cheapest to build. C is too loose, because the reference appears in the receipt and in any later message.

### Decision 2 — What is the donor told, with no email?

*Accepted 2026-11-26: option A.*

- **A recommended. Only on pages, in both languages, with the wording of DL 5.2**, approved by the owner. The receipt says plainly: recorded, not confirmed, checked by hand, nothing will be emailed, keep the code.
- B. The same, but also show the organisers' contact on every status page.

Recommendation (accepted): **A**, with the exact English and French text to be written by the frontend and approved. The point the owner must accept: the site promises nothing by email and never calls the initiative a charity or a gift tax-deductible.

### Decision 3 — May a guest leave a way to be reached, and what does the site do with it?

*Accepted 2026-11-26: option A.*

- **A recommended. An optional `contact` field, stored privately, that the site never uses to send anything** (DL 3.3). It only lets the organisers reach a donor by hand about a specific gift.
- B. No contact field at all until email exists.

Recommendation (accepted): **A.** It costs nothing, helps the organisers resolve an unclear payment, and the contract already forbids the site from sending to it. If the owner prefers B, drop the field.

### Decision 4 — How is provider eligibility handled, given the site cannot check it?

*Accepted 2026-11-26: option A.*

- **A recommended. An attestation recorded at activation (DL 2.5).** `payment-method-activate` asks who checked the provider's terms, when, and what; production refuses to activate without it; it is audited. An attestation by a person, not proof.
- B. Nothing recorded; eligibility is the operator's own responsibility, off the system.

Recommendation (accepted): **A.** The owner's own requirement says eligibility must be checked before a method is enabled; recording that it was is the most the software can honestly do.

### Decision 5 — Which payment methods exist at launch, and with what recipient details?

*Accepted 2026-11-26: option A.*

The owner must name the methods to turn on first and the exact recipient handles to publish (an email, a phone, a `$cashtag`, a PayPal link). These handles are public by nature.

- **A recommended. Start with the one or two the owner already uses and can publish**, for example Zelle and PayPal, and add the others later with no code change.
- B. Launch with all four (Zelle, PayPal, Cash App, Apple Cash).

Recommendation (accepted): **A.** Fewer methods mean fewer handles to watch and fewer eligibility checks at launch. The owner supplies the handles privately; they are not in this contract. Nothing is enabled until the owner provides them.

### Decision 6 — How are payment methods managed until the milestone 5 screens exist?

*Accepted 2026-11-26: option A.*

- **A recommended. The operator command on the server (DL 2.5)**, like staff accounts: five subcommands, prompting on the terminal, never over the network.
- B. A super-administrator web screen now, brought forward from milestone 5.

Recommendation (accepted): **A.** Whoever can change a recipient detail can redirect every future gift; keeping that behind server access in milestone 2 matches the trust the first super administrator rests on, and defers the web screen to its planned milestone. B is more convenient but widens that power earlier.

### Decision 7 — How does the general fund come to exist on a real database, and its public text?

*Accepted 2026-11-26: option A.*

- **A recommended. A one-time migration inserts it, empty, when none exists (DL 10)**, with the English and French name and description below, to be edited like any case when case administration arrives.
- B. The operator command creates it, like a case.

Recommendation (accepted): **A.** A migration runs once on every database with nothing to remember, and never writes fictional data. Proposed text for approval:

| | English | French |
|---|---|---|
| Name | General fund | Fonds général |
| Description | Gifts to the general fund are not tied to one case. They are kept until APV-GAKASINAY allocates them to a case: for an urgent need, or to complete a case that has not reached its goal. Every allocation appears in the record of this fund and in the record of the case that receives it. The fund never pays a beneficiary directly. | Les dons au fonds général ne sont pas liés à un cas précis. Ils sont conservés jusqu’à ce qu’APV-GAKASINAY les affecte à un cas : pour un besoin urgent, ou pour compléter un cas qui n’a pas atteint son objectif. Chaque affectation apparaît dans l’historique de ce fonds et dans celui du cas qui la reçoit. Le fonds ne verse jamais d’argent directement à un bénéficiaire. |

### Decision 8 — Who reviews gifts in milestone 2?

*Accepted 2026-11-26: option A.*

- **A recommended. Only the super administrator (`donations.confirm`) (DL 6.1).** `donation_reports.read` for administrators stays a planned name and grants nothing.
- B. Administrators may read the queue now (`donation_reports.read` becomes operational), only the super administrator confirms.

Recommendation (accepted): **A.** It keeps milestone 2's surface to one permission and one role, and matches the owner's "me and chosen admins" only where money is decided. B can follow in milestone 4 with case administration.

### Decision 9 — A guest's public name: consent and limits.

*Accepted 2026-11-26: option A.*

- **A recommended. The donor's choice at report time is the consent (DL 3.4); the super administrator can refuse to show a typed name but never add one; a name is never edited; it can be withdrawn later to anonymous or hidden (DL 8.4).**
- B. Never show a guest's name at all; a public name needs an account.

Recommendation (accepted): **A.** It honours the approved named/anonymous/hidden model for guests while letting the reviewer stop an abusive or mistaken name. B is safest but removes a donor's genuine wish to be thanked by name.

### Decision 10 — May a gift be confirmed for a different case than reported?

*Accepted 2026-11-26: option A.*

- **A recommended. Yes, with a required note (DL 6.3).** The report keeps the donor's case; the confirmation records the real one; the tracking page shows both.
- B. No: confirm only for the reported case; to move money, confirm then transfer (a milestone 5 operation).

Recommendation (accepted): **A.** Donors pick the wrong case or the fund by mistake often; forcing a transfer later (which does not exist until milestone 5) would block honest confirmations. The reported case is never lost.

### Decision 11 — Confirm the lifecycle status transitions (financial-terms section 9).

*Accepted 2026-11-26: option A.*

That section is still "working proposal, pending owner confirmation". Milestone 2 makes the first rows operational: a confirmation that reaches the goal makes a case `funded`; a correction below the goal returns it to `active`; a correction on a `delivered`/`closed` case flags it for review and does not change status.

- **A recommended. Confirm the table as written.**
- B. Change a transition (the owner says which).

Recommendation (accepted): **A.** The read model already depends on it, and the frontend reports `acceptingDonations` from the server regardless.

### Decision 12 — Screenshot limits (formats, size, dimensions).

*Accepted 2026-11-26: option A.*

- **A recommended. JPEG and PNG only, ≤ 5 MiB, ≤ 40 Mpx, one per gift, re-encoded and scaled to ≤ 2,400 px (DL 4).**
- B. Also accept HEIC and PDF.

Recommendation (accepted): **A.** JPEG and PNG cover every phone screenshot and photo; HEIC and PDF widen the decoder surface for little gain. No antivirus engine is added in milestone 2; re-encoding from pixels is the defence (DL 4.3).

### Decision 13 — What does "Other" say until the contact form exists?

*Accepted 2026-11-26: option A.*

"Other" leads to the contact page (approved), but the contact form is a later milestone.

- **A recommended. A static page that shows the organisers' own contact and says gifts by another method can be arranged by reaching out.**
- B. Hide "Other" until the contact form exists.

Recommendation (accepted): **A.** "Other" is an approved part of the flow; a static page honours it without building the form early. The owner supplies the contact shown there.

### Decision 14 — Rate-limit and brake numbers.

*Accepted 2026-11-26: option A.*

DL 12.2 and 12.3 are starting values: 6 reports/hour per address; site-wide 60 reports/hour, 300 waiting, 2 GiB evidence, 2 concurrent decodes.

- **A recommended. Adopt these as starting values, tunable by configuration, revisited after the first real use.**
- B. Different numbers (the owner says which).

Recommendation (accepted): **A.** They are deliberately low for a friends-and-family site and never disable reporting for good. Like the identity limits, they are not measured yet.

### Decision 15 — How long is private data kept?

*Accepted 2026-11-26: option A.*

- **A recommended (DL 14). Screenshots: 30 days after a decision. Payer name, contact, payment reference, notes: 90 days after a rejection, 24 months after a confirmation. Amounts, cases, dates, ledger and audit: for ever.**
- B. Other periods (the owner says which).
- C. Keep everything until milestone 6 decides, with no automatic deletion.

Recommendation (accepted): **A.** Evidence is the most sensitive and least needed once a gift is decided; the private details support a correction for a plausible window. This is not legal advice; it does not reach backups older than the period (a milestone 6 concern). If the owner is unsure, B with longer periods is safer than C, which keeps screenshots indefinitely.

## 3. Open questions carried forward (not for this milestone)

- Email to donors, and automated verification/recovery (I&S, unchanged).
- A donor's own gift history and claiming a guest gift for an account (milestone 3). The contract keeps a guest gift unlinked and never matches by address (DL 3.1).
- The contact form and reading contact messages (milestone 5).
- Transfers and disbursements from the fund, and web screens for payment configuration (milestone 5).
- The application's database role in production, and backup protection and retention for the files volume holding evidence (milestone 6).
- A real breached-password corpus (carried from M1B; unrelated to donations).

## 4. Risks and limits

- **Anonymous reporting is an open door.** It is bounded, not closed: nothing public changes before review, bodies are small and closed, files only follow an accepted report, and per-address limits plus site-wide brakes cap the flow. A patient person can still file a few false reports an hour per address, each costing the reviewer a look (DL 12.4, 17).
- **Confirmation is a human duty.** The system records that the super administrator verified receipt; it cannot prove they did (DL 6.3, 17).
- **Recipient details are the highest-value target.** No network operation changes them in milestone 2; the operator command, versioning and audit are the controls (DL 2.5, 17).
- **The decode surface** is the residual risk of accepting pictures; re-encoding, sniffing, pixel and time limits and a current runtime bound it. No antivirus engine in milestone 2.
- **Numbers are starting values**, like the identity limits: not measured.
- **This is a contract.** No runtime, no migration, no screen exists yet; the four ADR-style guarantees (idempotency, locks under correction, re-encoding, the one-time fund migration) are design to be verified in 2B.

## 5. Acceptance checklist

Backend agent:

- [ ] Every rule in DL can be enforced on the approved stack with no dependency upgrade; the four runtime guarantees (idempotency store, correction locks, picture re-encoding, the fund migration) are confirmed at the start of 2B.
- [ ] Every new response and request is a closed schema; the checker's new rules are covered by contract mutations (to be added with implementation, as in M1A).

Frontend agent:

- [ ] The giving, receipt, status, review and correction screens of DL 16 are implementable as written; `identity-api.ts` has the shape the frontend wants (path/query now emitted).
- [ ] The acceptance scenarios marked **B** are feasible.

Project owner:

- [x] Decisions 1–15 above (approved 2026-11-26, all option A).
- [x] The general fund's name and description (decision 7): approved exactly as written.
- [ ] The payment methods and recipient handles to publish (decision 5): supplied privately to the backend agent as server configuration, not in the repository.

Independent acceptance:

- [ ] Contract validation passes at the accepted head on Node 24.
- [ ] No accepted M0/M1 behaviour, schema or security precedence changed.
