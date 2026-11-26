# Financial terms

Exact meaning of every money field in `openapi.yaml`, and the rules the ledger follows.
All amounts are integer US cents.

Sections 1 to 8 define the public read contract (milestone 0) and are enforced on the fixtures by `npm run validate`.
Sections 9 to 11 state rules whose write side is built in later milestones. They are recorded now so the read model does not change when they are built. Where a rule still waits for the project owner, the section says so.

## 1. Ledger entries

Every confirmed movement of money is one ledger entry on one case. The general fund is a case with code `FUND`.
Entries are never edited or deleted. A reported donation that has not been confirmed has no ledger entry and affects no total.

Each entry has a positive `amount` and two signed effects.

Original movements:

| Kind | `allocatedDelta` | `deliveredDelta` | Meaning |
|---|---|---|---|
| `donation` | +amount | 0 | An external payment was confirmed for this case. |
| `transfer_in` | +amount | 0 | Money arrived from another case or the fund. |
| `transfer_out` | -amount | 0 | Money left for another case or the fund. |
| `disbursement` | 0 | +amount | Money was handed to the beneficiary. |

Corrections:

| Kind | `allocatedDelta` | `deliveredDelta` | Corrects | Meaning |
|---|---|---|---|---|
| `donation_reversal` | -amount | 0 | `donation` | The donation was recorded in error. No money moved. |
| `refund` | -amount | 0 | `donation` | Real money went back to the donor. |
| `transfer_in_reversal` | -amount | 0 | `transfer_in` | The transfer was undone. Always paired with the entry below. |
| `transfer_out_reversal` | +amount | 0 | `transfer_out` | The transfer was undone. Always paired with the entry above. |
| `disbursement_reversal` | 0 | -amount | `disbursement` | The disbursement was recorded in error, or the money came back. |

## 2. Corrections

1. **A correction points at exactly one original.** `corrects` holds the identifier of that entry. It is set on the five correction kinds and null on the four original kinds.
2. **Only the kind in the table can be corrected by each correction kind.** A refund cannot point at a disbursement.
3. **Same case.** A correction is recorded on the case of the entry it corrects.
4. **Not earlier.** A correction is never dated before the entry it corrects.
5. **Partial and full.** A correction can be for any amount from one cent up to what is left of the original. An original can receive any number of corrections, for example several partial refunds.
6. **Cumulative limit.** The sum of all corrections of one original never exceeds the original's amount. The original is fully corrected when the sum equals its amount, partly corrected when it is between zero and the amount.
7. **Corrections are final.** A correction cannot itself be corrected. A correction made by mistake is repaired by recording a new original movement, which keeps the history readable: every entry is either a movement or a direct correction of one.
8. **Transfer reversals are atomic.** Undoing a transfer writes two entries in one database transaction: a `transfer_out_reversal` on the sending case and a `transfer_in_reversal` on the receiving case, with the same amount and date. They correct the two sides of the same transfer. A transfer can be undone in part, and the two sides always stay equal.

How the public record shows this, on every `LedgerEntry`:

| Field | Meaning |
|---|---|
| `corrects` | The original this entry corrects, or null. |
| `correctedBy` | Every entry that corrects this one, oldest first. Replaces the earlier single `reversedBy`. |
| `correctedAmount` | Sum of the amounts in `correctedBy`. |
| `netAmount` | `amount - correctedAmount`. What still stands. |

**Net donor amount.** A donor listing shows the donation's `netAmount`. It keeps the date of the original donation. When the net amount reaches zero the listing disappears; the ledger still shows the donation and its corrections.

**Protection against recording a correction twice.** Three layers, built with the write endpoints:

- The cumulative limit is checked while holding a lock on the original entry, in the same transaction that writes the correction. Two simultaneous refunds cannot both pass.
- Every correction request carries an idempotency key scoped to the caller and the operation. Repeating the request returns the first result. The same key with different content is refused.
- A refund can carry the payment provider's own reference. The same reference cannot be recorded twice against one donation.

Example, from `fixtures/example.json`: donation `L03` of 10000 receives refund `L08` of 4000 and refund `L09` of 2500.
`L03` then shows `correctedBy: [L08, L09]`, `correctedAmount: 6500`, `netAmount: 3500`, and the anonymous donor listing shows 3500.
A third refund above 3500 is rejected.

## 3. Per-case fields

| Field | Definition |
|---|---|
| `financials.externalDonations` | Sum of `donation` minus `donation_reversal` and `refund` on this case. |
| `financials.transfersIn` | Sum of `transfer_in` minus `transfer_in_reversal`. |
| `financials.transfersOut` | Sum of `transfer_out` minus `transfer_out_reversal`. |
| `financials.allocated` | `externalDonations + transfersIn - transfersOut`. Equals the sum of `allocatedDelta`. |
| `Case.received` | Equal to `financials.allocated`. The "received" figure and progress bar numerator on the approved pages. |
| `Case.delivered` and `financials.delivered` | Sum of `disbursement` minus `disbursement_reversal`. Equals the sum of `deliveredDelta`. |
| `financials.available` | `allocated - delivered`. Money the case still holds. |
| `financials.hasShortfall` | `available < 0`. See section 6. |
| `Case.goal` | Target set by an administrator. Not a ledger value. |

`Case.received` deliberately means "net allocated to this case", not "donated directly to this case".
A case topped up from the general fund shows that money as received, and its ledger shows a `transfer_in` entry naming the fund in `counterpartCaseCode`.

## 4. Platform fields (`GET /stats`)

| Field | Definition |
|---|---|
| `received` | Sum over every case of `donation` minus `donation_reversal` and `refund`. Transfers are excluded, so moving money between cases never changes it. |
| `delivered` | Sum over every case of `disbursement` minus `disbursement_reversal`. Transfers are excluded. |
| `available` | `received - delivered`. |
| `peopleHelped` | Number of cases of kind `case` with net `delivered` above zero. |

`peopleHelped` counts cases, not human beings. One case can help a household of five, and one person can have two cases. The name is kept because the approved frontend uses it; the displayed wording must say "cases" or an equivalent.

## 5. Bounds

| Quantity | Bound | Why |
|---|---|---|
| One movement, or a goal | 1 to 10,000,000,000 cents | Far above any real gift, small enough to catch typing errors. |
| Sum of all movement amounts on the platform | at most 1,000,000,000,000,000 cents | A write that would pass it is refused. |
| Any total (`Cents`, `SignedCents`) | at most 1,000,000,000,000,000 in absolute value | Follows from the line above: every total is a signed sum of some movements. |

The largest total is about a ninth of the largest safe JavaScript integer (9,007,199,254,740,991), so no total can lose precision in a browser.

Arithmetic rules:

- Totals are summed in PostgreSQL as `numeric`, never as floating point.
- Comparing two funding ratios multiplies a total by a goal, which can reach 10^25 and does not fit in 64 bits. The backend uses `numeric`, the frontend uses `BigInt`.

## 6. Shortfalls

A case has a shortfall when it has handed over more than it holds: `available < 0`.

- **Real events are always recorded.** A refund the payment provider forced, or the reversal of a donation that was already spent, has happened. It is written to the ledger even when it makes `available` negative. It is never hidden, delayed or refused.
- **The shortfall stays visible.** `hasShortfall` is true, `available` is negative, `received` can fall below `delivered`, and the ledger shows the entry that caused it. Clients show the real numbers and only clamp the progress bar.
- **Discretionary spending is blocked.** While it would leave `available` below zero, a new `disbursement`, a `transfer_out` and a `transfer_in_reversal` are refused. These are choices, not events.
- **Resolution** is an ordinary ledger entry: a transfer in from the general fund, a new donation, or a disbursement reversal when the beneficiary returns money.

`fixtures/example.json` contains one: `HELP-903` delivered 25000, then a refund of 1000 arrived, leaving `available` at -1000.

## 7. Draft and non-public cases

Platform totals must be the sum of what the public can see. Therefore:

- **No money moves on a draft.** A donation cannot be confirmed for, and a transfer cannot be made to or from, a case that has never been published. The database enforces this.
- **A published case never becomes a draft again and is never deleted** once it has a ledger entry.
- **Withdrawing a case from view keeps its numbers.** If a beneficiary withdraws consent, the title, story, name and pictures are replaced by a neutral notice. The code, status, totals and ledger stay public.

Consequence: every ledger entry belongs to a public case, so invariants 5 to 7 below hold over public responses alone, and no private information is needed to reconcile.

## 8. Invariants a client or test can check

For every case, over the full ledger:

1. `sum(allocatedDelta) == Case.received == financials.allocated`
2. `sum(deliveredDelta) == Case.delivered == financials.delivered`
3. `financials.allocated == externalDonations + transfersIn - transfersOut`
4. `financials.available == allocated - delivered`

Across the platform, over every public case including the fund:

5. `sum(Case.received) == Stats.received`
6. `sum(Case.delivered) == Stats.delivered`
7. `sum(transfersIn) == sum(transfersOut)`

For every entry:

8. `correctedAmount == sum(amount of entries in correctedBy) <= amount`
9. `netAmount == amount - correctedAmount`

Donor list: the sum of listed donor amounts is at most `externalDonations`, because hidden donations are counted in totals but not listed.

**Consistent reads.** Each response is computed inside one read-only database transaction with a single snapshot, so within one response the case, its breakdown, its previews and its counts always satisfy invariants 1 to 4. `asOf` names the instant of that snapshot.

**Several pages.** Two requests are two snapshots. `GET /cases/{code}/ledger` in `openapi.yaml` describes how a movement recorded between two page requests shows up, and how a client detects it with `totalItems`.

## 9. Lifecycle

Confirmed by the owner with milestone 2A (2026-11-26, decision 11). The status transitions below are accepted; their write side is built in milestone 2B.

Statuses: `draft`, `active`, `funded`, `delivered`, `closed`. Public grouping: current is `active` and `funded`; completed is `delivered` and `closed`.

| Event | Effect on status |
|---|---|
| Confirmation or transfer in brings `received` to the goal or above | `active` becomes `funded`. |
| Reversal, refund or transfer out brings `received` below the goal | `funded` returns to `active`. |
| Goal is raised above `received`, or lowered to `received` or below | `funded` returns to `active`, or `active` becomes `funded`. |
| An administrator records that the help was handed over | `active` or `funded` becomes `delivered`. Reaching the goal is not required. |
| An administrator finalizes the case | `delivered` becomes `closed`. An expired case that received nothing can be closed directly. |
| An administrator reopens the case | `delivered` or `closed` returns to `active` or `funded`, depending on the goal. History is kept. |
| Any correction on a `delivered` or `closed` case | Status does not change automatically. The case is flagged for review. |

The deadline passing does not change the status. It ends new gifts, as described next.

**Accepting gifts is separate from status.** `Case.acceptingDonations` is true when the status is `active` or `funded`, the administrator has not paused gifts for the case, and the deadline's cutoff has not passed. Whether a `funded` case keeps accepting gifts by default is an open owner decision; the field carries the answer either way, so the frontend never works it out itself.

## 10. Deadlines and timezones

- `deadline` is a calendar day. `deadlineTimezone` is the zone in which it is that day. `deadlineEndsAt` is the cutoff instant: the start of the following day in that zone.
- All three are stored with the case when the deadline is set. The organization timezone (`Site.timezone`, default `America/Chicago`) only supplies the zone for deadlines set from then on.
- **Changing the organization timezone never moves an existing deadline.** Existing cases keep their own `deadlineTimezone` and `deadlineEndsAt`. An administrator who wants an old deadline in the new zone edits that case, which is an audited change.
- The cutoff is computed with the zone's daylight saving rules. In `America/Chicago`, a deadline of 2027-01-18 ends at 06:00 UTC and a deadline of 2027-03-14, the day clocks move forward, ends at 05:00 UTC. Both are in the fixtures.
- Every other timestamp is a UTC instant and is displayed in the visitor's own timezone.

## 11. General fund (working proposal, pending owner confirmation)

The fund is a pool of money not yet allocated to a case. Money leaves it only by a transfer to a case, and is recorded as delivered on that case when it reaches the beneficiary. The fund itself never records a disbursement.

What the public sees for the fund:

| Displayed | Field |
|---|---|
| External donations to the general fund | `financials.externalDonations` |
| Net funds allocated to cases | `financials.transfersOut` minus `financials.transfersIn` |
| Available balance | `financials.available` |

A transfer is never described as a delivery. If the owner instead allows the fund to pay a beneficiary directly, no field changes: the fund's `delivered` simply becomes non-zero.

The current frontend mock shows the fund with a non-zero `delivered`. The shared fixtures do not.
