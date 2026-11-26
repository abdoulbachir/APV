// Env A: public pages under the enforced policy with a signed-in donor's cookie present.
// (Repeat of one check whose first run failed on an error in the harness itself: a browser global used in Node.)
import crypto from 'node:crypto';
import { assert, check, resetThrottle, profile, closeBrowser, secret, waitHeading, waitForm, fill, submit, settle, signIn } from './lib.mjs';
const ENV='A: dev profile, HTTP preview with enforced policy', DB='apv-m1b-accept-dev', origin='http://127.0.0.1:4173'; resetThrottle(DB);
const P=await profile(origin); const page=await P.open('/sign-up'); await waitHeading(page,'Create an account'); await waitForm(page);
const email=`donor.${crypto.randomBytes(3).toString('hex')}@example.test`, password=secret('password','Rk6 walnut-ember pelican 3307');
await fill(page,{displayName:'Fictional Reader',email,password}); await submit(page); await settle(page); await signIn(page,email,password); await waitHeading(page,'My account');
const before=P.log.violations.length, t=Date.now();
await check('public',ENV,'Public pages under the enforced policy while a signed-in session cookie is present: home, case directory, one case; pictures load from the same origin; public reads set no cookie; no policy violation',async()=>{
  const counts=[];
  for(const p of ['/','/cases','/cases/HELP-014']){ await page.goto(origin+p,{waitUntil:'networkidle'}); const text=await page.locator('#root').innerText(); assert.ok(text.length>200,p+' is nearly empty'); counts.push(text.length); }
  const images=await page.evaluate(()=>[...document.images].map(i=>({src:i.currentSrc,ok:i.complete&&i.naturalWidth>0})));
  assert.ok(images.length>0&&images.every(i=>i.ok&&i.src.startsWith(origin)),JSON.stringify(images.slice(0,3)));
  const api=P.log.responses.filter(r=>r.at>=t&&!r.path.startsWith('/api/auth')&&r.path!=='/api/me'); assert.ok(api.length>=3&&api.every(r=>r.status===200),JSON.stringify(api.map(r=>[r.path,r.status])));
  assert.ok(api.every(r=>!r.headers['set-cookie']),'a public read set a cookie'); assert.deepEqual(P.log.violations.slice(before),[]); assert.deepEqual(P.log.external,[]);
  const identityCalls=P.log.responses.filter(r=>r.at>=t&&(r.path.startsWith('/api/auth')||r.path==='/api/me')).length;
  return `${api.length} public API reads all 200, ${images.length} pictures on the case page, ${identityCalls} identity calls made by public pages`;
});
await P.close(); await closeBrowser();
