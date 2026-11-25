import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFileSync} from 'node:fs';
import {createServer} from 'vite';

test('fresh documents declare and serve a same-origin favicon',async()=>{
 const server=await createServer({configFile:'vite.config.ts',server:{host:'127.0.0.1',port:0,strictPort:true,watch:null},logLevel:'silent'});
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
