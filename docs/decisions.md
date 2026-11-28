# Approved project decisions

Decisions made by the project owner, recorded here so they are not re-opened by accident.
The review history of milestone 0A stays in `contract/M0A-review.md`, unchanged; where that file lists a decision as pending, this file is the current answer.

## Approved on 2026-11-24, with the authorization of milestone 0B

| Topic | Decision | Where it is applied |
|---|---|---|
| Current cases | Status `active` and `funded` | `GET /api/cases?status=active` |
| Completed cases | Status `delivered` and `closed` | `GET /api/cases?status=completed` |
| Partial funding | Does not prevent a case from being completed | No rule ties `delivered` to the goal |
| General fund | Money is allocated to an individual case before it reaches a beneficiary. The fund does not disburse directly. | `contract/financial-terms.md` section 11 |
| Deployment | Frontend, API and public pictures on one origin | `docs/adr/0001-backend-architecture.md` |
| Languages | English and French titles and stories are both required to publish a case | Database constraint `aid_case_published_is_complete` |
| Funded cases | Keep accepting gifts until paused or past their deadline, with a disclosure about where overflow goes | `Case.acceptingDonations`; the disclosure text is frontend work |
| Event times | Shown in each visitor's own timezone | API returns UTC instants |
| Deadlines | Organization timezone, configurable, default `America/Chicago` | `organization_settings`, stored per case |
| Categories | Managed as data, seeded with the six initial ones | `category` table, `GET /api/site` |
| Backend | Spring Boot and PostgreSQL, Java 21 | `docs/adr/0001-backend-architecture.md` |
| Repository | One repository with `backend/` at the root | This repository |
| Hosting and domain | Deferred | Nothing depends on them yet |

## Settled requirements for later milestones

Not implemented in milestone 0B. Recorded so the later contract and schema work starts from them.

Payment methods:

- Zelle, PayPal, Cash App and Apple Cash. Apple Cash means person-to-person transfers, not Apple Pay: no merchant processing, no Apple software kit, no payment gateway.
- "Other" sends the donor to the contact form and does not create a donation report.
- Payment methods are records the super administrator manages, like categories. Adding one that uses the same pay-outside-then-report flow needs no code change, migration or redeployment.
- Recipient details are versioned. A donation keeps a reference to the method and to the details that were shown when it was reported.
- Every change to payment configuration is restricted to super administrators and written to the audit log.
- Before a method is enabled in production, the provider's eligibility rules, geographic limits and terms are checked. A personal account is not assumed to be permitted for collecting donations.

Donations:

- No account is required to give or to report a gift.
- A payment screenshot is optional. When given, it is stored privately and is supporting evidence, not proof of receipt.
- The super administrator must verify that the money was actually received before confirming.
- A reported donation never changes a public total until it is confirmed.
- The reported amount and the confirmed amount are stored separately.
- The site never processes payments and never collects card numbers, bank credentials, payment passwords, one-time codes or Apple account credentials.

Corrections:

- The future correction write API must tell the repair of an erroneous record (`donation_reversal`, no money moved) apart from money that really went back (`refund`), and must never record a genuinely new external payment as a correction.

Still open, to be decided in their own milestones: how long screenshots are kept, guest donation tracking, and email infrastructure (including future automated verification and recovery). Manual recovery for M1 is settled below.

## Approved on 2026-11-25 — milestone 1A

The project owner explicitly approved all seven M1A security policy decisions and adopted the operational recovery advice, and authorized merging PR #8 after verification of a documentation-only closure revision and its final CI. The independently accepted technical checkpoint is `b5ea149fb3e33380dd622ad734117a15051b4bfd` (contract v0.3.0). See [PR #8](https://github.com/abdoulbachir/APV/pull/8) and `contract/M1A-review.md`. This accepts the design, not an implemented authentication system.

| # | Topic | Approved decision |
|---|---|---|
| 1 | Public registration | Closed in production until email address verification is available; retain registration contract and dev/test coverage. Donating and reporting a gift remain account-optional. |
| 2 | Session lifetimes | Donor: 2 hours idle, 24 hours absolute. Staff: 30 minutes idle, 12 hours absolute. |
| 3 | Staff MFA | Mandatory for administrators and super administrators; authenticator application (TOTP) and ten single-use recovery codes. |
| 4 | Staff management | Server-side operator commands for provisioning and recovery until the milestone 5 administration interface. |
| 5 | Manual recovery | Verify identity through a previously established independent contact channel; refuse when verification is not possible. Never rely on facts supplied by the requester. |
| 6 | Passwords | Minimum 15 Unicode code points after NFC normalization, local blocklist, no composition rules; retain the reviewed contract's maximum length and other safeguards. |
| 7 | Email addresses | ASCII-only initially; revisit if needed. |

Operational advice (adopted, not an application invariant): maintain at least two usable trusted super administrators when practical, and keep recovery codes securely offline. With only one usable super administrator who loses all access, the server-side recovery path in the identity contract applies.

The reviewed identity design is the M1B implementation baseline, subject to the four compatibility probes in ADR 0002 and subsequent runtime acceptance. Generated-comment cleanup and explicit disposable-database enforcement remain implementation tasks. Generated fictional dataset cleanup is a separate post-M1A task before parallel M1B implementation. No runtime authentication, real-data deployment, or production readiness is accepted by this decision.

## Approved on 2026-11-25 — milestone 1B corrective follow-up

The owner explicitly approved the following two policies after reviewing the backend corrective delivery at `81df9dd8a6ee104c47df9520a40828ed1cfee7e5`:

- Protected password operations: a shared client-address budget of 30 requests in 15 minutes for password change and recovery-code regeneration, independent of the sign-in client-address budget, in addition to existing account password/second-factor limits. Refusal is 429 with bounded Retry-After until the window ends. The address budget is checked at step 5.
- Production requires a comprehensive local breached-password blocklist. Refuse startup outside dev/test if the configured list is missing, unreadable or unsuitable; the small bundled starter list alone is not sufficient. Keep dev/test usable with the starter list. Do not send passwords or hashes to another service. Record the selected list, provenance, format, integrity, sizing and update procedure before deployment.

The owner also directed that the existing prohibition on logging session identifiers be enforced, including expired and rotated identifiers at DEBUG/TRACE. No exception for retired identifiers. This is a correction to implementation, not a change to the accepted confidentiality rule.

These decisions do not authorize merging PR #10 or #11, accepting M1B, or deploying to a real host. Remaining questions: precise wording of body-keyed rate-limit precedence and pending-session discard timing; used/expired credential retention remains a later deployment decision. Preserve existing API schemas, error codes and financial behavior. Joint integration and independent security acceptance remain required.

## Approved on 2026-11-26 — milestone 2A (donation lifecycle contract)

The owner approved all fifteen decisions of `contract/M2A-review.md`, each as the recommended option (A), at contract version 0.4.0 on branch `feature/m2a-donation-contract`. This accepts the design, not an implemented donation system; the write side is milestone 2B. The rules are in `contract/donation-lifecycle.md`; the acceptance scenarios in `contract/tests/donation-acceptance.md`.

| # | Topic | Approved decision |
|---|---|---|
| 1 | Guest tracking | A one-time tracking code shown once on the receipt; looked up by `POST /donations/status`; the status shows an amount, a case and a state, never a personal detail. |
| 2 | What the donor is told, with no email | Only on pages, in both languages (wording written and approved in 2B). Nothing is emailed; the initiative is not called a charity. |
| 3 | Optional contact field | Include an optional private `contact` field. The site never sends anything to it. |
| 4 | Provider eligibility | Recorded as an attestation at method activation (who checked, when, what); production refuses activation without it. |
| 5 | Methods at launch | Start with the one or two the owner already uses and can publish; the handles are supplied privately as server configuration, never committed. |
| 6 | Managing methods until M5 | The operator command on the server, like staff accounts; no network route in M2. |
| 7 | General fund on a real database | A one-time migration inserts it, empty. Name and description approved exactly as written in `contract/donation-lifecycle.md` section 10 (EN/FR), shipped in the migration unchanged. |
| 8 | Who reviews in M2 | Only the super administrator (`donations.confirm`), now returned by `GET /me`. |
| 9 | Guest public name | The donor's choice at report time is the consent; the reviewer may show less but never add a name; a name is never edited; it can be withdrawn later. |
| 10 | Confirm for a different case | Allowed, with a required note. The report keeps the donor's case; the confirmation records the real one. |
| 11 | Lifecycle transitions | `financial-terms.md` section 9 is confirmed (no longer a working proposal). |
| 12 | Screenshot limits | JPEG and PNG only, ≤ 5 MiB, ≤ 40 Mpx, one per gift, re-encoded and scaled to ≤ 2,400 px. No antivirus engine in M2. |
| 13 | "Other" until the contact form exists | A static page with the organisers' contact (wording written in 2B). |
| 14 | Rate-limit and brake numbers | The starting values in `donation-lifecycle.md` section 12, tunable, revisited after first use. |
| 15 | Retention | Screenshots: 30 days after a decision. Payer name, contact, payment reference, notes: 90 days after a rejection, 24 months after a confirmation. Amounts, cases, dates, ledger and audit: for ever. |

Keep the contract additive: no change to accepted M0/M1 behaviour, schemas or the identity order of checks. The site never processes payments. Four implementation notes for 2B are recorded in `contract/donation-lifecycle.md` (a lost evidence 204 spends the token; duplicate hints cannot match an erased payer name; a `reentryOf` staff entry carries the original's tracking code; the per-reference status limit keys on the reference, backstopped by the per-address limit).

## Approved on 2026-11-28 — milestone 2B (questions raised by the backend implementation)

| Topic | Approved decision |
|---|---|
| Private note on a correction | Required on both a reversal and a refund, as `contract/donation-lifecycle.md` 8.1 says. The request schemas say so since contract version 0.4.1; a request without it answers 400, field `note`, `required`. |
| Provider reference on a manual entry | A manual entry takes an optional `providerReference`, as `contract/donation-lifecycle.md` section 7 says; the request schema has it since contract version 0.4.2. It takes part in the rule of 6.3: one reference is recorded once per payment method among confirmed gifts, whether a gift was reported or entered. |
| Payment card numbers | The refusal (`card_number`) stays on every text field, reference fields included, although an all-numeric reference of 13 to 19 digits is refused about one time in ten. |

Accepted as implemented, with no change: the contract defines fifteen donation operations (four for giving, eleven for review); a re-entry is listed as the less visible of the original's listing and the one requested, under the name the donor agreed to show; a malformed tracking code answers 404 like a wrong one; migration V7; and the points the contract left open (`refundedOn` has the range of `receivedOn`, a staff entry is listed with the instant it was entered, a repeated staff request is answered with the gift as it is now, a brake writes one audit record an hour while it holds).
