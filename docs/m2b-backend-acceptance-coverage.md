# Milestone 2B backend: coverage of the donation acceptance scenarios

Which executable test covers each scenario of `contract/tests/donation-acceptance.md`, as of the commit that contains this file. A test method named `d4_7_...` covers scenario 4.7.

This is the backend's part only. **It is not a statement that milestone 2B is accepted.** Scenarios marked **B** in the list are repeated by the real frontend in a real browser in the joint integration; the scenarios that are only about what a screen says or does are the frontend's.

How the backend tests run: the whole application on a real port, real HTTP with one cookie jar per simulated browser, PostgreSQL 18 started by Testcontainers. After every test, every giving and review answer it received is checked against scenario 8.9 and validated against the contract's closed schemas (`DonationIntegration.everyDonationAnswerMatchesTheContract`).

Test classes are in `backend/src/test/java/org/apvgakasinay/donations/` unless a path is given.

## 1. Payment methods

| # | Status | Executable tests and notes |
|---|---|---|
| 1.1 | Covered. Browser repeat: joint integration | `PaymentMethodsIT.d1_1_...` |
| 1.2 | Covered. Browser repeat: joint integration | `PaymentMethodsIT.d1_2_...` |
| 1.3 | Covered | `PaymentMethodsIT.d1_3_d1_7_...` |
| 1.4 | Covered | `PaymentMethodsIT.d1_4_...`; closed-schema validation of every answer |
| 1.5 | Server part covered. Browser: joint integration | `PaymentMethodsIT.d1_5_...`: a `link` value that is not an `https` address without credentials cannot be published. How the page opens it is the frontend's |
| 1.6 | Browser only | The server returns the value as a string and never as HTML |
| 1.7 | Covered | `PaymentMethodsIT.d1_3_d1_7_...`, `d1_7_...`; `PaymentMethodOperatorIT.d1_10_aMethodIsCreated...` |
| 1.8 | Covered | `PaymentMethodOperatorIT.d1_8_...`, under production rules |
| 1.9 | Covered | `PaymentMethodOperatorIT.d1_9_...` |
| 1.10 | Covered | `PaymentMethodOperatorIT.d1_10_everyCommandIsAudited...`; `PaymentMethodsIT.d1_10_...` (no network route) |
| 1.11 | Covered | `PaymentMethodsIT.d1_11_...` |

## 2. Reporting a gift

| # | Status | Executable tests and notes |
|---|---|---|
| 2.1 | Covered. Browser repeat: joint integration | `ReportingIT.d2_1_...` |
| 2.2 | Covered | `ReportingIT.d2_2_...`, `FundIT.d10_6_d10_7_...` |
| 2.3 | Covered | `ReportingIT.d2_3_d2_19_...` |
| 2.4 | Covered | `ReportingIT.d2_4_...` |
| 2.5 | Covered | `ReportingIT.d2_5_...` |
| 2.6 | Covered | `ReportingIT.d2_6_...` |
| 2.7 | Covered | `ReportingIT.d2_7_d2_8_...` |
| 2.8 | Covered | `ReportingIT.d2_7_d2_8_...`, `d2_8_theDetailsOfAMethodDeactivated...` |
| 2.9 | Covered | `ReportingIT.d2_9_...` |
| 2.10 | Covered | `ReportingIT.d2_10_...`; not logged: `AuditAndPrivacyIT` |
| 2.11 | Covered | `ReportingIT.d2_11_...` |
| 2.12 | Covered | `ReportingIT.d2_12_...` |
| 2.13 | Covered | `ReportingIT.d2_13_d2_14_...` |
| 2.14 | Covered | `ReportingIT.d2_13_d2_14_...`. No operation of this milestone lets an account read a gift, its own included (milestone 3); what is asserted is that a guest gift is linked to no account whatever name or address it carries, and that review is refused to a donor |
| 2.15 | Covered. Browser repeat: joint integration | `ReportingIT.d2_15_...` |
| 2.16 | Covered | `ReportingIT.d2_16_...` |
| 2.17 | Covered, with 20 reports | `ReportingIT.d2_17_...`. Twenty, not one hundred: the site takes sixty reports an hour (12.3). Every public read is byte-identical before and after, with gifts reported and rejected |
| 2.18 | Covered | `ReportingIT.d2_18_...` |
| 2.19 | Covered | `ReportingIT.d2_3_d2_19_...` |

## 3. Private evidence

| # | Status | Executable tests and notes |
|---|---|---|
| 3.1 | Covered. Browser repeat: joint integration | `EvidenceIT.d3_1_d3_8_...` |
| 3.2 | Covered | `EvidenceIT.d3_2_d3_9_...` |
| 3.3 | Covered | `EvidenceIT.d3_3_d3_12_...`; `internal/EvidenceImagesTest.d3_3_...` (also a truncated file, and one above 40 million pixels) |
| 3.4 | Covered | `EvidenceIT.d3_4_...`: the 413 for a declared length arrives without one byte of the body having been sent; a stream that exceeds the limit is cut off there |
| 3.5 | Covered | `EvidenceIT.d3_5_...` |
| 3.6 | Covered | `EvidenceIT.d3_6_...` |
| 3.7 | Covered | `EvidenceIT.d3_7_...` |
| 3.8 | Covered | `EvidenceIT.d3_1_d3_8_...`; `internal/EvidenceImagesTest.d3_8_...` |
| 3.9 | Covered | `internal/EvidenceImagesTest.d3_9_...` (all eight orientations); `EvidenceIT.d3_2_d3_9_...` |
| 3.10 | Covered | `internal/EvidenceImagesTest.d3_10_...` |
| 3.11 | Covered | `internal/EvidenceBudgetIT.d3_11_...`: both decoders are held by the test, as two pictures being decoded would hold them |
| 3.12 | Server part covered. Browser: joint integration | `EvidenceIT.d3_3_d3_12_...` |
| 3.13 | Covered | `EvidenceIT.d3_13_d8_10_...` |

## 4. Review: confirm, reject, reopen

| # | Status | Executable tests and notes |
|---|---|---|
| 4.1 | Covered. Browser repeat: joint integration | `ReviewIT.d4_1_...` |
| 4.2, 4.3 | Covered. Browser repeat: joint integration | `ReviewIT.d4_2_d4_3_...` |
| 4.4, 4.5 | Covered | `ReviewIT.d4_4_d4_5_...` |
| 4.6 | Covered | `ReviewIT.d4_6_d4_15_...` |
| 4.7 | Covered | `ReviewIT.d4_7_...` |
| 4.8 | Covered | `ReviewIT.d4_8_...` |
| 4.9 | Covered | `ReviewIT.d4_9_...` |
| 4.10, 4.11 | Covered | `ReviewIT.d4_10_d4_11_...` |
| 4.12, 4.13 | Covered. Browser repeat of 4.12: joint integration | `ReviewIT.d4_12_d4_13_...` |
| 4.14 | Covered | `ReviewIT.d4_14_...` |
| 4.15 | Covered | `ReviewIT.d4_6_d4_15_...` |
| 4.16 | Covered | `ReviewIT.d4_16_...` |
| 4.17 | Covered | `ReviewIT.d4_17_...` |
| 4.18 | Covered | `ReviewIT.d4_18_...` |
| 4.19 | Covered | `ReviewIT.d4_19_...`; degrading with retention: `RetentionIT.d4_19_...` |
| 4.20 | Browser only | What the confirm screen says |

## 5. Manual entry

| # | Status | Executable tests and notes |
|---|---|---|
| 5.1 | Covered | `ManualEntryIT.d5_1_aCashGift...`, `d5_1_theSameEntrySentTwice...` |
| 5.2, 5.3 | Covered | `ManualEntryIT.d5_2_d5_3_...` |
| 5.4 | Covered | `ManualEntryIT.d5_4_d6_10_...` |
| 5.5 | Covered | `ManualEntryIT.d5_5_...` |
| 5.6 | Covered | `ManualEntryIT.d5_6_...` |

## 6. Corrections

| # | Status | Executable tests and notes |
|---|---|---|
| 6.1, 6.2 | Covered | `CorrectionsIT.d6_1_d6_2_...` |
| 6.3 | Covered | `CorrectionsIT.d6_3_...` |
| 6.4 | Covered | `CorrectionsIT.d6_4_d6_7_...` |
| 6.5 | Covered | `CorrectionsIT.d6_5_...` |
| 6.6 | Covered | `CorrectionsIT.d6_6_...` |
| 6.7 | Covered | `CorrectionsIT.d6_4_d6_7_...` |
| 6.8 | Covered | `CorrectionsIT.d6_8_...` |
| 6.9 | Covered | `CorrectionsIT.d6_9_...` |
| 6.10 | Covered | `ManualEntryIT.d5_4_d6_10_...` |
| 6.11 | Covered | `CorrectionsIT.d6_11_...` |
| 6.12 | Covered | `CorrectionsIT.d6_1_d6_2_...`, `d6_12_...` |

## 7. Tracking and what the donor is told

| # | Status | Executable tests and notes |
|---|---|---|
| 7.1 to 7.3 | Covered. Browser repeat of 7.1: joint integration | `TrackingIT.d7_1_d7_2_d7_3_...`; after a correction: `CorrectionsIT.d9_3_...` |
| 7.4 | Covered, except timing | `TrackingIT.d7_4_...`: the same status and body for a wrong code, a malformed one and an unknown reference. The same work is done for each (one keyed hash, one lookup by reference); a timing measurement over many runs, as for sign-in, is not made |
| 7.5 | Covered | `TrackingIT.d7_5_...` |
| 7.6 | Covered | `TrackingIT.d7_6_d7_8_...` |
| 7.7 | Browser only | The wording of the receipt |
| 7.8 | Covered | `TrackingIT.d7_6_d7_8_...`. That no access log holds the code follows from its being in a body: `AuditAndPrivacyIT` searches the whole console at TRACE |

## 8. Permissions, order of checks and headers

| # | Status | Executable tests and notes |
|---|---|---|
| 8.1 | Covered | `PermissionsAndOrderIT.d8_1_...`: all eleven review operations, as a donor and as an administrator, for an existing, an unknown and a malformed identifier, with identical answers |
| 8.2 | Covered | `PermissionsAndOrderIT.d8_2_...` |
| 8.3 | Covered | `PermissionsAndOrderIT.d8_3_d8_4_d8_5_...`; `identity/AuthorizationAndHeadersIT.a5_3_...` |
| 8.4, 8.5 | Covered | `PermissionsAndOrderIT.d8_3_d8_4_d8_5_...` |
| 8.6 | Covered | `PermissionsAndOrderIT.d8_6_...` |
| 8.7 | Covered | `PermissionsAndOrderIT.d8_7_d13_3_...` |
| 8.8 | Covered in one direction | `PermissionsAndOrderIT.d8_8_...`: a confirmation that begins while the disablement holds the account row waits, and is refused once it commits. The other direction (the confirmation first, the disablement waiting for it) is the same lock and is not separately staged |
| 8.9 | Covered | Applied to every answer of every donation test: `DonationIntegration.everyDonationAnswerMatchesTheContract` |
| 8.10 | Covered | `EvidenceIT.d3_13_d8_10_...` |

## 9. Rate limits and abuse

| # | Status | Executable tests and notes |
|---|---|---|
| 9.1 | Covered | `LimitsIT.d9_1_...` (reports, methods, uploads, staff reads and writes); `TrackingIT.d9_1_...` (status lookups) |
| 9.2 | Covered | `BrakesIT.d9_2_d9_8_...`, with the brake configured at three an hour |
| 9.3 | Covered | `BrakesIT.d9_3_...`, with the queue configured at five |
| 9.4 | Covered | `EvidenceIT.d9_4_...` |
| 9.5 | Covered | `LimitsIT.d9_5_...` |
| 9.6 | Covered, except timing | `LimitsIT.d9_6_...` |
| 9.7 | Covered | `LimitsIT.d9_7_...` |
| 9.8 | Covered | `BrakesIT.d9_2_d9_8_...`, `d9_3_...`, `EvidenceIT.d9_4_...`, `internal/EvidenceBudgetIT` |
| 9.9 | Server part covered | `PaymentMethodsIT.d1_1_...` asserts the unchanged content policy header. That the frontend loads no third-party script is the frontend's |

## 10. The general fund on a real database

| # | Status | Executable tests and notes |
|---|---|---|
| 10.1, 10.2, 10.3, 10.5 | Covered | `ProductionProfileIT.d10_...` and `aFreshDatabaseHoldsNoFictionalDataOnlyTheSeededSettings`, on a database never marked disposable. 10.3 runs the migration's own guarded statement again |
| 10.4 | Covered | `FundIT.d10_4_...`; `fixtures/FixtureSafetyIT` |
| 10.6 | Covered | `FundIT.d10_6_d10_7_...` |
| 10.7 | Covered | `FundIT.d10_6_d10_7_...`, `ProductionProfileIT.d10_...`. The existing guard covers code and kind; migration V7 adds that the row is never deleted |

## 11. Audit and privacy

| # | Status | Executable tests and notes |
|---|---|---|
| 11.1 | Covered | `AuditAndPrivacyIT`: every event of a full journey is present and carries keys from the allowlist only; each area's test asserts its own events' fields |
| 11.2, 11.3 | Covered | `AuditAndPrivacyIT`: the console at TRACE, the audit log, and the tables that remember and count requests are searched for every private value of the journey |
| 11.4 | Covered | `EvidenceIT.d3_13_d8_10_...` |
| 11.5 | Covered | `ListingIT.d11_5_...` |
| 11.6 | Covered | `ListingIT.d11_6_...` |

## 12. Retention

| # | Status | Executable tests and notes |
|---|---|---|
| 12.1 | Covered | `RetentionIT.d12_1_d12_5_...` |
| 12.2, 12.3 | Covered | `RetentionIT.d12_2_d12_3_...` |
| 12.4 | Covered | `RetentionIT.d12_4_...` |
| 12.5 | Covered | `RetentionIT.d12_1_d12_5_...`, `d12_2_d12_3_...` |

## 13. Nothing accepted earlier changed

| # | Status | Executable tests and notes |
|---|---|---|
| 13.1 | Covered | The milestone 0 and 1 suites pass unchanged (`api/*IT`, `ledger/LedgerRulesIT`); with gifts in every state: `ReportingIT.d2_17_...` |
| 13.2 | Covered | The identity suite passes. Three of its tests were adjusted for what this milestone changes on purpose, and nothing else: `a5_2` now also accepts the fifteen declared donation routes; `a5_3` and the permission unit test expect `donations.confirm` for a super administrator (6.1); `RealDatabaseIT.a12_2` names the ledger together with the gift tables that now reference it, and adds those tables |
| 13.3 | Covered | `PermissionsAndOrderIT.d8_7_d13_3_...`; `identity/PrecedenceIT` unchanged |
| 13.4 | Covered | `api/DevProfileHttpIT`, `identity/DevProfileIdentityClockIT`, `fixtures/*IT` |
| 13.5 | CI | No file under `contract/` is changed by this milestone; `npm run validate` runs in the contract workflow |
