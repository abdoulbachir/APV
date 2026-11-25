import assert from 'node:assert/strict';
import { test, before, after } from 'node:test';
import { createServer } from 'vite';

let server, createSessionStore, createIdentityClient;
before(async()=>{
 server=await createServer({configFile:'vite.config.ts',server:{middlewareMode:true,hmr:false,watch:null},logLevel:'silent'});
 ({createSessionStore}=await server.ssrLoadModule('/src/identity/session-store.ts'));
 ({createIdentityClient}=await server.ssrLoadModule('/src/api/identity-client.ts'));
});
after(async()=>{await server?.close();});
const account=(id='X')=>({id,email:id+'@example.invalid',emailVerified:false,displayName:id,role:'donor',permissions:['account.read_own','account.change_password'],mfa:{required:false,enrolled:false,recoveryCodesRemaining:null},locale:'en',createdAt:'2026-11-25T00:00:00Z',session:{idleExpiresAt:'2026-11-25T02:00:00Z',absoluteExpiresAt:'2026-11-26T00:00:00Z'}});
const json=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
const refused=code=>json({error:{code,message:'test'}},401);
const csrf=()=>json({headerName:'X-CSRF-TOKEN',token:'test-token-000000001'});
function harness(responses){
 const calls=[];
 const client=createIdentityClient(async(path,options)=>{
  calls.push([options.method,path]);
  assert.ok(responses.length,'unexpected request');
  return await responses.shift();
 });
 return {store:createSessionStore(client),calls};
}
function deferred(){let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};}

test('initial load discovers session without creating an anonymous CSRF session',async()=>{
 const {store,calls}=harness([refused('unauthenticated')]);
 assert.equal(store.getSnapshot().reading.status,'checking');
 await store.refresh('en');
 assert.equal(store.getSnapshot().reading.status,'anonymous');
 assert.deepEqual(calls,[['GET','/api/me']]);
});
test('suspend clears account synchronously, invalidates form leases and notifies subscribers',async()=>{
 const {store}=harness([json(account()),csrf(),json(account())]);
 let updates=0;const unsubscribe=store.subscribe(()=>updates++);
 await store.refresh('en');
 const lease=await store.prepare('en');
 assert.equal(store.isCurrent(lease.generation),true);
 assert.equal(store.getSnapshot().reading.account.id,'X');
 store.suspend();
 assert.equal(store.getSnapshot().reading.status,'checking');
 assert.equal('account' in store.getSnapshot().reading,false);
 assert.equal(store.isCurrent(lease.generation),false);
 assert.ok(updates>=3);
 unsubscribe();
});
test('late GET /me result cannot resurrect a prior identity',async()=>{
 const first=deferred();
 const {store}=harness([first.promise,json(account('Y'))]);
 const slow=store.refresh('en');
 await store.refresh('en');
 first.resolve(json(account('X')));
 await slow;
 assert.equal(store.getSnapshot().reading.account.id,'Y');
});
test('a failed refresh clears stale account and fails closed',async()=>{
 const {store}=harness([json(account()),json({error:{code:'unavailable',message:'test'}},503)]);
 await store.refresh('en');
 await store.refresh('en');
 assert.equal(store.getSnapshot().reading.status,'unavailable');
 assert.equal('account' in store.getSnapshot().reading,false);
});
test('pending sign-in has no account or permission data and invalidation revokes equal-state leases',async()=>{
 const {store}=harness([refused('mfa_required'),csrf(),refused('mfa_required'),refused('mfa_required')]);
 await store.refresh('fr');
 const lease=await store.prepare('fr');
 assert.deepEqual(store.getSnapshot().reading,{status:'mfa_required'});
 await store.refresh('fr');
 assert.deepEqual(store.getSnapshot().reading,{status:'mfa_required'});
 assert.equal(store.isCurrent(lease.generation),false);
});
test('form preparation detects a changed identity before enabling secret entry',async()=>{
 const {store,calls}=harness([json(account()),csrf(),json(account('Y'))]);
 await store.refresh('en');
 const old=store.getSnapshot().generation;
 await assert.rejects(store.prepare('en'),{code:'session_refresh_required'});
 assert.equal(store.getSnapshot().reading.account.id,'Y');
 assert.equal(store.isCurrent(old),false);
 assert.deepEqual(calls.map(x=>x[1]),['/api/me','/api/auth/csrf','/api/me']);
});
test('late form preparation is rejected after visibility or cross-tab invalidation',async()=>{
 const first=deferred();
 const {store}=harness([json(account()),csrf(),first.promise]);
 await store.refresh('en');
 const pending=store.prepare('en');
 store.suspend();
 first.resolve(json(account()));
 await assert.rejects(pending,{code:'session_refresh_required'});
 assert.equal(store.getSnapshot().reading.status,'checking');
});
test('no form token is acquired when state is not verified',async()=>{
 const {store,calls}=harness([]);
 await assert.rejects(store.prepare('en'),{code:'session_refresh_required'});
 assert.equal(calls.length,0);
});
