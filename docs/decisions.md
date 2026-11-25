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
