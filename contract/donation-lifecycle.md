# Donation lifecycle contract

Rules behind the giving and donation review endpoints of `openapi.yaml` (version 0.4.0): reporting a gift, private evidence, review, manual entry, corrections, payment methods and the general fund on a real database. The frontend and the backend each implement against this document in milestone 2B. Nothing here is implemented yet.

Status: proposed in milestone 2A. Not reviewed, not accepted. The owner decisions it depends on are listed in `M2A-review.md`; until they are answered, every rule marked **[Proposed]** is a recommendation, written out in full so it can be judged and, if approved, implemented as it stands.

Rule labels:

- **[Approved]** decided by the project owner (`../docs/decisions.md`), or a rule of an accepted contract (`financial-terms.md`, `media-and-privacy.md`, `identity-and-security.md`, `../docs/adr/0001-backend-architecture.md`). Restated here, not changed.
- **[M2A design]** technical design proposed here, to be reviewed, then implemented and verified in milestone 2B.
- **[Proposed, decision N]** depends on owner decision N of `M2A-review.md`. The text describes the recommended option.

"I&S" is `identity-and-security.md`. "Gift" and "donation" mean the same thing; the API says `donation`.

The site records money that moved **outside** it. It never processes a payment and never asks for, accepts or stores a card number, bank credentials, a payment password, a one-time code or Apple account credentials. **[Approved]** Nothing in this contract adds a payment provider, a payment page or a payment software kit.

## 1. What a gift is

### 1.1 One record, three states

A gift is one record from the moment it is reported or entered until for ever. It is never deleted.

| State | Meaning | Public effect |
|---|---|---|
| `reported` | A donor said they paid. Nobody has verified it. | None at all. No total, no count, no list, no ledger entry. **[Approved]** |
| `confirmed` | A super administrator verified in the payment account that the money was received. | One `donation` ledger entry; totals and, by the donor's choice, the donor list change at that instant. |
| `rejected` | A super administrator decided the report cannot be confirmed. | None. |

| From | To | By | Rule |
|---|---|---|---|
| (nothing) | `reported` | Anyone, no account needed | Section 3 |
| (nothing) | `confirmed` | Super administrator, manual entry | Section 7 |
| `reported` | `confirmed` | Super administrator | Section 6.3 |
| `reported` | `rejected` | Super administrator | Section 6.4 |
| `rejected` | `reported` | Super administrator, reopening | Section 6.5 |
| `confirmed` | (stays `confirmed`) | | Never undone. Mistakes and returned money are ledger corrections (section 8). |

Every transition is one conditional update of the state under a lock on the gift's row, in the same database transaction as everything it causes. Of two simultaneous decisions on one gift exactly one succeeds; the other answers 409 `donation_state_conflict` and changed nothing. **[M2A design]**

### 1.2 Reported and confirmed values are separate

**[Approved]** The reported amount and the confirmed amount are stored separately. The same holds for the case. **[M2A design]**

| | Written by | Changes afterwards |
|---|---|---|
| Reported amount, reported case, date paid, method and recipient details shown | The donor, at the report | Never |
| Confirmed amount, confirmed case, date received, provider reference | The super administrator, at confirmation | Never. A wrong confirmation is repaired by a correction (section 8). |

A confirmation never copies the reported values by default: the super administrator states the amount and the case they are confirming, every time (6.3). Public pages show confirmed values only.

### 1.3 Identifiers

| Identifier | Form | Who sees it | Secret |
|---|---|---|---|
| Gift identifier | `don_` and 22 random characters | Staff, in review screens and addresses | No, but never public |
| Reference | 8 characters, written `XXXX-XXXX` | The donor and staff. What a donor quotes when contacting the organisers. | No |
| Tracking code | 20 characters, written `XXXX-XXXX-XXXX-XXXX-XXXX`; its first 8 are the reference | The donor only, shown once | Yes (section 5) |
| Listing identifier | Opaque, as in milestone 0 | Public, in the donor list | No |
| Ledger entry identifier | As in milestone 0 | Public | No |

The public listing identifier and ledger entry identifier are unrelated to the gift identifier and the reference: nothing public can be matched to a report, and no reference can be looked up in a public response. **[M2A design]**

## 2. Payment methods

### 2.1 Records, not code

**[Approved]** Payment methods are records. Adding one that uses the same pay-outside-then-report flow needs no code change, migration or redeployment. Expected methods are Zelle, PayPal, Cash App and Apple Cash (person-to-person transfers, not Apple Pay). Which of them exist at launch is **owner decision 5**.

| Part | Content | Versioned |
|---|---|---|
| Method | Stable identifier (`zelle`, `paypal`, ...), English and French name, display order, active flag, the eligibility attestation of 2.5 | No. Changes are audited. |
| Details version | English and French instructions, and one to six recipient fields, each with an English and French label, a value and a format (`text` or `link`) | Yes. Never edited: a change publishes a new version. |

- A method identifier never changes and is never reused. A method that was ever shown to a donor is never deleted, only deactivated. **[M2A design]**, the same rule as categories.
- Each method has exactly one current details version while it is active. Publishing a new version makes the previous one superseded at that instant, in one transaction.
- **[Approved]** A gift keeps a reference to the method and to the details version that was shown when it was reported. The review screen shows those details as they were, and says when they have been superseded.
- The frontend keeps its own look for the identifiers it knows and uses a generic look for any other, as it does for categories.

A `link` value is an `https` address without user name or password, at most 200 characters. The frontend shows where it leads, opens it only when the donor presses it, in a new tab, with `rel="noopener noreferrer"`, and never follows it by itself. A `text` value is shown with a copy button. No value is ever rendered as HTML.

### 2.2 What a visitor reads

`GET /giving/methods` returns the active methods in display order, each with its current details version. No account and no session are needed.

- Recipient details are public by nature: a donor must see them before paying. They are the organisers' own payment handles, published on purpose (**owner decision 5** asks the owner to choose handles they accept to publish).
- The response is `Cache-Control: no-store`. A cached copy of superseded recipient details would send money to the wrong place, so no cache may keep one. **[M2A design]**
- An empty list means giving is not open on this site yet. The frontend then explains that gifts cannot be reported at the moment; it does not show a report form. `Case.acceptingDonations` keeps its milestone 0 meaning and does not change because no method exists.
- The request is rate-limited by client address (section 12).

### 2.3 "Other"

**[Approved]** "Other" is not a payment method record. The frontend always offers it after the listed methods; it leads to the contact page and creates no report: no request of this contract is sent. What that page says until the contact form exists is **owner decision 13**.

### 2.4 Details that changed while the donor was paying

A donor reads the details, leaves for their payment application, pays, comes back and reports. The details may have been replaced in between. **[M2A design]**

| The report names a details version that is | Answer |
|---|---|
| Current, of an active method | Accepted |
| Superseded, or of a method deactivated, less than 24 hours ago | Accepted. The review screen marks it "details superseded". |
| Superseded or deactivated 24 hours ago or more | 409 `payment_details_changed`. Nothing is recorded. The frontend reloads the methods, shows the current details, and says: if you already paid using the earlier details, contact the organisers. |
| Unknown | 400, field `paymentDetailsId`, `invalid_value` |

### 2.5 The minimum for milestone 2, and who manages methods until milestone 5

**[Approved]** Every change to payment configuration is restricted to super administrators and written to the audit log. Browser screens for it belong to milestone 5 (`payment_methods.manage`).

**[Proposed, decision 6]** Until then, payment methods are managed with the operator command, like staff accounts (I&S 8.1): on the server, by someone who already has access to it, prompting on the terminal, never over the network.

| Command | Effect |
|---|---|
| `payment-method-create` | Creates a method, inactive, with its identifier, names and position. |
| `payment-method-publish-details` | Reads a details definition (the instructions and fields of 2.1) from a file it asks for, shows it next to the current version, and publishes it as a new version after the operator types the method identifier to confirm. |
| `payment-method-activate` | Activates a method that has a details version. In production it first asks for the eligibility attestation below. |
| `payment-method-deactivate` | Deactivates. Gifts already reported keep their reference. |
| `payment-method-list` | Methods, versions and attestations. |

What milestone 2 must contain so that gifts can be reported before those screens exist: the two tables of 2.1, the public read of 2.2, these five commands, and their audit events. Nothing else of payment configuration.

There is no network operation that changes a recipient detail in milestone 2. This matters: whoever can change a recipient detail can redirect every future gift, so the first version of that power stays behind server access, the same trust the first super administrator rests on.

**Eligibility.** **[Approved]** Before a method is enabled in production, the provider's eligibility rules, geographic limits and terms are checked; a personal account is not assumed to be permitted for collecting donations. **[Proposed, decision 4]** The site cannot check a provider's terms. It records that a person did: `payment-method-activate` asks who checked, on which date, and what was checked (free text), stores the answers with the method and writes them to the audit log. Outside the `dev` and `test` profiles a method cannot be activated without them. This is an attestation by the operator, not proof.

## 3. Reporting a gift

### 3.1 Who

**[Approved]** No account is required. `POST /donations` accepts every session state. It needs the CSRF token like every `POST`, so the page first calls `GET /auth/csrf`, which gives a visitor the anonymous session of I&S 4.1.

When the session is fully signed in, the gift is linked to that account. In any other state it is a guest gift. **[Approved]** A guest gift is never attached to an account because an email address or a name matches. Showing a signed-in donor their own gifts (`donations.read_own`) belongs to milestone 3.

### 3.2 The steps the donor sees

1. Choose a case that accepts gifts, or the general fund.
2. Choose a payment method. Its instructions and recipient details are shown.
3. Pay outside the site.
4. Come back and report: amount, the date paid, the name the payment was sent from, how to appear publicly, and optionally a reference, a way to be reached and a screenshot.
5. Read the receipt: the reference, the tracking code, and what happens next (section 5).

The report form says, before anything is typed: the site never asks for a card number, a bank password or a code; the screenshot is optional; and the gift appears publicly only after it has been verified.

### 3.3 The report

| Field | Rule |
|---|---|
| `caseCode` | A public case or `FUND`, matched case-insensitively. It must be accepting gifts when the report is made (`Case.acceptingDonations`): otherwise 409 `case_not_accepting`. An unknown code and a draft are answered alike: 400, field `caseCode`, `invalid_value`. |
| `amount` | Integer US cents, 1 to 10,000,000,000 (`financial-terms.md` section 5). What the donor says they sent. |
| `paymentDetailsId` | The details version the page showed (2.4). It also names the method. |
| `paidOn` | The calendar day the donor paid, `YYYY-MM-DD`. From 90 days before today to one day after today in the organization timezone; the extra day is for donors east of it. |
| `payerName` | The name the payment was sent from, as the recipient will see it in their payment application. 2 to 80 characters. Private. It is what the super administrator matches the payment with. |
| `paymentReference` | Optional. A confirmation number or the handle the payment came from. 1 to 64 characters. Private. |
| `contact` | Optional. An email address or a phone number where the organisers may reach the donor about this gift. 3 to 120 characters. Private. The site sends nothing to it. **[Proposed, decision 3]** |
| `display` | How the gift appears publicly once confirmed (3.4). Required: there is no default on the server. |

Text fields: Unicode NFC; no control characters; no leading or trailing white space (the frontend trims, the server refuses with `invalid_format`, as for email addresses). A text value that is a payment card number (13 to 19 digits once spaces and dashes are removed, with a valid check digit) is refused with field code `card_number` and is not stored or logged: the site never holds one. **[M2A design]**

The body is JSON, at most 16 KiB, and an unknown property is refused with `unknown_field` (I&S 9.3). A report cannot carry a state, a confirmed amount, an account, a permission or an identifier of its own.

### 3.4 How the donor appears

**[Approved]** by `media-and-privacy.md` section 6; **[Proposed, decision 9]** for guests.

| `display.mode` | Meaning once confirmed |
|---|---|
| `named` | Listed with `display.publicName` (2 to 60 characters), a name the donor typed and agreed to show. Choosing this is the donor's consent to show that name; the choice and its instant are the record of it. No picture: a guest has none, and a picture is a separate consent. |
| `anonymous` | Listed with no name. |
| `hidden` | Counted in totals, not listed. |

The frontend preselects `anonymous`. The name is not public before confirmation, and the super administrator can refuse to show it (6.3). Being named can be withdrawn later (8.4).

### 3.5 The receipt

201 with the reference, the tracking code (section 5), the reported amount and case, the instant of the report, and the evidence upload capability (4.2). The state is `reported`.

Nothing public changes. **[Approved]**

### 3.6 Sent twice, and lost answers

Every report carries an `Idempotency-Key` header: 22 to 64 characters from `A-Z a-z 0-9 _ -`, generated at random by the page when the form is opened and kept in memory with the form. **[M2A design]**

| Request | Answer |
|---|---|
| A key not seen in the last 24 hours | The report is recorded. |
| The same key and the same body again | The first answer again, exactly: same reference, same tracking code, same upload capability with its original expiry. One gift exists. |
| The same key with a different body | 409 `idempotency_key_reused`. Nothing changes. |

So a double tap, a retry after a timeout, and an answer lost on a weak connection never create a second gift, and the donor still receives the tracking code. The frontend resends a report only with the same key and the same body, and only when the person asks; after 403 `csrf_invalid` it follows I&S 9.1 and resends nothing by itself.

To give the same answer twice without storing a secret, the tracking code and the upload token are derived from a random value stored with the gift and a key that is not in the database (HMAC-SHA-256 under the existing hash key, with a different label for each). The database stores only what is needed to recompute and to look up; a copy of the database alone yields neither.

## 4. Private evidence

**[Approved]** A payment screenshot is optional. When given, it is stored privately and is supporting evidence, not proof of receipt. It never confirms a gift by itself, automatically or otherwise. It is never public: it has no public token and no address under `/media/public/` (`media-and-privacy.md` section 2).

### 4.1 What is accepted

**[Proposed, decision 12]**

| Limit | Value |
|---|---|
| Formats | JPEG and PNG, decided from the content of the file, not from its name or declared type |
| Size as sent | At most 5 MiB (5,242,880 bytes) |
| Picture size | At most 40 million pixels |
| Per gift | One, attached once. No replacement. |
| When | Within 30 minutes of the report, while the gift is `reported` |

No PDF, no HEIC, no animated picture, no archive. A phone's screenshot is a PNG, and a browser sends a photo as JPEG when the form asks for these two types.

### 4.2 How it is sent

Reporting and uploading are two requests, so that a failed upload on a weak connection never loses the report. **[M2A design]**

1. The receipt of 3.5 carries `evidenceUpload`: a token and its expiry. The token is bound to that one gift and that one purpose, is kept in the page's memory only, and is never placed in an address or in storage.
2. `POST /donations/evidence` sends the file itself as the body (`Content-Type: image/jpeg` or `image/png`), with the CSRF header and the token in `X-Evidence-Token`. No multipart form, no file name.
3. 204 when stored. The token is spent by one conditional update, so two simultaneous uploads cannot both succeed.

| Refusal | Answer |
|---|---|
| Declared type is neither of the two | 415 |
| Declared length above 5 MiB, or more bytes than that arrive | 413. The body is not read past the limit. |
| The token is wrong, expired, already used, or its gift is no longer `reported` | 400 `evidence_token_invalid`. All four look the same. |
| The content is not a JPEG or PNG that can be decoded within the limits | 400 `evidence_invalid` |
| Too many pictures are being processed, or the storage budget of 12.3 is full | 503 with `Retry-After` |

An upload that fails leaves the gift exactly as it was: reported, without evidence. The frontend says the gift is recorded and the screenshot was not, and offers to try again while the token lasts. It never implies the gift failed.

### 4.3 What is stored

**[M2A design]**, following the storage service of ADR 0001 ("private kinds are served only through authenticated routes").

- **Re-encoded, never as sent.** The server decodes the picture to pixels, scales it so its longer side is at most 2,400 pixels, and writes a new JPEG or PNG from those pixels. Only that file is stored. The bytes that were sent are discarded and never written to disk.
- **Metadata.** Re-encoding from pixels drops everything else the file carried: location, device, dates, colour profiles, text chunks, thumbnails, and anything appended to it. The embedded orientation is applied to the pixels before it is dropped.
- **Malware.** The stored file is produced by the server, so a file built to be two things at once does not survive. No antivirus engine is added in milestone 2. What remains is a fault in the picture decoder itself, bounded by: content sniffing before decoding, the pixel limit, a time limit, at most two decodes at once, and a Java runtime kept up to date.
- **Where.** In the storage service's private area, on the persistent files volume, outside anything the web server or the public picture route can reach, under a random key that is never derived from the gift, the donor or the file. The database holds the key, the format, the dimensions, the size, a SHA-256 of the stored file and the instants of upload and deletion. No file-system path leaves the storage service.
- **Backups** of the files volume contain evidence. Their protection and how long they are kept are deployment matters (milestone 6); the retention of 14.1 is honest only if backups older than it are not kept indefinitely.

### 4.4 Who may read it, and how it is served

- Only a session holding `donations.confirm`, which is the super administrator. No donor, no guest, no administrator and no public route can read it, and the donor cannot read back what they uploaded. **[M2A design]**
- `GET /admin/donations/{donationId}/evidence` streams the stored file after the checks of section 11, with: `Cache-Control: no-store`; the stored content type; `X-Content-Type-Options: nosniff`; `Content-Disposition: inline` with a generated file name; `Content-Security-Policy: default-src 'none'; sandbox`; `Cross-Origin-Resource-Policy: same-origin`.
- Every read is written to the audit log (section 13).
- 404 `evidence_not_found` when the gift has none or it has been deleted.
- The super administrator can delete the evidence of a gift before its time, for a privacy request (`POST /admin/donations/{donationId}/evidence/delete`). The gift keeps the fact that evidence existed and when it was deleted.

## 5. Tracking, and what the donor is told

There is no email infrastructure. The site sends nothing to anyone. **[Approved]** Everything a donor learns, they learn on a page.

### 5.1 Tracking code

**[Proposed, decision 1]** A guest can follow one gift with the tracking code shown on the receipt.

- 20 characters from an alphabet without look-alike letters (digits and upper-case letters except `I`, `L`, `O`, `U`), shown in five groups of four. The first two groups are the reference; the other twelve characters are the secret part: 60 random bits.
- Shown once, on the receipt. The page offers to copy it and tells the donor to keep it: it cannot be shown again, and the organisers cannot read it either. It is kept in memory only: not in an address, not in `localStorage`. If it ever travels in a link it is in the fragment, removed from the address bar before anything else is done (I&S 11).
- `POST /donations/status` with the code returns the state of that one gift. A `POST`, so the code is never in an address or a server log.
- A wrong code, a malformed code and an unknown reference all answer 404 `donation_not_found`, in the same time. Lookups are rate-limited by client address and by reference (section 12).

| The status shows | Never shows |
|---|---|
| Reference, state, case, reported amount, instant of the report | The payer name, the contact detail, the payment reference |
| When decided: the instant, the confirmed amount and case, and what still stands after corrections | The public name, any staff note, who decided |
| When rejected: one of three reasons (`not_received`, `duplicate`, `other`) | The screenshot, or more than whether one is attached |

So someone who obtains a tracking code learns an amount, a case and a state, and nothing about a person.

Losing the code loses tracking, not the gift. A named or anonymous gift also appears in the case's public donor list once confirmed. A donor who lost the code can contact the organisers and quote the reference, the amount and the date.

### 5.2 What the donor is told

**[Proposed, decision 2]** The exact wording, in English and French, is the frontend's and needs the owner's approval. What each screen must say:

| Moment | Must say |
|---|---|
| Before reporting | The site does not take payments. Pay with the method shown, then report here. A gift is listed only after the organisers have verified it. |
| On a case that has reached its goal | Gifts are still welcome; money beyond the goal may be moved to the general fund to help other cases (approved decision on funded cases). |
| Receipt | Your report is recorded. It is not confirmed yet. The organisers check each gift by hand in the payment account; this usually takes a few days. Keep this code to check the status. Nothing will be emailed to you. |
| Status `reported` | Waiting to be verified. |
| Status `confirmed` | Verified on that date, for that amount, for that case. When the amount or the case differs from the report, both are shown. When a correction was made, what still stands is shown. |
| Status `rejected` | Not confirmed, with the reason, and how to contact the organisers. `not_received`: no matching payment was found. `duplicate`: this payment is already recorded in another report. `other`: contact the organisers. |

No screen promises a delay as a guarantee, calls the initiative a charity, or says a gift is tax-deductible.

## 6. Review

### 6.1 Who

**[Proposed, decision 8]** In milestone 2 every review operation needs `donations.confirm`, which only a super administrator holds (I&S 1.2), in a fully signed-in session with its second factor. `donation_reports.read` for administrators stays a planned name and grants nothing yet.

`GET /me` therefore returns `donations.confirm` in `permissions` for a super administrator, in addition to the two of milestone 1. This is the announced way permissions become operational (I&S 1.2).

### 6.2 List and detail

`GET /admin/donations` lists gifts, 12 per page by default and at most 48.

| Filter | Order |
|---|---|
| `status=reported` (default) | Oldest report first: the queue |
| `status=confirmed`, `status=rejected` | Latest decision first |
| `status=all` | Latest report first |

Ties are broken by the gift identifier, compared character by character. `caseCode` narrows to one case (reported or confirmed), `reference` to one reference. Every page carries `pendingCount`, the number of gifts in `reported`.

`GET /admin/donations/{donationId}` returns everything recorded about one gift: what was reported; the recipient details that were shown, with their version and whether they are still current; whether evidence is attached; the decision; the ledger entry and its corrections; the history; and **possible duplicates**: other gifts with the same method and amount whose payer name is equal ignoring case and white space, reported within 7 days of this one. A possible duplicate is a hint for the reviewer. It never refuses or decides anything.

Both are `Cache-Control: no-store`.

### 6.3 Confirm

**[Approved]** The super administrator must verify that the money was actually received before confirming. The screen says so, and says that a screenshot is not that verification.

`POST /admin/donations/{donationId}/confirm`:

| Field | Rule |
|---|---|
| `caseCode` | Required, never prefilled by the server. A published case or `FUND`. Not a draft: no money moves on a draft (`financial-terms.md` section 7). The case does not have to be accepting gifts now: the donor acted when it was, and the money has arrived. |
| `confirmedAmount` | Required, never prefilled by the server. 1 to 10,000,000,000 cents. What was received. |
| `receivedOn` | The calendar day the money arrived, as the payment account shows it. Not after tomorrow, not more than 366 days ago. |
| `providerReference` | Optional. The payment's own identifier in the provider's records. 1 to 64 characters. |
| `listing` | `as_requested`, `anonymous` or `hidden`. `as_requested` applies the donor's choice. The other two show less than the donor asked, for example when the typed name cannot be published. Never more: a gift the donor asked to hide cannot be listed, and a name is never edited. |
| `note` | Optional, private, at most 500 characters. Required when `caseCode` is not the reported case. |

**[Proposed, decision 10]** A gift may be confirmed for another case than the one reported, or for the fund, when the super administrator states why. The report keeps the case the donor chose; the tracking page shows both.

In one transaction, under the lock on the gift:

1. The state goes from `reported` to `confirmed`. Otherwise 409 `donation_state_conflict`.
2. When `providerReference` is given and another confirmed gift of the same payment method already carries it: 409 `duplicate_payment_reference`, naming nothing. One payment is never counted twice.
3. The ledger module writes one `donation` entry on the confirmed case for the confirmed amount, dated at this instant (9.1). A write that would pass the platform bound is refused: 409 `platform_limit_reached`.
4. The donor listing is written according to `listing`.
5. The case's status follows `financial-terms.md` section 9 (9.3).
6. The audit record is written.

Either all of it happens or none of it.

### 6.4 Reject

`POST /admin/donations/{donationId}/reject` with `reason` (`not_received`, `duplicate`, `other`) and a private `note` (required for `other`). From `reported` only. Nothing public changes; no money was ever recorded.

### 6.5 Reopen

`POST /admin/donations/{donationId}/reopen`, no body. From `rejected` back to `reported`, for a payment that arrived after the rejection or a rejection made by mistake. The earlier rejection stays in the history. Rejecting and reopening never touch money, so both are reversible; a confirmation is not.

### 6.6 Sent twice

Confirmation carries an `Idempotency-Key`, as every operation that writes to the ledger does (`financial-terms.md` section 2).

| Request | Answer |
|---|---|
| The same key, by the same account, on the same operation and gift, with the same body, within 7 days | The first answer again. Nothing is written twice. |
| The same key with a different body, gift or operation | 409 `idempotency_key_reused` |

A key belongs to the account that used it: another account's key means nothing. Rejecting and reopening need no key: the state guard already makes a repeat harmless (the second answers 409 `donation_state_conflict`).

## 7. Manual entry

For money that arrived without a report: handed over in person, or sent by someone who will never use the site.

`POST /admin/donations` records a gift that is confirmed at once. It needs `donations.confirm` and an `Idempotency-Key`. **[M2A design]**

| Field | Rule |
|---|---|
| `caseCode`, `amount`, `receivedOn`, `providerReference` | As in 6.3. `amount` is what was received. |
| `method` | Either a payment method identifier (active or not), or, for a way that is not a method record, a short description such as "cash, in person" (2 to 80 characters). Exactly one of the two. |
| `payerName` | Who the money came from. Private. |
| `display` | As in 3.4. For `named`, the super administrator attests that the person agreed to show that name. |
| `note` | Required, private: why this gift is entered by staff. At most 500 characters. |
| `reentryOf` | Optional: the gift this one replaces (8.3). |

The gift has no reported amount: nobody reported it. Its origin is `staff_entry`, it has no tracking code and no evidence. Everything else is the confirmation of 6.3, in one transaction.

A manual entry is the only way to record money that arrived without a report, and it is always a new gift. **[Approved]** A genuinely new external payment is never recorded as a correction.

## 8. Corrections

### 8.1 Two different things

**[Approved]** The write API must tell the repair of an erroneous record apart from money that really went back.

| | `donation_reversal` | `refund` |
|---|---|---|
| What happened | The record was wrong. **No money moved.** | **Real money went back** to the donor. |
| Examples | The same payment confirmed twice. A typing error in the amount. Confirmed for the wrong case. A payment confirmed that never arrived. | The donor asked for their money back and it was sent. The payment provider took the money back. |
| Operation | `POST /admin/donations/{donationId}/reversals` | `POST /admin/donations/{donationId}/refunds` |
| Must state | `amount`, `reason` (`duplicate_confirmation`, `wrong_amount`, `wrong_case`, `not_received`, `other`), a private `note` | `amount`, `refundedOn` (the day the money left), a private `note`, optionally the provider's own `providerReference` for the return |
| Ledger | One `donation_reversal` entry correcting the gift's `donation` entry | One `refund` entry correcting it |

They are two operations with different required fields, so neither can be sent by filling in the other's form. The screens put the question first: "Did money go back to the donor?".

### 8.2 Rules

All from `financial-terms.md` section 2 **[Approved]**, applied to the write side **[M2A design]**:

- Only a `confirmed` gift can be corrected. Otherwise 409 `donation_state_conflict`.
- A correction is for 1 cent up to what still stands of the gift. The sum of all corrections of one gift never exceeds its confirmed amount; a request that would pass it answers 409 `correction_exceeds_remaining`. The limit is checked under a lock on the gift and on its ledger entry, in the transaction that writes the correction, so two simultaneous corrections cannot both pass.
- A correction is recorded on the case the gift was confirmed for, dated at the instant it is recorded, and is never earlier than the gift's entry.
- A correction cannot be corrected or removed. One made by mistake is repaired by a new original movement: a manual entry (section 7).
- Each carries an `Idempotency-Key` (6.6).
- A refund's `providerReference` cannot be recorded twice against one gift: 409 `duplicate_payment_reference`.
- **Real events are always recorded.** A refund or a reversal is written even when it leaves the case with less than it has handed over. The shortfall is shown (`financial-terms.md` section 6). Nothing is refused, hidden or delayed for that reason.
- The donor listing shows what still stands and disappears when nothing does; the ledger keeps the gift and every correction (`financial-terms.md` section 2).

### 8.3 Putting a wrong record right

A reversal only takes away. When the truth is a different amount or a different case, the wrong gift is reversed **in full** and entered again correctly.

| Mistake | Repair |
|---|---|
| Confirmed for too much | A reversal for the difference, reason `wrong_amount`. |
| Confirmed for too little, or for the wrong case | A reversal for the full amount, then a manual entry with `reentryOf` naming the reversed gift, for the right amount and case. |
| The same payment confirmed twice | A full reversal of one of the two, reason `duplicate_confirmation`. No re-entry. |
| Confirmed, and no payment exists | A full reversal, reason `not_received`. No re-entry. |

`reentryOf` is accepted only when the named gift is confirmed, nothing of it still stands, every correction of it is a `donation_reversal` (not a refund: refunded money is gone), and it has not been re-entered already. Otherwise 409 `reentry_not_allowed`. So one payment is counted once, before and after the repair. The re-entry takes over the original's tracking code, account link and display choice, so the donor's status page follows the gift to its corrected record.

### 8.4 Showing less of a donor

`POST /admin/donations/{donationId}/listing` changes how a confirmed gift is listed to `anonymous` or `hidden`, for a donor who no longer wants to be named or listed. It can only show less. It changes no amount, no total and no ledger entry. It is audited. Showing more needs proof of who is asking, which a guest cannot give; it is not possible in milestone 2.

As `media-and-privacy.md` section 4 says of pictures: this stops the site showing the name from the next request. It does not remove copies already seen, saved or captured.

## 9. What changes in public

### 9.1 The ledger entry

A confirmation writes exactly one entry: kind `donation`, on the confirmed case, amount the confirmed amount, `allocatedDelta` plus that amount, `deliveredDelta` zero (`financial-terms.md` section 1). **[Approved]**

- **Date.** The instant of confirmation, in UTC whole seconds. It is the instant the totals changed. The day the donor paid and the day the money arrived are kept privately with the gift and are not public. **[M2A design]** This keeps the public record in the order things were recorded: no entry ever appears in the past of a page someone has already read, and a correction can never precede its gift.
- **Label.** The existing label of the kind. It never contains a name, a method or a reference.
- Only the ledger module writes ledger entries (ADR 0001). The donations module asks it to; both writes are one transaction.
- Every `donation` entry written on a real database belongs to exactly one confirmed gift, and every confirmed gift has exactly one.

### 9.2 Before confirmation, nothing

**[Approved]** A reported gift changes no public total. Stated so it can be tested: with any number of gifts in `reported` or `rejected`, every response of the seven public read endpoints is identical, byte for byte apart from `asOf` and `requestId`, to what it was before they existed. No public response carries a count of pending gifts.

### 9.3 Case status

The first two rows and the last row of the table in `financial-terms.md` section 9 become operational with the first confirmation. That section is still marked a working proposal; **owner decision 11** asks for it to be confirmed.

| Event | Effect |
|---|---|
| A confirmation brings `received` to the goal or above | `active` becomes `funded`, in the same transaction |
| A correction brings `received` below the goal | `funded` returns to `active`, in the same transaction |
| A confirmation or a correction on a `delivered` or `closed` case | The status does not change. An audit record marks the case for review. |

The general fund has no goal and its status does not change.

## 10. The general fund on a real database

`GET /site` announces `fundCode: FUND`. A real database has no such case today, so `GET /cases/FUND` answers 404 there; the frontend has tolerated that since pull request 13. A gift to the general fund needs the fund to exist.

**[Proposed, decision 7]**

- **How it comes to exist.** A database migration of milestone 2B inserts the fund when no case of kind `fund` exists. A migration runs exactly once per database, on every database, including one that already holds accounts. No command has to be remembered, and no start-up code writes data.
- **Once.** The existing unique index allows one fund. The migration inserts nothing when one is there, which is the case on a development database loaded from a fixture.
- **Empty.** No ledger entry, no picture, no category, no goal, no deadline. Code `FUND`, kind `fund`, status `active`, accepting gifts, published at the instant of the migration.
- **Not fictional.** It is real data on a real database, written by a migration, never by the fixture loader. On a disposable database the fixture loader replaces it with the fixture's own fund, as it replaces every case.
- **Never removed.** The fund is never deleted and never changes code or kind.
- **Its text** is data, and will be edited like any case text when case administration exists (milestone 4). Until then it is the text of the migration, which is why the owner approves it now.

Proposed text, for the owner's approval:

| | English | French |
|---|---|---|
| Name | General fund | Fonds général |
| Description | Gifts to the general fund are not tied to one case. They are kept until APV-GAKASINAY allocates them to a case: for an urgent need, or to complete a case that has not reached its goal. Every allocation appears in the record of this fund and in the record of the case that receives it. The fund never pays a beneficiary directly. | Les dons au fonds général ne sont pas liés à un cas précis. Ils sont conservés jusqu’à ce qu’APV-GAKASINAY les affecte à un cas : pour un besoin urgent, ou pour compléter un cas qui n’a pas atteint son objectif. Chaque affectation apparaît dans l’historique de ce fonds et dans celui du cas qui la reçoit. Le fonds ne verse jamais d’argent directement à un bénéficiaire. |

After the migration, `GET /cases/FUND` answers 200 on every database, with zero totals on a real one, and `FUND` can be chosen in a report as soon as a payment method is active. The public rules of the fund are unchanged (`financial-terms.md` section 11): it never records a disbursement.

## 11. Permissions, session states and the order of checks

### 11.1 Operations

| Operation | Session states | Permission |
|---|---|---|
| `GET /giving/methods` | Any | None |
| `POST /donations` | Any | None. Linked to the account only when fully signed in. |
| `POST /donations/evidence` | Any | The upload token of that gift |
| `POST /donations/status` | Any | The tracking code of that gift |
| Every operation under `/admin/donations` | `authenticated` only | `donations.confirm` |

A session in a pending state (`mfa_required`, `mfa_enrollment_required`) has no permission at all (I&S 4.3): on the four public operations it is treated as a guest, and on the others it is answered 401 with its state's code.

Ownership (I&S 1.3): the four public operations take no gift identifier. A donor reaches a gift only through a secret bound to that one gift. Staff operations take a gift identifier and are allowed by permission, and every one of them is audited with the actor and the gift.

### 11.2 Order of checks

The order of I&S 4.3 applies unchanged. Nothing is inserted before or between its steps. **[Approved]** order; **[M2A design]** what each step means here.

| Step | Check | Here |
|---|---|---|
| 1 | Path and method | 404 `not_found`; 405 with `Allow`. An address under `/admin/donations` with a malformed gift identifier is a known path with a bad parameter: it is refused at step 7, not here. |
| 2 | Origin and fetch metadata, on `POST` | 403 `forbidden` |
| 3 | Body size and declared type | JSON operations: 413 above 16 KiB, 415 unless `application/json`. Evidence upload: 413 above 5 MiB, 415 unless `image/jpeg` or `image/png`. |
| 4 | CSRF token, on `POST` | 403 `csrf_invalid` |
| 5 | Rate limits keyed on the client address | 429 |
| 6 | Session state, then permission | 401 with the state's code; 403 `forbidden` for a signed-in account without `donations.confirm` |
| 7 | Validation of the path parameters, the query, the headers of the operation (`Idempotency-Key`, `X-Evidence-Token` present and well formed) and the body | 400 |
| 8 | The operation's own checks, in this order: counters keyed on an account or a reference (429); a repeated `Idempotency-Key` (the first answer, or 409); the secret (400 `evidence_token_invalid`, 404 `donation_not_found` for a tracking code); the gift exists (404 `donation_not_found`); its state (409 `donation_state_conflict`); the rules of the operation (the other 409 codes); the brakes of 12.2 (503) | |

Consequences, each of which is a test:

- A request without `donations.confirm` is answered at step 6, before the gift identifier is looked at. It never learns whether a gift exists.
- A request refused before step 8 wrote nothing, and its `Idempotency-Key` was not consumed.
- The picture of an evidence upload is decoded only at step 8, after the token was accepted. An upload without a valid token costs the server no decoding.
- A lost staff session behaves as I&S 4.3 says: a `POST` still carrying its token answers 403 `csrf_invalid`; the frontend then reads `GET /me` and resends nothing by itself.

### 11.3 A change that races a decision

The rule of I&S 6.3 applies: every staff operation here re-reads the account inside its own transaction under a shared lock. A super administrator who is disabled or demoted while a confirmation is in flight either had it committed before the change, or has it refused. It can never commit afterwards.

### 11.4 Headers

Every answer of every operation in this document, at every status, is `Cache-Control: no-store` with no `ETag`, like identity answers (I&S 9.4). The other headers of I&S 9.4 apply unchanged. The evidence response adds those of 4.4.

## 12. Rate limits and abuse

Anonymous reporting is an open door: anyone can write into the review queue. These rules bound what can come through it. **[M2A design]** for the mechanism; the numbers are starting values (**owner decision 14**).

### 12.1 What a report can and cannot do

- **It changes nothing public.** No total, no list, no count. A false report gains its author nothing anyone else can see.
- **It cannot be sent from another site.** The origin check and the session-bound CSRF token apply (I&S 9). A script needs a session and a token first, and `GET /auth/csrf` is itself rate-limited.
- **It is small and closed.** 16 KiB, a closed schema, bounded text fields rendered as text only.
- **A file can only follow a report.** Evidence needs the token of an accepted report: one file per gift, re-encoded, within 30 minutes. Nobody can upload a file that is attached to nothing.
- **It never names a real person publicly without review.** A public name appears only after confirmation, and the super administrator can refuse to show it.

### 12.2 Limits

Counters use the mechanism of I&S 7.3: one atomic statement, counted before the work is done, keyed hashes instead of addresses, the client address taken as I&S 7.3 says.

| Counter | Limit | When exceeded |
|---|---|---|
| Payment methods read, per client address | 120 in 1 minute | 429 until the minute ends |
| Reports, per client address | 6 in 1 hour | 429 until the window ends, at most 60 minutes |
| Evidence uploads, per client address | 12 in 1 hour | 429 until the window ends |
| Status lookups, per client address | 10 in 15 minutes | 429 until the window ends |
| Status lookups with a wrong code, per reference | 10 in 1 hour | 429 for 15 minutes, for that reference, whatever the code |
| Staff reads, per account | 300 in 1 minute | 429 until the minute ends |
| Staff writes, per account | 60 in 1 minute | 429 until the minute ends |

`Retry-After` is never more than 60 minutes. Nothing escalates, and nothing an outsider does disables reporting for good.

### 12.3 Brakes for the whole site

A flood from many addresses passes the per-address limit. The review queue is one person's attention, so it has its own bounds.

| Brake | Starting value | Effect |
|---|---|---|
| Reports accepted, whole site | 60 in 1 hour | Further reports answer 503 `reporting_paused` with `Retry-After` until the hour's window moves |
| Gifts waiting in `reported` | 300 | Further reports answer 503 `reporting_paused` until the queue is below the bound |
| Evidence stored, in total | 2 GiB | Further uploads answer 503. Reports continue: evidence is optional. |
| Pictures being decoded at once | 2, with a 2-second wait | 503 with `Retry-After: 5` |

When a brake holds, the frontend says that reports cannot be taken for the moment and how to contact the organisers. Each time a brake starts to hold, one audit record is written. All four values are configuration, never below one.

### 12.4 Deliberately not done

- No CAPTCHA and no third-party bot service: either would send visitors' data to another company and add a script the content policy forbids.
- No block list of addresses kept by hand in milestone 2.
- No automatic rejection of a report, and no automatic confirmation. **[Approved]** A gift is never confirmed from a screenshot.

What remains: a patient person can file six false reports an hour per address. Each costs the super administrator a look and a rejection, and gains its author nothing.

## 13. Audit

Every event below is written to `audit_event` (I&S 10.1), in the transaction of the change it records where there is one. Each record has the instant, the event, the outcome, the actor, the client address, the request identifier and a details object built from an allowlist.

| Event | Recorded |
|---|---|
| Gift reported | Gift, case, method, details version, amount, whether an account is linked. Actor: the account, or none. |
| Report refused by a brake, or a brake starting to hold | Which brake |
| Evidence attached; evidence refused | Gift; for a refusal, the kind of reason |
| Evidence read | Gift, actor |
| Evidence deleted | Gift, actor or the retention task, reason |
| Gift confirmed | Gift, actor, reported and confirmed amount, reported and confirmed case, ledger entry, how it is listed, whether the amount or the case differs from the report |
| Gift rejected; gift reopened | Gift, actor, reason |
| Gift entered by staff | Gift, actor, case, amount, method, ledger entry, the gift it re-enters if any |
| Reversal recorded; refund recorded | Gift, actor, amount, reason, ledger entry, what still stands |
| A correction or a confirmation refused by a rule | Gift, actor, which rule |
| Listing restricted | Gift, actor, from and to |
| Case status changed by a confirmation or correction; case marked for review | Case, from and to, the ledger entry that caused it |
| Private details of a gift erased by retention | Gift, which fields |
| Payment method created, activated, deactivated; details version published | Method, version, operator, and for activation the eligibility attestation |
| Staff operation refused for lack of permission | Account, operation |

Never written, to the audit log or any log: the payer name, the contact detail, the payment reference, the public name, any note, a tracking code, an upload token, an `Idempotency-Key`, the content of a screenshot, or a request body of these operations. Recipient details of payment methods are public and may be recorded.

A gift also keeps its own short history (who decided what, when), which the review screen shows. It is not a second audit log: it holds nothing the audit log does not.

## 14. Retention

How long private data is kept is the owner's decision. **[Proposed, decision 15]** for both rows; nothing is assumed until it is answered.

### 14.1 Periods

| Data | Kept | Then |
|---|---|---|
| Screenshot | While the gift is `reported`, and 30 days after it is confirmed or rejected | The stored file is deleted. The gift keeps that one existed and when it was deleted. |
| Payer name, payment reference, contact detail, private notes | 90 days after a rejection; 24 months after a confirmation | Erased from the gift |
| Amounts, cases, dates, method and details version, state, decisions, ledger entries, audit records, a public name the donor agreed to show | For ever | They are the financial record |

- A daily task applies the periods and writes an audit record for each deletion and erasure.
- Reopening a rejected gift restarts its period.
- A correction within the period uses the details; after erasure a correction is still possible, and the reviewer relies on the provider's records.
- The tracking code keeps working after erasure: the status page never showed private details.

### 14.2 Limits

This is not legal advice. It does not remove copies in backups older than the period, a copy the super administrator saved or printed, or the provider's own records.

## 15. Data design

Proposed tables for milestone 2B. No migration is written yet. The `donations` module owns all of them; it reads cases and writes the ledger only through those modules.

| Table | Purpose | Key constraints |
|---|---|---|
| `payment_method` | Identifier, names, position, active flag, eligibility attestation | Identifier immutable. Never deleted once referenced. |
| `payment_method_details` | One row per version: instructions, fields, published instant, superseded instant | Append-only apart from setting the superseded instant once. At most one current version per method. |
| `donation` | One row per gift: identifiers, origin, state, reported values, private details, display choice, linked account, decision, confirmed values, ledger entry, re-entry link, what the codes are derived from, idempotency key | Never deleted. The state and the columns it requires are checked together. `reported`, `confirmed` and `rejected` only. A confirmed row has exactly one ledger entry, of kind `donation`, with its confirmed amount and case; a ledger entry belongs to at most one row. At most one re-entry per gift. |
| `donation_evidence` | Storage key, format, dimensions, size, SHA-256, upload and deletion instants | At most one per gift. |
| `donation_history` | What was decided on a gift, by whom, when | Append-only. |
| `idempotency_record` | Scope, operation, keyed hash of the key, hash of the request, what was answered | Unique per scope, operation and key. Old rows deleted by a scheduled task. |

Deliberate choices:

- **Private details live in their own columns of `donation`, read only by the review queries.** The queries that build the donor's status and anything public name their columns one by one, as milestone 0 does for cases.
- **Reported and confirmed values are different columns**, never one column that changes meaning.
- **A state change is a conditional update**, like every single-use thing in I&S 10.
- **Lock order**, everywhere: the gift, then the ledger entry being corrected, then the case, then the platform total. The ledger already takes the last two in that order.
- **Rate limits and brakes** use the counters of I&S 7.3. No new infrastructure.
- **The fund** is a row of the existing `aid_case` table.

## 16. Browser rules

For the frontend, which owns their implementation. They extend I&S 11, which applies unchanged.

- **Secrets.** The tracking code, the upload token and the `Idempotency-Key` are never placed in an address path or query, in `localStorage`, `sessionStorage`, IndexedDB or a cookie written by script, or in a log, console message or error report. A tracking code in a link is in the fragment and is removed from the address bar first.
- **The report form** is kept in memory while the donor is away paying. It is not written to storage. If the page was unloaded, the donor fills it in again.
- **Never resend by itself.** After `csrf_invalid` the report is not sent again automatically (I&S 9.1). When the person submits again, the same `Idempotency-Key` and body are sent, so one gift results.
- **Recipient details** are always read fresh (`no-store`) and are shown with the instant they were read. Before the report is sent, the page reads them again; if the version changed, it shows the new details and asks whether the payment was already made.
- **No payment fields.** No screen has a field for a card number, a bank password or a code, and no payment software kit is loaded. The content policy of I&S 11 stays as it is: links to providers are plain links.
- **Review screens** are private screens: they follow the rules of I&S 11 for clearing private state, and show evidence only from the authenticated route, never from a stored copy.
- **No fallback to mock data** in real-API mode, for any of these operations.
- **Screens.** Giving, receipt, status, review queue, gift detail, confirm, reject, manual entry, reversal and refund are tested in English and French, in both colour themes, at phone width, with the keyboard alone, and with errors announced and tied to their fields.

## 17. Threat model

| Threat | Defence | Residual risk |
|---|---|---|
| False reports to inflate a total or a donor list | Nothing public before confirmation; the super administrator verifies receipt in the payment account | None on public data |
| Flooding the review queue | Per-address limits, site-wide brakes, session-bound CSRF, small closed bodies | Six false reports an hour per address, each costing a look |
| Using the upload to store or spread files | An upload needs the token of an accepted report; one per gift; re-encoded; never served publicly or back to the uploader | Storage up to the bound of 12.3 |
| A harmful file reaching the reviewer | Only a file re-encoded by the server is stored and served; `nosniff`, `sandbox`, no script | A fault in the picture decoder |
| A screenshot leaking | Private storage, one authenticated route, `no-store`, every read audited, short retention | Whoever can read the server's disk or its backups |
| Location or device data in a screenshot | Dropped by re-encoding | What is visible in the picture itself |
| A card number or bank password reaching the site | No field asks for one; card-shaped text is refused; bodies are never logged | A donor's screenshot can show one. Retention is the mitigation. |
| Recipient details changed by an attacker to collect gifts | No network operation changes them in milestone 2; operator command only; versioned and audited; `no-store` | Someone with server access. The same trust root as I&S. |
| Money sent to details that were just replaced | The 24-hour rule of 2.4; the page re-reads details before sending | A donor who paid to old details and reports late must contact the organisers |
| One payment counted twice | State guard; idempotency keys; provider reference unique per method; possible-duplicate hints; re-entry only of a fully reversed gift | Two reports of one payment both confirmed without a provider reference. Repaired by a reversal. |
| A new payment recorded as a correction, or a refund recorded as a reversal | No operation adds money by correction; two operations with different required fields | The super administrator choosing the wrong one. Repaired by a new movement; the history stays readable. |
| Confirming without verifying | The screen states the duty; the amount and case are typed, never prefilled; audit | It is a human duty. The system records it and cannot enforce it. |
| Reading another person's gift | Guests: only by a tracking code with 60 secret bits, rate-limited, and the status shows no personal detail. Staff: permission, then audit. | A donor who shares their code |
| Guessing a tracking code from a known reference | 60 secret bits; 10 wrong codes an hour per reference | None practical |
| Learning whether a gift exists | The permission is checked before the identifier; one answer for wrong, malformed and unknown codes | None known |
| A guest's typed name used to impersonate or insult | Not public before confirmation; the reviewer can list the gift without the name; listing can be restricted later | A plausible false name that the reviewer accepts |
| Stale privileges during a decision | Account re-read under lock in the transaction (11.3) | None known |
| Private details in logs | Bodies never logged; audit details from an allowlist | A future log line; covered by a leak test |
| Fictional gifts or methods in production | Fixtures and seeds run only on a database marked disposable (I&S 10.2); the fund comes from a migration, not a fixture | None known |

## 18. Not in scope

Not in milestone 2: card or any payment processing (never); email to donors; a donor's own gift history and claiming a guest gift for an account (milestone 3); the contact form and reading contact messages; browser screens for payment configuration, transfers and disbursements (milestone 5); administrators other than the super administrator reading reports; an administrator pausing gifts for a case (milestone 4); exports and reconciliation reports.
