# Donation lifecycle acceptance tests for milestone 2B

The tests the donation implementation must pass before it is accepted. Each is an observable behaviour, so the backend implements it against real PostgreSQL and the browser suite repeats the marked ones end to end. It mirrors `security-acceptance.md`; the rules are in `../donation-lifecycle.md` ("DL"). Nothing here is implemented yet.

Where they run:

- **API**: the whole application over HTTP against real PostgreSQL (Testcontainers), with a cookie jar per simulated browser.
- **Browser**: a real browser driving the real frontend against the real backend. Marked **B**.
- **Unit**: no database.

"Same answer" means the same status, the same body apart from `requestId`, and no timing difference outside normal noise. A gift's identifiers are written as in DL 1.3.

These tests depend on owner decisions (`../M2A-review.md`). Where a scenario rests on a decision, its row names it; if the owner chooses another option, the scenario changes with it.

## 1. Payment methods

| # | Test | Expected | B |
|---|---|---|---|
| 1.1 | `GET /giving/methods` with active methods | 200, methods in `position` order, each with its current details version, both languages, `Cache-Control: no-store` | B |
| 1.2 | `GET /giving/methods` with no active method | 200, `methods: []`; the frontend shows that giving is not open and no report form | B |
| 1.3 | A method with a superseded details version | Only the current version is returned | |
| 1.4 | `GET /giving/methods` carries no account data | No forbidden property; valid against `PaymentMethodList`; no private marker | |
| 1.5 | A `link` recipient value | An `https` URL without credentials, at most 200 chars; the frontend opens it only on a press, in a new tab, `rel=noopener noreferrer`; never auto-followed | B |
| 1.6 | A `text` recipient value rendered | Shown as text with a copy button, never as HTML | B |
| 1.7 | `payment-method-publish-details` then read | The new version is current; the previous is superseded at one instant; one current version at all times | |
| 1.8 | `payment-method-activate` in production without the eligibility attestation (decision 4) | Refused; the method stays inactive | |
| 1.9 | `payment-method-activate` with the attestation | Active; the attestation is stored and audited | |
| 1.10 | Every payment-method command | Writes an audit record naming the operating-system user; restricted to the operator command, no network route in M2 (decision 6) | |
| 1.11 | `GET /giving/methods` with an unsupported method (`POST`) | 405 with `Allow` | |

## 2. Reporting a gift

| # | Test | Expected | B |
|---|---|---|---|
| 2.1 | Report a valid gift to a case, no account | 201 with reference, tracking code and upload token; state `reported`; nothing public changes | B |
| 2.2 | Report to `FUND` once the fund exists | 201 | |
| 2.3 | Report with no CSRF token | 403 `csrf_invalid`; nothing recorded | |
| 2.4 | Report with `amount` 0, and above 10,000,000,000 | 400 `out_of_range` both | |
| 2.5 | Report naming an unknown case, and a draft case | 400, field `caseCode`, `invalid_value`, identical for both | |
| 2.6 | Report on a case not accepting gifts | 409 `case_not_accepting`; nothing recorded | |
| 2.7 | Report naming details superseded less than 24 h ago | 201; the detail is marked superseded on the review screen | |
| 2.8 | Report naming details superseded 24 h ago or more | 409 `payment_details_changed`; nothing recorded | |
| 2.9 | Report with a body over 16 KiB; wrong `Content-Type`; an unknown property | 413; 415; 400 `unknown_field` | |
| 2.10 | Report whose `payerName` or another text field is a payment card number (valid check digit) | 400 `card_number`; not stored, not logged | |
| 2.11 | Report with a leading or trailing space in a text field | 400 `invalid_format` | |
| 2.12 | `paidOn` more than 90 days ago, and more than one day in the future | 400 `out_of_range` both | |
| 2.13 | Report while fully signed in | 201; the gift is linked to the account (visible only in the review detail's `linkedAccount`) | |
| 2.14 | Report while signed in; then another account reads it | A guest gift is never found by another account; linkage is by session only | |
| 2.15 | Two reports with the same `Idempotency-Key` and body | One gift; the second returns the first answer, same tracking code | B |
| 2.16 | Same `Idempotency-Key`, different body | 409 `idempotency_key_reused`; one gift | |
| 2.17 | 100 reports then count public totals and lists | Every public read response is byte-identical apart from `asOf`/`requestId` to before (DL 9.2) | |
| 2.18 | `display.mode = named` without `publicName` | 400 `required` | |
| 2.19 | Report from a cross-site context (`Origin` not the site) | 403 `forbidden` | |

## 3. Private evidence

| # | Test | Expected | B |
|---|---|---|---|
| 3.1 | Upload a JPEG within the limits with a valid token | 204; the gift shows evidence attached | B |
| 3.2 | Upload a PNG | 204 | |
| 3.3 | Upload a GIF, a PDF, or a text file renamed `.png` | 400 `evidence_invalid`; nothing stored | |
| 3.4 | Upload declaring 6 MiB, and a body that exceeds 5 MiB mid-stream | 413; the body is not read past the limit | |
| 3.5 | Upload with `Content-Type: application/pdf` | 415 | |
| 3.6 | Upload with a wrong, expired, or already-used token, or whose gift is no longer `reported` | 400 `evidence_token_invalid`, identical for all | |
| 3.7 | Two uploads with the same token at once | Exactly one 204; the other `evidence_token_invalid` | |
| 3.8 | A stored screenshot compared with the uploaded bytes | Different: re-encoded, scaled to ≤ 2,400 px on the long side; the original bytes are never on disk | |
| 3.9 | A JPEG carrying GPS and camera EXIF | The stored copy has none; orientation is applied to the pixels | |
| 3.10 | A JPEG with trailing non-image bytes appended | The stored copy is a clean picture; the trailer is gone | |
| 3.11 | Decode budget: more concurrent uploads than the limit | 503 with `Retry-After`; no partial file kept | |
| 3.12 | A failed upload | The gift stays `reported` without evidence; the frontend says the gift is recorded and the screenshot is not | B |
| 3.13 | No public route serves evidence | No `/media/public/` token exists for it; a guess answers 404 | |

## 4. Review: confirm, reject, reopen

| # | Test | Expected | B |
|---|---|---|---|
| 4.1 | A super administrator lists `status=reported` | Oldest first; `pendingCount` correct | B |
| 4.2 | Confirm a reported gift for the reported case and amount | 200; one `donation` ledger entry on that case for that amount dated now; totals change at that instant; state `confirmed` | B |
| 4.3 | Public totals before and after 4.2 | Unchanged before; increased by the confirmed amount after | B |
| 4.4 | Confirm for a different case with a `note` (decision 10) | 200; the report keeps the reported case; the ledger entry is on the confirmed case; the status page shows both | |
| 4.5 | Confirm for a different case without a `note` | 400 `required` on `note` | |
| 4.6 | Confirm a gift that is not `reported` | 409 `donation_state_conflict` | |
| 4.7 | Two confirmations of one gift at once | Exactly one 200, one ledger entry; the other 409 `donation_state_conflict` | |
| 4.8 | Confirm with a `providerReference` already recorded for another confirmed gift of the same method | 409 `duplicate_payment_reference`; nothing written | |
| 4.9 | Confirm for a draft case | 400 `invalid_value` | |
| 4.10 | Confirm with `listing=anonymous` for a gift reported as named | Listed anonymously; the name is never shown | |
| 4.11 | Confirm a gift reported as hidden with `listing=as_requested` | Counted, not listed | |
| 4.12 | Reject a reported gift | 200; nothing public changes; the status page shows the reason, never the note | B |
| 4.13 | Reject with reason `other` and no note | 400 `required` | |
| 4.14 | Reopen a rejected gift | 200; back to `reported`; the rejection stays in the history | |
| 4.15 | Reopen a confirmed gift | 409 `donation_state_conflict` | |
| 4.16 | Confirm brings `received` to the goal | The case becomes `funded` in the same transaction (decision 11) | |
| 4.17 | Confirmation that would pass the platform bound | 409 `platform_limit_reached`; nothing written | |
| 4.18 | Confirm, with the same `Idempotency-Key` and body sent twice | One ledger entry; the second returns the first answer | |
| 4.19 | The review detail shows possible duplicates | Other gifts, same method, amount and payer name, within 7 days, are listed as hints; they decide nothing | |
| 4.20 | The confirm screen states the duty to verify receipt, and that a screenshot is not that | Present | B |

## 5. Manual entry

| # | Test | Expected |
|---|---|---|
| 5.1 | Enter a cash gift | 201; confirmed at once; one `donation` entry; origin `staff_entry`; no reported amount, no tracking code |
| 5.2 | Enter a gift naming both a method id and a free description, or neither | 400 |
| 5.3 | Enter without a `note` | 400 `required` |
| 5.4 | Enter with `reentryOf` naming a fully reversed gift (DL 8.3) | 201; the original shows `reenteredAs`; one payment is counted once |
| 5.5 | `reentryOf` naming a gift that still stands, was refunded, or is already re-entered | 409 `reentry_not_allowed` |
| 5.6 | Manual entry by an account without `donations.confirm` | 403; the gift identifier of `reentryOf` is never looked at |

## 6. Corrections

| # | Test | Expected |
|---|---|---|
| 6.1 | Reversal of part of a confirmed gift | 200; one `donation_reversal` entry; `netAmount` reduced; no money implied |
| 6.2 | Refund of part of a confirmed gift | 200; one `refund` entry; `netAmount` reduced |
| 6.3 | A reversal and a refund are separate operations with different required fields | Neither form can be submitted as the other |
| 6.4 | Correction larger than what still stands | 409 `correction_exceeds_remaining` |
| 6.5 | Two corrections at once that together exceed the remaining | Exactly one passes; the sum never exceeds the confirmed amount (checked under lock) |
| 6.6 | Refund with a `providerReference` already recorded against this gift | 409 `duplicate_payment_reference` |
| 6.7 | Correct a gift that is not confirmed | 409 `donation_state_conflict` |
| 6.8 | Correct a correction | No endpoint allows it; repaired only by a new movement |
| 6.9 | A refund that makes the case `available` negative | Written; `hasShortfall` true; the entry shows (financial-terms section 6) |
| 6.10 | Full reversal then re-entry for the right amount | One payment counted once before and after; the donor's tracking page follows the re-entry |
| 6.11 | Correction with a repeated `Idempotency-Key` and body | One entry; the second returns the first answer |
| 6.12 | The donor listing after corrections | Shows `netAmount`; disappears at zero; the ledger keeps the gift and its corrections |

## 7. Tracking and what the donor is told

| # | Test | Expected | B |
|---|---|---|---|
| 7.1 | `POST /donations/status` with a valid tracking code, gift `reported` | 200; state `reported`; no name, contact, payment reference or note | B |
| 7.2 | Status of a confirmed gift | Confirmed amount and case, `netAmount`; still no personal detail | |
| 7.3 | Status of a rejected gift | The reason only | |
| 7.4 | A wrong code, a malformed code, an unknown reference | 404 `donation_not_found`, in the same time | |
| 7.5 | Ten wrong codes for one reference | 429 for that reference for 15 minutes, whatever the code (DL 12.2) | |
| 7.6 | The tracking code appears only on the receipt | It is never returned again, and no staff route reveals it | |
| 7.7 | The receipt text | States the gift is not confirmed, the organisers verify by hand, nothing is emailed, keep the code (decision 2) | B |
| 7.8 | The status is a `POST` | The tracking code never appears in an address or a server access log | |

## 8. Permissions, order of checks and headers

| # | Test | Expected |
|---|---|---|
| 8.1 | Every `/admin/donations` operation as a donor, and as an administrator (not super admin) | 403 `forbidden`; the gift identifier is never looked at (decision 8) |
| 8.2 | Every `/admin/donations` operation signed out | A `GET` 401 `unauthenticated`; a `POST` with a stale token 403 `csrf_invalid` |
| 8.3 | `GET /me` as a super administrator | `permissions` includes `donations.confirm` |
| 8.4 | A pending (`mfa_required`) session on `/admin/donations` | 401 with the state's code |
| 8.5 | A pending session reporting a gift | Treated as a guest: 201 |
| 8.6 | An admin operation with a malformed `donationId` | 400 at step 7, not 404 at step 1 |
| 8.7 | A request refused before step 8 | No `Idempotency-Key` consumed, nothing written |
| 8.8 | A super administrator disabled while a confirmation is in flight | The confirmation commits before the change or is refused; never after (DL 11.3) |
| 8.9 | Every answer of every operation here, at every status | `Cache-Control: no-store`, no `ETag`; 405 has `Allow`; 429 and 503 have `Retry-After` |
| 8.10 | The evidence response headers | `no-store`, `nosniff`, `Content-Disposition: inline`, `Content-Security-Policy: default-src 'none'; sandbox` |

## 9. Rate limits and abuse

| # | Test | Expected |
|---|---|---|
| 9.1 | More reports than the per-address limit in the window | 429 until the window ends, at most 60 minutes (DL 12.2) |
| 9.2 | More than the site-wide reports-per-hour brake | 503 `reporting_paused`; nothing recorded (DL 12.3) |
| 9.3 | More gifts waiting than the queue brake | 503 `reporting_paused` until the queue falls |
| 9.4 | Evidence storage past the total brake | 503 on uploads; reports still accepted |
| 9.5 | Each counter is advanced before the work is done | Parallel requests cannot all slip under a limit |
| 9.6 | A throttled report, existing and unknown references alike | Same answer and timing |
| 9.7 | Counters store keyed hashes, not the address or the reference | Verified in the stored rows |
| 9.8 | A brake starting to hold | One audit record is written |
| 9.9 | No third-party bot or CAPTCHA script is loaded | The content policy of I&S 11 is unchanged (DL 12.4) |

## 10. The general fund on a real database

| # | Test | Expected |
|---|---|---|
| 10.1 | A real (never-disposable) database after the M2B migration | Exactly one case of kind `fund`, code `FUND`, status `active`, accepting gifts, no ledger entry |
| 10.2 | `GET /cases/FUND` on that database | 200 with zero totals, instead of the former 404 |
| 10.3 | The migration run again (a database that already has the fund) | Nothing inserted; still one fund |
| 10.4 | A disposable database loaded from a fixture | The fixture's fund, not the migration's; the loader never leaves two |
| 10.5 | The fund's English and French name and description | Exactly the approved text (decision 7) |
| 10.6 | A report and a confirmation to `FUND` | Allowed; the fund's `externalDonations` rises; it never records a `disbursement` (financial-terms section 11) |
| 10.7 | The fund is never deleted and never changes code or kind | Enforced by the existing case guard |

## 11. Audit and privacy

| # | Test | Expected |
|---|---|---|
| 11.1 | Every event of DL 13 | One record with the listed fields, from an allowlist |
| 11.2 | No log or audit record anywhere holds a payer name, contact, payment reference, public name, note, tracking code, upload token, idempotency key, or screenshot content | Verified with verbose logging, as the identity leak test does |
| 11.3 | Request bodies of these operations are never logged | Verified |
| 11.4 | Reading a screenshot | Writes an audit record with the actor and the gift |
| 11.5 | A confirmed gift's public donor listing | Carries only what media-and-privacy section 6 allows; no picture for a guest |
| 11.6 | Restricting a listing to anonymous or hidden | Shows less from the next request; no total or ledger entry changes; audited |

## 12. Retention (decision 15)

| # | Test | Expected |
|---|---|---|
| 12.1 | A screenshot 30 days after the gift is decided | Deleted by the daily task; the gift keeps that one existed and when it was deleted |
| 12.2 | Private details after their period | Erased; amounts, cases, dates, ledger and audit remain |
| 12.3 | The tracking code after erasure | Still returns the status, which never showed private details |
| 12.4 | Reopening a rejected gift | Restarts its retention period |
| 12.5 | Each deletion or erasure | Writes an audit record |

## 13. Nothing accepted earlier changed

| # | Test | Expected |
|---|---|---|
| 13.1 | The seven public read endpoints, with and without gifts in every state | Unchanged responses, status, caching and headers from milestone 1 |
| 13.2 | The twelve identity operations | Unchanged |
| 13.3 | The order of checks (I&S 4.3) | Unchanged; nothing inserted before or between its steps |
| 13.4 | A fixture-loaded (disposable) database | Fixtures and the development seed still load; no donation table is touched by the identity loader's list |
| 13.5 | Contract validation | `npm run validate` passes; generated types match; no public schema gained a private property |
