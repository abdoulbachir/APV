// Exercise the actual frontend real-mode API adapter through Vite's same-origin proxy.
// A running development backend with the shared fictional dataset is required.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createServer } from 'vite';
import { createMockModel } from '../src/api/mock-model.ts';

process.env.VITE_API_MODE='real';
process.env.VITE_SAMPLE_DATA='true';
const fixture=JSON.parse(readFileSync(new URL('../src/api/dataset.public.json',import.meta.url),'utf8'));
const model=createMockModel(fixture);
const nativeFetch=globalThis.fetch;
const server=await createServer({configFile:'vite.config.ts',server:{host:'127.0.0.1',port:0,strictPort:true,watch:null},logLevel:'silent'});
let requests=0,comparisons=0;
const pictures=new Set();
function canonical(value){
 if(typeof value==='string'){
  const m=/^\/media\/public\/[^/]+\/(.+)$/.exec(value);
  return m?'/media/public/TOKEN/'+m[1]:value;
 }
 if(Array.isArray(value))return value.map(canonical);
 if(value!==null&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,canonical(v)]));
 return value;
}
function collectPictures(value){
 if(typeof value==='string'&&value.startsWith('/media/public/'))pictures.add(value);
 else if(value&&typeof value==='object')Object.values(value).forEach(collectPictures);
}
function compare(actual,expected,label){
 assert.deepEqual(canonical(actual),canonical(expected),label);
 collectPictures(actual);
 comparisons++;
}
try{
 await server.listen();
 const base='http://127.0.0.1:'+server.httpServer.address().port;
 // Node needs an origin for the browser adapter's relative URLs. No responses are mocked.
 globalThis.fetch=(input,init)=>{
  if(typeof input==='string'&&input.startsWith('/api/')){
   requests++;
   return nativeFetch(base+input,init);
  }
  return nativeFetch(input,init);
 };
 const {APP_CONFIG}=await server.ssrLoadModule('/src/config.ts');
 assert.equal(APP_CONFIG.apiMode,'real');
 const {api}=await server.ssrLoadModule('/src/api/client.ts');
 const page=await nativeFetch(base+'/cases/HELP-014');
 assert.equal(page.status,200);
 assert.match(page.headers.get('content-type')??'',/text\/html/);
 assert.match(await page.text(),/id="root"/);
 const details=['HELP-014','FUND','HELP-101','HELP-102','HELP-103','HELP-104','HELP-105','HELP-108','HELP-903'];
 for(const locale of ['en','fr']){
  compare(await api.getSite(locale),model.site(),'site '+locale);
  compare(await api.getStats(locale),model.stats(),'stats '+locale);
  for(const sort of ['newest','deadline','closest','least','featured']){
   for(const page of [1,17,18]){
    const query={status:'active',sort,page};
    compare(await api.getCases(query,locale),model.list(query,locale),JSON.stringify({query,locale}));
   }
  }
  for(const query of [{status:'completed'},{q:'  ÉLODIE  '},{category:'food'},{status:'completed',category:'transport'}]){
   compare(await api.getCases(query,locale),model.list(query,locale),JSON.stringify({query,locale}));
  }
  for(const code of details){
   compare(await api.getCase(code,locale),model.detail(code,locale),'detail '+code+' '+locale);
   compare(await api.getUpdates(code,{},locale),model.updates(code,locale,{}),'updates '+code+' '+locale);
   compare(await api.getDonors(code,{},locale),model.donors(code,{}),'donors '+code+' '+locale);
   compare(await api.getLedger(code,{page:2,pageSize:3},locale),model.ledger(code,locale,{page:2,pageSize:3}),'ledger '+code+' '+locale);
  }
  for(const code of ['HELP-902','HELP-906','HELP-999999']){
   await assert.rejects(api.getCase(code,locale),{code:'case_not_found',status:404});
  }
  await assert.rejects(api.getCases({category:'unknown'},locale),{code:'validation_error',status:400});
  await assert.rejects(api.getCases({q:'💚'.repeat(81)},locale),{code:'validation_error',status:400});
 }
 assert.equal(requests,comparisons+10,'every comparison and expected error must use live HTTP');
 assert.ok(pictures.size>=4,'exercise public image variants');
 const approved=['hope-window.webp','new-home.webp'].map(name=>readFileSync(new URL('../contract/fixtures/images/'+name,import.meta.url)));
 for(const path of pictures){
  const response=await nativeFetch(base+path);
  assert.equal(response.status,200,path);
  assert.equal(response.headers.get('content-type'),'image/webp',path);
  assert.match(response.headers.get('cache-control')??'',/max-age=300/);
  assert.match(response.headers.get('cache-control')??'',/public/);
  const bytes=Buffer.from(await response.arrayBuffer());
  assert.ok(approved.some(asset=>asset.equals(bytes)),path+' does not match fixture artwork');
 }
 const headers={'Accept-Language':'fr'};
 const response=await nativeFetch(base+'/api/cases/HELP-014',{headers});
 assert.equal(response.status,200);
 assert.equal(response.headers.get('content-language'),'fr');
 assert.match(response.headers.get('vary')??'',/Accept-Language/i);
 assert.match(response.headers.get('cache-control')??'',/no-cache/);
 const etag=response.headers.get('etag');
 assert.ok(etag);
 const revalidated=await nativeFetch(base+'/api/cases/HELP-014',{headers:{...headers,'If-None-Match':etag}});
 assert.equal(revalidated.status,304);
 const privateFixture=await nativeFetch(base+'/contract/fixtures/dataset.json');
 assert.ok([403,404].includes(privateFixture.status));
 console.log('Live API integration: '+comparisons+' exact response comparisons (opaque media tokens masked), 10 expected errors, '+pictures.size+' images, locale/cache headers and private-file protection passed.');
}finally{
 globalThis.fetch=nativeFetch;
 await server.close();
}
