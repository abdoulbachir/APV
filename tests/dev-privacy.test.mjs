import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {createServer} from 'vite';

test('generated fixture is deterministic and matches the accepted M0B bytes',()=>{
 const files=['contract/fixtures/dataset.json','contract/fixtures/dataset.public.json','src/api/dataset.public.json'];
 // Git blob digests from the accepted baseline. Intentionally changed fixture inputs
 // require an explicit golden-data review and an update to these expectations.
 const hashes=['b9018e2bfb2b8040bb2c7c786adfcc8d7727f8db','ab7ae19ba1821b3c285fa75b71349625f14b00cf','ab7ae19ba1821b3c285fa75b71349625f14b00cf'];
 const before=files.map(file=>readFileSync(file));
 execFileSync(process.execPath,['contract/tools/generate-dataset.mjs']);
 files.forEach((file,i)=>{
  const actual=readFileSync(file);
  assert.deepEqual(actual,before[i],file+' changed on regeneration');
  const hash=createHash('sha1').update('blob '+actual.length+'\0').update(actual).digest('hex');
  assert.equal(hash,hashes[i],file+' differs from accepted fixture bytes');
 });
 assert.deepEqual(before[1],before[2]);
});

test('Vite denies server-only files, including raw imports and filesystem URLs',async()=>{
 const server=await createServer({configFile:'vite.config.ts',server:{host:'127.0.0.1',port:0,strictPort:false,hmr:false,watch:null},logLevel:'silent'});
 try{
  await server.listen();
  const base='http://127.0.0.1:'+server.httpServer.address().port;
  const paths=['/contract/fixtures/dataset.json','/contract/fixtures/dataset.json?raw','/contract/fixtures/%64ataset.json','/contract/fixtures/example.json','/contract/fixtures/example.expected.json','/contract/fixtures/golden/acceptance.json','/@fs/'+resolve('contract/fixtures/dataset.json').replaceAll('\\','/'),'/@fs/'+resolve('contract/fixtures/dataset.json').replaceAll('\\','/')+'?raw'];
  for(const path of paths){
   const r=await fetch(base+path);const body=await r.text();
   assert.ok(r.status===403||r.status===404,path+' status '+r.status);
   assert.ok(!body.includes('PRIVATE-REALNAME-'),path+' leaked a private value');
  }
  const response=await fetch(base+'/src/api/dataset.public.json?import');
  assert.equal(response.status,200);
  const text=await response.text();
  assert.ok(text.includes('HELP-014'));
  assert.ok(!text.includes('PRIVATE-REALNAME-')&&!text.includes('HELP-902'));
  assert.equal((await fetch(base+'/src/api/mock-client.ts')).status,200);
 }finally{await server.close();}
});

// Keep HTTP server tests in one file so Node runs them sequentially.
test('fresh documents declare and serve a same-origin favicon',async()=>{
 const server=await createServer({configFile:'vite.config.ts',server:{host:'127.0.0.1',port:0,strictPort:false,hmr:false,watch:null},logLevel:'silent'});
 try{
  await server.listen();
  const base='http://127.0.0.1:'+server.httpServer.address().port;
  for(const path of ['/','/cases','/cases/HELP-014']){
   const response=await fetch(base+path);
   assert.equal(response.status,200);
   assert.match(await response.text(),/<link rel="icon" type="image\/svg\+xml" href="\/favicon\.svg"\s*\/>/);
  }
  const response=await fetch(base+'/favicon.svg');
  assert.equal(response.status,200);
  assert.match(response.headers.get('content-type')??'',/^image\/svg\+xml/);
  assert.equal(await response.text(),readFileSync('public/favicon.svg','utf8'));
 }finally{await server.close();}
});
