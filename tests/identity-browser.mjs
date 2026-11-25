// Browser-only frontend evidence against real-mode dist and actual HTTP CSP.
 // Identity responses are deliberately intercepted, NOT a backend acceptance test.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { pathToFileURL } from 'node:url';
import { contentSecurityPolicy } from '../security/content-policy.mjs';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
const origin='http://127.0.0.1:4179';
const server=spawn(process.execPath,['node_modules/vite/bin/vite.js','preview','--host','127.0.0.1','--port','4179','--strictPort'],{stdio:'ignore',env:{...process.env,VITE_API_MODE:'real'}});
let browser;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const fixtures={
 password:'fictional-browser-password-0001',
 newPassword:'fictional-browser-password-0002',
 credential:'fictional-operator-credential-0001',
 secret:'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567',
 token:'fictional-browser-token-0001',
 fresh:'fictional-browser-token-0002',
 codes:['AAAA-AAAA-AAAA-AAAA','BBBB-BBBB-BBBB-BBBB','CCCC-CCCC-CCCC-CCCC','DDDD-DDDD-DDDD-DDDD','EEEE-EEEE-EEEE-EEEE','FFFF-FFFF-FFFF-FFFF','GGGG-GGGG-GGGG-GGGG','HHHH-HHHH-HHHH-HHHH','IIII-IIII-IIII-IIII','JJJJ-JJJJ-JJJJ-JJJJ'],
};
const account=(role='donor')=>({id:'acc_abcdefghijklmnopqrstuv',email:'browser@example.invalid',emailVerified:false,displayName:'Browser Test',role,permissions:['account.read_own','account.change_password'],mfa:{required:role!=='donor',enrolled:role!=='donor',recoveryCodesRemaining:role==='donor'?null:10},locale:'en',createdAt:'2026-11-25T00:00:00Z',session:{idleExpiresAt:'2026-11-25T02:00:00Z',absoluteExpiresAt:'2026-11-26T00:00:00Z'}});
const codes=()=>({codes:fixtures.codes,generatedAt:'2026-11-25T00:00:00Z'});
const errors=[];
const paths=[];
const secrets=[fixtures.password,fixtures.newPassword,fixtures.credential,fixtures.secret,fixtures.token,fixtures.fresh,...fixtures.codes];
async function run(locale,theme,width){
 const context=await browser.newContext({viewport:{width,height:900},locale:locale==='fr'?'fr-FR':'en-US'});
 const page=await context.newPage();
 let state='anonymous', role='donor',registration=true, stale=false, wrong=false,offline=false;
 let token=fixtures.token;
 const calls=[];
 const violations=[];
 await page.exposeFunction('apvTestCspViolation',directive=>{violations.push(directive);});
 await page.addInitScript(({locale,theme})=>{
  localStorage.setItem('apv-locale',locale);localStorage.setItem('apv-theme',theme);
  document.addEventListener('securitypolicyviolation',event=>window.apvTestCspViolation(event.violatedDirective));
 },{locale,theme});
 page.on('pageerror',()=>errors.push('pageerror'));
 page.on('console',m=>{if(secrets.some(s=>m.text().includes(s)))errors.push('secret in console');});
 page.on('request',request=>{
  const url=request.url();
  if(!url.startsWith(origin+'/'))errors.push('unexpected origin');
  if(secrets.some(s=>url.includes(s)))errors.push('secret in request URL');
 });
 await page.route('**/api/**',async route=>{
  const req=route.request(), path=new URL(req.url()).pathname, method=req.method();
  calls.push({path,method});
  const send=(status,value)=>route.fulfill({status,headers:{'Cache-Control':'no-store','Content-Type':'application/json'},...(status===204?{}:{body:JSON.stringify(value)})});
  const fail=(status,code)=>send(status,{error:{code,message:'test refusal'}});
  if(offline)return fail(503,'unavailable');
  if(path==='/api/me')return state==='authenticated'?send(200,account(role)):fail(401,state==='anonymous'?'unauthenticated':state);
  if(path==='/api/auth/csrf')return send(200,{headerName:'X-CSRF-TOKEN',token});
  if(path==='/api/auth/options')return send(200,{registrationOpen:registration});
  if(method==='POST'){
   if(stale){stale=false;token=fixtures.fresh;state='anonymous';return fail(403,'csrf_invalid');}
   if(req.headers()['x-csrf-token']!==token)return fail(403,'csrf_invalid');
   if(wrong){wrong=false;return fail(401,path.includes('/mfa/')?'mfa_code_invalid':'invalid_credentials');}
   if(path.endsWith('/login')){state=role==='donor'?'authenticated':'mfa_required';return send(200,state==='authenticated'?{status:state,account:account(role)}:{status:state,pendingExpiresAt:'2026-11-25T00:10:00Z'});}
   if(path.endsWith('/register'))return registration?send(202,{status:'accepted'}):fail(403,'registration_closed');
   if(path.endsWith('/logout')){state='anonymous';return send(204);}
   if(path.endsWith('/change-password'))return send(204);
   if(path.endsWith('/set-password')){state='anonymous';return send(204);}
   if(path.endsWith('/verify')){state='authenticated';return send(200,{status:state,account:account(role)});}
   if(path.endsWith('/enroll'))return send(200,{secret:fixtures.secret,otpauthUri:'otpauth://totp/APV:browser?secret='+fixtures.secret+'&issuer=APV',expiresAt:'2026-11-25T00:10:00Z'});
   if(path.endsWith('/confirm')){state='authenticated';return send(200,codes());}
   if(path.endsWith('/recovery-codes'))return send(200,codes());
  }
  return fail(404,'not_found');
 });
 async function go(path,selector){
  const response=await page.goto(origin+path,{waitUntil:'domcontentloaded'});
  assert.equal(response.headers()['content-security-policy'],contentSecurityPolicy);
  await page.locator(selector).first().waitFor({state:'visible'});
  assert.equal(await page.locator('html').getAttribute('data-theme'),theme);
  assert.equal(await page.locator('html').getAttribute('lang'),locale);
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'viewport overflow');
  paths.push(path.split('#')[0]);
 }
 async function fill(name,value){await page.locator('[name="'+name+'"]').fill(value);}
 async function submit(){await page.locator('form button[type=submit]').click();}
 async function expectPath(path){await page.waitForURL(origin+path);}
 try{
  await go('/sign-in','input[name=password]');
  await fill('email','browser@example.invalid');await fill('password',fixtures.password);
  wrong=true;await submit();await page.locator('[role=alert]').first().waitFor({state:'visible'});
  assert.equal(await page.locator('[name=password]').inputValue(),'');
  const before=calls.filter(c=>c.method==='POST').length;
  stale=true;await fill('password',fixtures.password);await submit();
  await page.locator('.identity-notice').waitFor({state:'visible'});
  await page.locator('[name=password]').waitFor({state:'visible'});
  assert.equal(calls.filter(c=>c.method==='POST').length,before+1,'stale POST replayed');
  await fill('email','browser@example.invalid');await fill('password',fixtures.password);await submit();
  await expectPath('/account');await page.locator('.identity-account').waitFor({state:'visible'});
  await go('/account/password','input[name=currentPassword]');
  wrong=true;await fill('currentPassword',fixtures.password);await fill('newPassword',fixtures.newPassword);await submit();
  await page.locator('[role=alert]').first().waitFor({state:'visible'});
  assert.equal(new URL(page.url()).pathname,'/account/password');
  assert.equal(await page.locator('[name=currentPassword]').inputValue(),'');
  await fill('currentPassword',fixtures.password);await fill('newPassword',fixtures.newPassword);await submit();await expectPath('/account');
  await page.locator('.identity-primary').click();await expectPath('/sign-in');
  registration=false;await go('/sign-up','.identity-panel h1');
  await page.locator('.identity-panel p').filter({hasText:locale==='fr'?'inscriptions publiques':'Public registration'}).waitFor({state:'visible'});
  assert.equal(await page.locator('[name=password]').count(),0);
  registration=true;await go('/sign-up','[name=displayName]');
  await fill('displayName','Browser Test');await fill('email','browser@example.invalid');await fill('password',fixtures.password);await submit();await expectPath('/sign-in');
  await go('/auth/set-password#'+fixtures.credential,'[name=credential]');
  assert.equal(new URL(page.url()).hash,'');
  await fill('credential',fixtures.credential);await fill('newPassword',fixtures.newPassword);await submit();await expectPath('/sign-in');
  role='admin';state='mfa_required';await go('/auth/mfa','[name=code]');
  wrong=true;await fill('code','012345');await submit();await page.locator('[role=alert]').first().waitFor({state:'visible'});
  assert.equal(await page.locator('[name=code]').inputValue(),'');
  await fill('code','012345');await submit();await expectPath('/account');
  state='mfa_enrollment_required';await go('/auth/enroll','.identity-primary');
  await page.locator('.identity-primary').first().click();await page.locator('svg.identity-qr path').waitFor({state:'visible'});
  assert.equal(await page.locator('.identity-secret').textContent(),fixtures.secret);
  await fill('code','012345');await submit();await page.locator('.identity-codes').waitFor({state:'visible'});
  assert.equal(await page.locator('.identity-codes li').count(),10);
  await page.locator('.identity-primary').click();await expectPath('/account');
  assert.equal(await page.locator('.identity-codes').count(),0);
  await go('/account/recovery-codes','[name=currentPassword]');
  await fill('currentPassword',fixtures.password);await fill('code','012345');await submit();
  await page.locator('.identity-codes').waitFor({state:'visible'});
  await page.locator('.identity-primary').click();await expectPath('/account');
  offline=true;await go('/account','[role=alert]');
  assert.equal(await page.locator('.identity-account').count(),0);
  const storage=await page.evaluate(()=>({local:Object.keys(localStorage).sort(),session:Object.keys(sessionStorage),cookies:document.cookie}));
  assert.deepEqual(storage.local,['apv-locale','apv-theme']);assert.deepEqual(storage.session,[]);assert.equal(storage.cookies,'');
  assert.deepEqual(violations,[],'CSP violations');
 }finally{await context.close();}
}
try{
 for(let i=0;i<100;i++){
  try{const response=await fetch(origin);if(response.ok)break;}catch{}
  if(i===99)throw new Error('preview did not start');await sleep(100);
 }
 browser=await chromium.launch({headless:true});
 for(const locale of ['en','fr'])for(const theme of ['light','dark'])for(const width of [360,1440]){
  await run(locale,theme,width);console.log('PASS intercepted browser journey: '+locale+' '+theme+' '+width);
 }
 assert.deepEqual(errors,[]);console.log('PASS production CSP / identity routes ('+paths.length+' page loads); intercepted responses only');
}finally{await browser?.close();if(server.exitCode===null){server.kill();await once(server,'exit');}}
