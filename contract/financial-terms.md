# Financial terms

Exact meaning of every money field in `openapi.yaml`, and how each maps to the approved frontend.
All amounts are integer US cents.

Status of this document: sections 1 to 4 define the public read contract (milestone 0).
Sections 5 and 6 are proposals for later milestones, recorded here so the read model does not have to change when they are built.

## 1. Ledger entries

Every confirmed movement of money is one ledger entry on one case (the general fund is a case with code `FUND`).
Entries are never edited or deleted. A correction is a new entry that points at the entry it corrects.

Each entry has a positive `amount` and two signed effects:

| Kind | `allocatedDelta` | `deliveredDelta` | Meaning |
|---|---|---|---|
| `donation` | +amount | 0 | An external payment was confirmed for this case. |
| `donation_reversal` | -amount | 0 | A donation was recorded in error. No money moved. |
| `refund` | -amount | 0 | Real money was returned to the donor. |
| `transfer_in` | +amount | 0 | Money arrived from another case or the fund. |
| `transfer_out` | -amount | 0 | Money left for another case or the fund. |
| `transfer_in_reversal` | -amount | 0 | A transfer was undone. Always paired with the entry below. |
| `transfer_out_reversal` | +amount | 0 | A transfer was undone. Always paired with the entry above. |
| `disbursement` | 0 | +amount | Money was handed to the beneficiary. |
| `disbursement_reversal` | 0 | -amount | A disbursement was recorded in error, or the money came back. |

A reported donation that has not been confirmed has no ledger entry and affects no total.

## 2. Per-case fields

| Field | Definition | Frontend use |
|---|---|---|
| `financials.externalDonations` | Sum of `donation` minus `donation_reversal` and `refund` on this case. | New. Optional breakdown on the case page. |
| `financials.transfersIn` | Sum of `transfer_in` minus `transfer_in_reversal`. | New. |
| `financials.transfersOut` | Sum of `transfer_out` minus `transfer_out_reversal`. | New. |
| `financials.allocated` | `externalDonations + transfersIn - transfersOut`. Equals the sum of `allocatedDelta`. | Same number as `Case.received`. |
| `Case.received` | Equal to `financials.allocated`. This is the "received" figure and progress bar numerator on the approved pages. | Existing. Unchanged name. |
| `Case.delivered` and `financials.delivered` | Sum of `disbursement` minus `disbursement_reversal`. Equals the sum of `deliveredDelta`. | Existing. Unchanged. |
| `financials.available` | `allocated - delivered`. Money the case still holds. | New. |
| `financials.hasShortfall` | `available < 0`. See section 5. | New. |
| `Case.goal` | Target set by an administrator. Not a ledger value. | Existing. |

`Case.received` deliberately means "net allocated to this case", not "donated directly to this case".
A case topped up from the general fund shows that money as received, and its ledger shows a `transfer_in` entry.

## 3. Platform fields (`GET /stats`)

| Field | Definition |
|---|---|
| `received` | Sum over every case of `donation` minus `donation_reversal` and `refund`. Transfers are excluded, so moving money between cases never changes it. |
| `delivered` | Sum over every case of `disbursement` minus `disbursement_reversal`. |
| `available` | `received - delivered`. |
| `peopleHelped` | Number of cases of kind `case` with `delivered > 0`. |

Because every transfer has equal and opposite entries, the sum of `Case.received` over all cases, including the fund, equals `Stats.received`.

## 4. Invariants a client or test can check

For every case, over the full ledger:

1. `sum(allocatedDelta) == Case.received == financials.allocated`
2. `sum(deliveredDelta) == Case.delivered == financials.delivered`
3. `financials.allocated == externalDonations + transfersIn - transfersOut`
4. `financials.available == allocated - delivered`

Across the platform:

5. `sum(Case.received) == Stats.received`
6. `sum(Case.delivered) == Stats.delivered`
7. The sum of all transfer deltas is zero.

Donor list: the sum of listed donor amounts is at most `externalDonations`, because hidden donations are counted in totals but not listed.

## 5. Shortfalls (proposal, later milestone)

Normal rule: a disbursement or a transfer out is refused when it would make `available` negative.

Exception: a real-world event that has already happened is always recorded, even when it makes `available` negative. The two cases are a `refund` (the payment provider or the donor took the money back) and a `donation_reversal` of money that was already spent.

While a case has a shortfall:

- `hasShortfall` is true and `available` is negative.
- No new disbursement or transfer out is accepted from that case.
- The shortfall is listed for the super administrator until it is resolved.

A shortfall is resolved by a transfer in from the general fund, by a new donation, or by a disbursement reversal when the beneficiary returns money. Each is an ordinary ledger entry.

## 6. Lifecycle effects (proposal, later milestone)

| Event | Effect on status |
|---|---|
| Confirmation or transfer in brings `received` to the goal or above | `active` becomes `funded`. |
| Reversal, refund or transfer out brings `received` below the goal | `funded` returns to `active`, unless the deadline has passed. |
| Goal is raised above `received` | `funded` returns to `active`. |
| Goal is lowered to `received` or below | `active` becomes `funded`. |
| `delivered` reaches `received` on a funded or past-deadline case | An administrator marks it `delivered`. Not automatic. |
| Any correction on a `delivered` or `closed` case | Status does not change automatically. The case is flagged for review. |

## 7. General fund

The fund is a source of transfers to cases. It does not disburse to beneficiaries, so its `delivered` is zero and its balance is `financials.available`.
This is pending owner decision 11. If the owner allows direct disbursement from the fund, no field changes; the fund's `delivered` simply becomes non-zero.

The current mock shows the fund with a non-zero `delivered`. The shared fixtures correct this.
