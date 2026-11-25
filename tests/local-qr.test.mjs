import assert from 'node:assert/strict';
import { test, before, after } from 'node:test';
import { createServer } from 'vite';
import qrcode from 'qrcode-generator';
let server, makeAuthenticatorQr;
before(async()=>{
 server=await createServer({configFile:'vite.config.ts',server:{middlewareMode:true,hmr:false,watch:null},logLevel:'silent'});
 ({makeAuthenticatorQr}=await server.ssrLoadModule('/src/identity/local-qr.ts'));
});
after(async()=>{await server?.close();});
test('local authenticator QR has the same module matrix as the encoder and four-module quiet zone',()=>{
 const uri='otpauth://totp/APV:fictional%40example.invalid?secret=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&issuer=APV';
 const result=makeAuthenticatorQr(uri);
 const qr=qrcode(0,'M');qr.addData(uri,'Byte');qr.make();
 assert.equal(result.size,qr.getModuleCount()+8);
 const modules=new Set([...result.path.matchAll(/M(\d+) (\d+)h1v1h-1z/g)].map(m=>m[1]+','+m[2]));
 for(let row=0;row<qr.getModuleCount();row++)for(let col=0;col<qr.getModuleCount();col++)
  assert.equal(modules.has((col+4)+','+(row+4)),qr.isDark(row,col));
 assert.ok(!result.path.includes(uri));
 assert.deepEqual(makeAuthenticatorQr(uri),result);
 assert.notDeepEqual(makeAuthenticatorQr(uri.replace('234567','765432')),result);
});
test('unexpected or excessively long QR input is rejected',()=>{
 assert.throws(()=>makeAuthenticatorQr('https://example.invalid/secret'));
 assert.throws(()=>makeAuthenticatorQr('otpauth://totp/'+ 'a'.repeat(4096)));
});
