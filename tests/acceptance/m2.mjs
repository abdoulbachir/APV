// Milestone 2B joint run, env A (dev profile, disposable database, two fictional payment methods):
// a donor reports a gift in the real frontend, a super administrator reviews it, and the public
// record changes only at confirmation. Real browser, real backend, real PostgreSQL; nothing mocked.
// Needs the dev backend on 8080 (log in evidence/backend-dev.log) and the preview on 4173.
import fs from 'node:fs';
import path from 'node:path';
import { PNG } from 'pngjs';
import { assert, check, EVIDENCE, profile, closeBrowser, secret, waitHeading, waitForm, staffSignIn, sleep } from './lib.mjs';
const ENV = 'A: dev profile, HTTP preview', origin = 'http://127.0.0.1:4173';
const log = fs.readFileSync(path.join(EVIDENCE, 'backend-dev.log'), 'utf8');
const owner = log.match(/super_admin\s+(\S+)\s+password:\s+(\S+)\s+authenticator secret:\s+(\S+)/);
assert.ok(owner, 'development super administrator not found in the backend log');
const staff = { email: owner[1], password: secret('password', owner[2]), secret: secret('authenticator secret', owner[3]) };
const stats = async () => (await (await fetch('http://127.0.0.1:8080/api/stats')).json());
const fund = async () => (await (await fetch('http://127.0.0.1:8080/api/cases/FUND')).json());
const png = () => { const image = new PNG({ width: 64, height: 64 }); image.data.fill(180); const file = path.join(EVIDENCE, 'screenshot.png'); fs.writeFileSync(file, PNG.sync.write(image)); return file; };

const D = await profile(origin); const donor = await D.context.newPage();
const S = await profile(origin); const admin = await S.context.newPage();
const before = await stats(); const fundBefore = await fund();
let code = '', code2 = '';

async function report(page, amount, name) {
  await page.goto(origin + '/give'); await page.locator('.donation-recipient').waitFor({ timeout: 15000 });
  await page.locator('#amount').fill(String(amount)); await page.locator('#payerName').fill(name);
  await page.locator('.identity-panel form button[type=submit]').click();
  await page.locator('.donation-tracking').waitFor({ timeout: 15000 });
  return (await page.locator('.donation-tracking').innerText()).trim();
}
const posts = (P, t) => P.log.responses.filter(r => r.at >= t && r.method === 'POST').map(r => `${r.path.replace('/api', '')} ${r.status}`);

await check('m2-methods', ENV, 'The giving page reads the active payment methods from the real backend and shows the recipient details', async () => {
  await donor.goto(origin + '/give'); await donor.locator('.donation-recipient').waitFor({ timeout: 15000 });
  const count = await donor.locator('input[name="method"]').count(); assert.ok(count >= 1);
  return `${count} methods offered`;
});
let t = Date.now();
await check('m2-report', ENV, 'A guest reports a gift to the general fund: 201, a reference and a tracking code shown once; nothing public changes', async () => {
  code = secret('tracking code', await report(donor, 40, 'Joint Run Donor'));
  assert.match(code, /^[0-9A-Z]{4}(-[0-9A-Z]{4}){4}$/); assert.deepEqual(posts(D, t), ['/donations 201']);
  const now = await stats(); assert.equal(now.received, before.received);
  assert.ok(!donor.url().includes(code.slice(10)), 'the tracking code is in the address');
  assert.equal(await donor.evaluate(c => JSON.stringify({ ...localStorage, ...sessionStorage }).includes(c.slice(10)), code), false);
  return 'public received total unchanged; the code is in no address or storage';
});
await check('m2-evidence', ENV, 'The receipt accepts one screenshot: 204, and the page says it is attached', async () => {
  t = Date.now(); await donor.locator('.donation-evidence input[type=file]').setInputFiles(png());
  await donor.locator('.donation-evidence button').click(); await donor.locator('.donation-ok').waitFor({ timeout: 15000 });
  assert.deepEqual(posts(D, t), ['/donations/evidence 204']);
});
await check('m2-status-reported', ENV, 'The tracking code shows the gift waiting, with no personal detail; a wrong code is refused alike', async () => {
  await donor.goto(origin + '/track'); await donor.locator('#code').fill(code); await donor.locator('form button[type=submit]').click();
  await donor.locator('.donation-status').waitFor({ timeout: 15000 }); const shown = await donor.locator('.donation-status').innerText();
  assert.match(shown, /Waiting to be verified/); assert.ok(!shown.includes('Joint Run Donor'));
  await donor.locator('#code').fill(code.slice(0, 10) + 'ZZZZ-ZZZZ-ZZZZ'); await donor.locator('form button[type=submit]').click();
  await donor.locator('#code-error').waitFor({ timeout: 15000 }); assert.match(await donor.locator('#code-error').innerText(), /No gift was found/);
});

await admin.goto(origin + '/sign-in'); await staffSignIn(admin, staff);
await check('m2-queue', ENV, 'The super administrator reaches the queue from the account page and sees the reported gift with its screenshot flag', async () => {
  await admin.locator('a', { hasText: 'Review gifts' }).click(); await admin.locator('.donation-list').waitFor({ timeout: 15000 });
  const row = admin.locator('.donation-list a', { hasText: code.slice(0, 9) }); assert.equal(await row.count(), 1);
  assert.match(await row.innerText(), /screenshot/); assert.match(await admin.locator('.donation-pending').innerText(), /waiting/);
  await row.click(); await admin.locator('.donation-facts').first().waitFor({ timeout: 15000 });
});
await check('m2-detail', ENV, 'The gift page shows what was reported, the private payer name, and the screenshot from the authenticated route', async () => {
  const body = await admin.locator('.donation-admin-panel').innerText(); assert.match(body, /Joint Run Donor/); assert.match(body, /\$40/);
  const img = admin.locator('.donation-evidence-view img'); await img.waitFor({ timeout: 15000 });
  await admin.waitForFunction(() => { const i = document.querySelector('.donation-evidence-view img'); return i && i.complete && i.naturalWidth > 0; }, null, { timeout: 15000 });
  const response = S.log.responses.find(r => /\/admin\/donations\/[^/]+\/evidence$/.test(r.path)); assert.ok(response); assert.equal(response.status, 200);
  assert.match(String(response.headers['cache-control']), /no-store/);
  return 'picture decoded in the page; served no-store';
});
await check('m2-confirm', ENV, 'Confirming with a typed case and amount: one POST, the gift is confirmed, and the public total rises by exactly that amount at that moment', async () => {
  assert.equal(await admin.locator('#caseCode').inputValue(), ''); assert.equal(await admin.locator('#confirmedAmount').inputValue(), '');
  t = Date.now(); await admin.locator('#caseCode').fill('FUND'); await admin.locator('#confirmedAmount').fill('40');
  await admin.locator('#receivedOn').fill(new Date().toISOString().slice(0, 10)); await admin.locator('#listing').selectOption('as_requested');
  await admin.locator('.donation-action button.identity-primary').first().click(); await admin.locator('.donation-ok').waitFor({ timeout: 15000 });
  assert.match(await admin.locator('.donation-state').innerText(), /Confirmed/);
  assert.equal(posts(S, t).filter(x => x.includes('/confirm')).join(), posts(S, t).filter(x => x.includes('/confirm 200')).join()); assert.equal(posts(S, t).filter(x => x.includes('/confirm')).length, 1);
  const now = await stats(); assert.equal(now.received, before.received + 4000);
  const f = await fund(); assert.equal(f.financials.externalDonations, fundBefore.financials.externalDonations + 4000);
  return 'received +$40; the fund shows it';
});
await check('m2-status-confirmed', ENV, 'The donor\'s status page now says verified, with the amount', async () => {
  await donor.goto(origin + '/track'); await donor.locator('#code').fill(code); await donor.locator('form button[type=submit]').click();
  await donor.locator('.donation-status').waitFor({ timeout: 15000 }); assert.match(await donor.locator('.donation-status').innerText(), /Verified on .*\$40/);
});
await check('m2-refund', ENV, 'A partial refund with its required note: the net amount and the public total fall by it; an amount above what stands is refused and changes nothing', async () => {
  await admin.locator('input[name="correction"]').nth(1).check();
  await admin.locator('#refundAmount').fill('15'); await admin.locator('#refundedOn').fill(new Date().toISOString().slice(0, 10)); await admin.locator('#refundNote').fill('Joint run: donor asked for part back');
  await admin.locator('.donation-action form button.identity-primary').click(); await admin.locator('.donation-ok').waitFor({ timeout: 15000 });
  await admin.waitForFunction(() => /\$25/.test(document.querySelector('.donation-admin-panel')?.textContent ?? ''), null, { timeout: 15000 });
  assert.equal((await stats()).received, before.received + 2500);
  await admin.locator('input[name="correction"]').nth(0).check();
  await admin.locator('#reversalAmount').fill('30'); await admin.locator('#reversalReason').selectOption('wrong_amount'); await admin.locator('#reversalNote').fill('Joint run: more than stands');
  await admin.locator('.donation-action form button.identity-primary').click(); await admin.locator('.donation-action [role=alert]').waitFor({ timeout: 15000 });
  assert.match(await admin.locator('.donation-action [role=alert]').innerText(), /more than what still stands/); assert.equal((await stats()).received, before.received + 2500);
  return 'net $25 after a $15 refund; a $30 reversal refused (409)';
});
await check('m2-reject-reopen', ENV, 'A second report is rejected (the donor sees the reason), then reopened; neither touches a public total', async () => {
  code2 = secret('tracking code', await report(donor, 9, 'Second Donor')); const total = (await stats()).received;
  await admin.goto(origin + '/admin/donations'); await admin.locator('.donation-list').waitFor({ timeout: 15000 });
  await admin.locator('.donation-list a', { hasText: code2.slice(0, 9) }).click(); await admin.locator('#reason').waitFor({ timeout: 15000 });
  await admin.locator('#reason').selectOption('not_received'); await admin.locator('.donation-action button.donation-secondary').click();
  await admin.locator('.donation-ok').waitFor({ timeout: 15000 }); assert.match(await admin.locator('.donation-state').innerText(), /Rejected/);
  await donor.goto(origin + '/track'); await donor.locator('#code').fill(code2); await donor.locator('form button[type=submit]').click();
  await donor.locator('.donation-status').waitFor({ timeout: 15000 }); assert.match(await donor.locator('.donation-status').innerText(), /No matching payment/);
  await admin.locator('.donation-action button.identity-primary').click(); await sleep(800);
  await admin.waitForFunction(() => /Waiting to be verified/.test(document.querySelector('.donation-state')?.textContent ?? ''), null, { timeout: 15000 });
  assert.equal((await stats()).received, total);
});
await check('m2-manual-entry', ENV, 'Manual entry records a gift confirmed at once and the public total rises by it', async () => {
  const total = (await stats()).received;
  await admin.goto(origin + '/admin/donations/new'); await admin.locator('#caseCode').waitFor({ timeout: 15000 });
  await admin.locator('#caseCode').fill('FUND'); await admin.locator('#amount').fill('12'); await admin.locator('#receivedOn').fill(new Date().toISOString().slice(0, 10));
  await admin.locator('#methodDescription').fill('cash, in person'); await admin.locator('#payerName').fill('Hand Giver'); await admin.locator('#note').fill('Joint run: handed over in person');
  await admin.locator('.donation-action button.identity-primary').click();
  await admin.waitForFunction(() => /Confirmed/.test(document.querySelector('.donation-state')?.textContent ?? ''), null, { timeout: 15000 });
  assert.match(await admin.locator('.donation-state').innerText(), /entered by staff/); assert.equal((await stats()).received, total + 1200);
});
await check('m2-donor-cannot-review', ENV, 'An anonymous browser is sent to sign-in from the review pages, and the API answers 401 without saying whether a gift exists', async () => {
  await donor.goto(origin + '/admin/donations'); await waitHeading(donor, 'Sign in'); await waitForm(donor);
  const r = await fetch('http://127.0.0.1:8080/api/admin/donations/don_0000000000000000000000'); assert.equal(r.status, 401);
});
await check('m2-clean', ENV, 'No content-policy violation or page error in either browser during the run', async () => {
  assert.deepEqual(D.log.violations, []); assert.deepEqual(S.log.violations, []); assert.deepEqual(D.log.pageErrors, []); assert.deepEqual(S.log.pageErrors, []);
});
await D.close(); await S.close(); await closeBrowser();
