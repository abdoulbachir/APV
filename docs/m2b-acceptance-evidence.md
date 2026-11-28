# M2B joint donation acceptance: evidence of 2026-11-28

Real browser, real production frontend build, real Spring Boot, real PostgreSQL 18.7. No donation or identity response was mocked.

**This is not acceptance of milestone 2B.** It is the coordinator's joint run. An independent review has not happened, CI has not run on the integration branch, and nothing was merged into `main`.

## What was run

Integration branch `integration/m2b-donation`, application code of frontend `feature/m2b-donation-frontend` and backend `feature/m2b-donation-backend` at contract 0.4.2. One pass of the whole harness (`tests/acceptance/README.md`), results in `tests/acceptance/results-2026-11-28-m2b-full.json`:

| Part | Scripts | Result |
|---|---|---|
| M1B identity harness, as regression | `b1` to `b4`, `a1` to `a3`, `r1`, `r2`, and the four plain reproductions | PASS, with the two known PARTIAL (10.15 screen reader, 10.16 password manager) |
| Donations under production rules (HTTPS, unmarked database) | `m2p` | 7 PASS |
| Donations in the dev profile | `m2`, `m2b` | 26 PASS |

Total 121 PASS, 2 PARTIAL, 0 FAIL. Both backend logs for the whole run: no identifier-shaped text, no hex literal, no ERROR line. R1: 150 rounds, nobody left signed in after a sign-out.

## What the donation checks assert

- **Nothing public before confirmation.** A report, a rejection and a reopening leave `GET /stats` unchanged; a confirmation raises `received` by exactly the confirmed amount; a refund lowers it by its amount; a refused correction changes nothing.
- **Donor flow.** Methods and recipient details from the server; report (201); reference and tracking code shown once, in no address and no storage; one screenshot (204); status by code shows no personal detail; a wrong code is refused like an unknown one.
- **Presentation.** Report and status pages in English and French, light and dark, 360 and 1440 px, with contrast, overflow and untranslated-text checks; the keyboard alone; French at 390 px for the donor and the review pages.
- **Faults.** A lost answer to a report (the same key and body return the one gift's receipt); another session's token (403, nothing recorded, nothing resent); two presses in one instant (one request); a card-shaped value (refused, named, not stored, not logged); a staff session ended while a confirmation is typed (403 once, sign-in screen, gift still waiting).
- **Review.** Queue, gift detail with the private payer name and the screenshot from the authenticated route (`no-store`), confirm with typed values, partial refund with its required note, a reversal above what stands refused, reject with the reason shown to the donor, reopen, manual entry, a provider reference recorded once per method across reports and manual entries.
- **Access.** Anonymous: sign-in screen and 401. Donor account: told it cannot review, 403. Administrator who is not the super administrator: no link, told so, 403 on the list and on a gift identifier alike.
- **Production rules.** The general fund exists on a real database with its approved text and the home page shows it. Giving is closed while no method is active. The operator creates a method, is refused activation before details exist, publishes details from a file and activates with an attestation; the three steps are audited and nothing sensitive is on a command line. A report over HTTPS uses the `__Host-` cookie. The server log, every request address and referrer, and all audit details were searched for the payer name, contact, tracking code, passwords and authenticator secrets: none found.

## Found by the joint run and corrected (frontend)

| Found | Correction |
|---|---|
| The report form and the review screens read the CSRF token just before each POST, so a form could be sent for whatever session the browser then had | The token is read when the screen opens (I&S 9.1, donation-lifecycle 3.1); after `csrf_invalid` nothing is resent |
| Two presses before the disabled state was drawn sent two requests (one gift resulted, by the idempotency key) | A second press sends nothing |
| A refused report did not say which field | The server's field and reason are shown, in both languages |

Contract corrections made during the build, each approved by the owner: the private note of a reversal and a refund is required (0.4.1); a manual entry takes an optional provider reference (0.4.2).

## Not covered

- Rate limits and the site-wide brakes of donation-lifecycle 12, retention (14), and the evidence upload whose answer is lost: backend tests only.
- Corrections beyond a partial refund and a refused reversal: full reversal with re-entry, restricting the listing, deleting evidence early.
- Giving to a specific case from its page under production rules (a real database has no case yet); the case-page link was checked in mock mode only.
- Chromium only. No screen reader, no password manager, no phone hardware.
- The owner's real payment methods (decision 5) are not entered anywhere; the runs use fictional ones.
- The backend suite (448 tests at `cd4fc3f`, more since) was run by the backend agent locally, not in CI.
