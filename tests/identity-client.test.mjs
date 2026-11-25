import assert from 'node:assert/strict';
import { test, before, after } from 'node:test';
import { createServer } from 'vite';

let server, mod;
before(async()=>{
 server=await createServer({configFile:'vite.config.ts',server:{middlewareMode:true,hmr:false,watch:null},logLevel:'silent'});
 mod=await server.ssrLoadModule('/src/api/identity-client.ts');
});
after(async()=>{await server?.close();});

const token='token-for-test-0001',fresh='token-for-test-0002';
const json=(body,status=200,headers={})=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json',...headers}});
const failure=(code,status=401)=>json({error:{code,message:'Do not expose raw error text'}},status);
const csrf=value=>json({headerName:'X-CSRF-TOKEN',token:value});
const anonymous=()=>failure('unauthenticated');
const account={id:'account-X',email:'x@example.invalid',emailVerified:false,displayName:'X',role:'donor',permissions:['account.read_own','account.change_password'],mfa:{required:false,enrolled:false,recoveryCodesRemaining:null},locale:'en',createdAt:'2026-11-25T00:00:00Z',session:{idleExpiresAt:'2026-11-25T02:00:00Z',absoluteExpiresAt:'2026-11-26T00:00:00Z'}};
const form={csrf:{headerName:'X-CSRF-TOKEN',token},session:{status:'anonymous'}};
function scripted(responses) {
 const calls=[];
 const client=mod.createIdentityClient(async(path,options)=>{
  calls.push({path,...options});
  assert.ok(responses.length,'unexpected extra request: '+path);
  const next=responses.shift();
  if(next instanceof Error)throw next;
  return next;
 });
 return {client,calls,assertDone:()=>assert.equal(responses.length,0)};
}
const mutations=[
 ['register','/api/auth/register',{email:'x@example.invalid',password:'a long test password',displayName:'X'},202,{status:'accepted'}],
 ['login','/api/auth/login',{email:'x@example.invalid',password:'a long test password'},200,{status:'authenticated',account}],
 ['logout','/api/auth/logout',null,204,null],
 ['changePassword','/api/auth/change-password',{currentPassword:'old password',newPassword:'new long password'},204,null],
 ['setPasswordWithCredential','/api/auth/set-password',{credential:'test-only',newPassword:'new long password'},204,null],
 ['verifyMfa','/api/auth/mfa/verify',{method:'totp',code:'012345'},200,{status:'authenticated',account}],
 ['beginMfaEnrollment','/api/auth/mfa/enroll',null,200,{secret:'test-only',otpauthUri:'otpauth://totp/test',expiresAt:'2026-11-25T00:10:00Z'}],
 ['confirmMfaEnrollment','/api/auth/mfa/confirm',{code:'012345'},200,{codes:[],generatedAt:'2026-11-25T00:00:00Z'}],
 ['regenerateRecoveryCodes','/api/auth/mfa/recovery-codes',{currentPassword:'test password',code:'012345'},200,{codes:[],generatedAt:'2026-11-25T00:00:00Z'}],
];

test('all three identity reads use same-origin no-store GET without a token',async()=>{
 const {client,calls,assertDone}=scripted([csrf(token),json({registrationOpen:false}),json(account)]);
 assert.equal((await client.read('getCsrfToken','fr')).token,token);
 assert.deepEqual(await client.read('getAuthOptions','fr'),{registrationOpen:false});
 assert.deepEqual(await client.read('getMyAccount','fr'),account);
 assert.deepEqual(calls.map(x=>x.path),['/api/auth/csrf','/api/auth/options','/api/me']);
 for(const call of calls){
  assert.equal(call.method,'GET');assert.equal(call.body,undefined);
  assert.equal(call.headers['X-CSRF-TOKEN'],undefined);
  assert.equal(call.headers['Accept-Language'],'fr');
  assert.equal(call.credentials,'same-origin');assert.equal(call.cache,'no-store');
  assert.equal(call.redirect,'error');assert.equal(call.referrerPolicy,'no-referrer');
 }
 assertDone();
});
test('all nine identity mutations use exact paths, JSON only where specified and one POST',async()=>{
 for(const [op,path,body,status,result] of mutations){
  const {client,calls,assertDone}=scripted([status===204?new Response(null,{status}):json(result,status)]);
  assert.deepEqual(await mod.submitIdentityOnce(client,op,body,'en',form),{kind:'success',value:result});
  assert.equal(calls.length,1);assert.equal(calls[0].path,path);
  assert.equal(calls[0].method,'POST');assert.equal(calls[0].headers['X-CSRF-TOKEN'],token);
  assert.equal(calls[0].headers['Content-Type'],body===null?undefined:'application/json');
  assert.equal(calls[0].body,body===null?undefined:JSON.stringify(body));assertDone();
 }
});
test('all nine stale-token refusals refresh then reconcile, never replay',async()=>{
 for(const [op,path,body] of mutations){
  const {client,calls,assertDone}=scripted([failure('csrf_invalid',403),csrf(fresh),anonymous()]);
  const result=await mod.submitIdentityOnce(client,op,body,'en',form);
  assert.equal(result.kind,'refused');assert.equal(result.error.code,'csrf_invalid');
  assert.equal(result.reconciliation.kind,'prepared');
  assert.equal(result.reconciliation.form.csrf.token,fresh);
  assert.equal(result.reconciliation.form.session.status,'anonymous');
  assert.deepEqual(calls.map(x=>[x.method,x.path]),[['POST',path],['GET','/api/auth/csrf'],['GET','/api/me']]);assertDone();
 }
});
test('pending states, lost session and fully signed-in state are distinct',async()=>{
 for(const [code,status] of [['unauthenticated','anonymous'],['mfa_required','mfa_required'],['mfa_enrollment_required','mfa_enrollment_required']]){
  const {client,calls}=scripted([failure(code)]);
  assert.deepEqual(await mod.readIdentitySession(client,'en'),{status});
  assert.equal(calls.length,1);
 }
 const {client}=scripted([json(account)]);
 assert.deepEqual(await mod.readIdentitySession(client,'en'),{status:'authenticated',account});
});
test('prepare a form before accepting secrets: token first, then state discovery',async()=>{
 const {client,calls}=scripted([csrf(fresh),json(account)]);
 assert.deepEqual(await mod.prepareIdentityForm(client,'en'),{csrf:{headerName:'X-CSRF-TOKEN',token:fresh},session:{status:'authenticated',account}});
 assert.deepEqual(calls.map(x=>x.path),['/api/auth/csrf','/api/me']);
});
test('wrong secret and origin refusal do not sign out or replay',async()=>{
 for(const [code,status] of [['invalid_credentials',401],['mfa_code_invalid',401],['forbidden',403],['registration_closed',403]]){
  const {client,calls}=scripted([failure(code,status)]);
  const result=await mod.submitIdentityOnce(client,'changePassword',{currentPassword:'x',newPassword:'y'},'en',form);
  assert.equal(result.kind,'refused');assert.equal(result.error.code,code);
  assert.equal(result.reconciliation,undefined);assert.equal(calls.length,1);
 }
});
test('lost session and already-signed-in responses reconcile with one GET /me',async()=>{
 for(const [code,status] of [['unauthenticated',401],['mfa_required',401],['mfa_enrollment_required',401],['already_signed_in',409]]){
  const {client,calls}=scripted([failure(code,status),json(account)]);
  const result=await mod.submitIdentityOnce(client,'logout',null,'en',form);
  assert.equal(result.kind,'refused');assert.equal(result.reconciliation.kind,'session');
  assert.deepEqual(calls.map(x=>x.path),['/api/auth/logout','/api/me']);
 }
});
test('lost response never repeats a submitted secret; unavailable reconciliation fails closed',async()=>{
 const {client,calls}=scripted([new Error('secret transport error'),new Error('private network error')]);
 const result=await mod.submitIdentityOnce(client,'login',{email:'x@example.invalid',password:'secret'},'en',form);
 assert.equal(result.kind,'uncertain');assert.equal(result.error.message,'network_error');
 assert.equal(result.reconciliation.kind,'unavailable');
 assert.deepEqual(calls.map(x=>x.path),['/api/auth/login','/api/me']);
});
test('missing token fails locally; server error text and unknown field names are discarded',async()=>{
 const {client,calls}=scripted([json({error:{code:'validation_error',message:'secret',fields:[{name:'password',code:'too_short'},{name:'secret',code:'unknown_field'}]}},400,{'Retry-After':'5'})]);
 await assert.rejects(client.post('logout',null,'en',''),{code:'session_refresh_required'});
 assert.equal(calls.length,0);
 await assert.rejects(client.post('login',{email:'x@example.invalid',password:'x'},'en',token),error=>{
  assert.equal(error.message,'validation_error');assert.deepEqual(error.fields,[{name:'password',code:'too_short'}]);assert.equal(error.retryAfterSeconds,5);return true;
 });
});
test('malformed token and unexpected success status fail closed',async()=>{
 const {client}=scripted([json({headerName:'Other',token}),json({},202)]);
 await assert.rejects(client.read('getCsrfToken','en'),{code:'invalid_response'});
 await assert.rejects(client.read('getMyAccount','en'),{code:'invalid_response'});
});
