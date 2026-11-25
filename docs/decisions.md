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

Still open, to be decided in their own milestones: how long screenshots are kept, guest donation tracking, password recovery, email.
