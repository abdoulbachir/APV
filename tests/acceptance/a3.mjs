// Env A: the site header's way to an account, and the changes made after the owner's manual test of
// 2026-11-25 and 26: "Sign in" or "My account" in the header, one error message for a wrong password,
// the short-password mark, the header that stays at the top, and no page that reads GET /me twice.
import crypto from 'node:crypto';
import { assert, check, resetThrottle, profile, closeBrowser, secret, waitHeading, waitForm, fill, submit, settle, signIn, signOut, sleep } from './lib.mjs';
const ENV='A: dev profile, HTTP preview with enforced policy', DB='apv-m1b-accept-dev', origin='http://127.0.0.1:4173'; resetThrottle(DB);
const P=await profile(origin); const page=await P.open('/'); await settle(page);
const header=async()=>{await sleep(500);const a=page.locator('.header-account');return `${(await a.innerText()).trim()} -> ${await a.getAttribute('href')}`;};
const meSince=t=>P.log.responses.filter(r=>r.at>=t&&r.path==='/api/me').map(r=>r.status);
let t=Date.now();
await check('header-anonymous',ENV,'Anonymous visitor on a public page: the header offers "Sign in"; the page reads GET /me once',async()=>{
  await page.reload(); await settle(page); assert.equal(await header(),'Sign in -> /sign-in'); assert.deepEqual(meSince(t),[401]);
});
const email=`header.${crypto.randomBytes(3).toString('hex')}@example.test`, password=secret('password','Rk6 walnut-ember pelican 3308');
await page.goto(origin+'/sign-up'); await waitHeading(page,'Create an account'); await waitForm(page);
await check('short-password',ENV,'A new password under 15 characters is marked while typing: red edge, a count, aria-invalid; cleared at 15',async()=>{
  await page.locator('#password').fill('short pass'); assert.equal(await page.locator('#password').getAttribute('aria-invalid'),'true');
  assert.match(await page.locator('#password-length').innerText(),/10 of 15/); assert.ok((await page.locator('#password').getAttribute('aria-describedby')).includes('password-length'));
  await page.locator('#password').fill('fifteen chars ok'); assert.equal(await page.locator('#password').getAttribute('aria-invalid'),null); assert.equal(await page.locator('#password-length').count(),0);
});
await fill(page,{displayName:'Fictional Reader',email,password}); await submit(page); await settle(page);
await waitHeading(page,'Sign in'); await waitForm(page); await fill(page,{email,password:'not the password at all 00'}); await submit(page); await settle(page);
await check('one-message',ENV,'A wrong password is reported once, on the password field, as an alert; the sign-in field itself carries no length mark',async()=>{
  const message=await page.locator('#password-error').innerText(); const all=await page.locator('.identity-panel p').allInnerTexts();
  assert.equal(all.filter(x=>x===message).length,1); assert.equal(await page.locator('#password-error').getAttribute('role'),'alert'); assert.equal(await page.locator('.identity-panel [role=alert]').count(),1);
  assert.equal(await page.evaluate(()=>document.activeElement.id),'password'); assert.equal(await page.locator('#password-length').count(),0);
});
t=Date.now(); await signIn(page,email,password); await waitHeading(page,'My account');
await check('header-signed-in',ENV,'After a real sign-in the header reads "My account" on the account page, and adds no GET /me of its own',async()=>{
  assert.equal(await header(),'My account -> /account');
  // Two reads, as before the header existed: the session store after the sign-in, and the account screen preparing its form.
  assert.deepEqual(meSince(t),[200,200]);
  return 'GET /me after sign-in: the session store, then the account screen preparing its form; none from the header';
});
await check('header-public-in-app',ENV,'Going to a public page inside the application keeps "My account" without a further GET /me',async()=>{
  t=Date.now(); await page.locator('.site-header .brand').click(); await settle(page); assert.equal(new URL(page.url()).pathname,'/'); assert.equal(await header(),'My account -> /account'); assert.deepEqual(meSince(t),[]);
});
await check('header-public-load',ENV,'Loading a public page afresh while signed in: "My account", one GET /me, no cookie set by it',async()=>{
  t=Date.now(); await page.goto(origin+'/cases'); await settle(page); assert.equal(await header(),'My account -> /account'); assert.deepEqual(meSince(t),[200]);
  assert.ok(!P.log.responses.filter(r=>r.at>=t&&r.path==='/api/me').some(r=>r.headers['set-cookie']));
});
await check('header-french',ENV,'In French the header reads "Mon compte"',async()=>{
  await page.evaluate(()=>localStorage.setItem('apv-locale','fr')); await page.reload(); await settle(page); assert.equal(await header(),'Mon compte -> /account');
  await page.evaluate(()=>localStorage.setItem('apv-locale','en')); await page.reload(); await settle(page);
});
await check('header-sticky',ENV,'The header stays at the top of the window while the page is scrolled (desktop width)',async()=>{
  await page.goto(origin+'/'); await settle(page); await page.mouse.wheel(0,900); await sleep(500);
  const m=await page.evaluate(()=>({top:Math.round(document.querySelector('.site-header').getBoundingClientRect().top),y:Math.round(scrollY)})); assert.ok(m.y>200,'page did not scroll'); assert.equal(m.top,0);
});
await check('header-other-tab',ENV,'Sign-out in one tab: a public page open in another tab goes back to "Sign in"',async()=>{
  const other=await P.context.newPage(); await other.goto(origin+'/account'); await waitHeading(other,'My account'); await waitForm(other);
  await page.bringToFront(); await signOut(other); await sleep(800); await page.bringToFront(); assert.equal(await header(),'Sign in -> /sign-in'); await other.close();
});
assert.deepEqual(P.log.violations,[]); await P.close(); await closeBrowser();
