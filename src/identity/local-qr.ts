import qrcode from 'qrcode-generator';

// Pure local matrix encoding. No data URL, HTML injection, remote image, canvas
// serialization, storage or network requests. Four white modules of quiet zone.
export function makeAuthenticatorQr(uri:string){
 if(!uri.startsWith('otpauth://totp/')||uri.length>4096)throw new Error('Invalid authenticator address');
 // The issuer/account label should be URI-encoded; encode any remaining
 // non-ASCII characters as UTF-8 bytes, preserving the ASCII URI syntax.
 const bytes=new TextEncoder().encode(uri);
 const encoded=Array.from(bytes,value=>String.fromCharCode(value)).join('');
 const qr=qrcode(0,'M');qr.addData(encoded,'Byte');qr.make();
 const modules=qr.getModuleCount();
 let path='';
 for(let row=0;row<modules;row++)for(let col=0;col<modules;col++){
  if(qr.isDark(row,col))path+='M'+(col+4)+' '+(row+4)+'h1v1h-1z';
 }
 return {size:modules+8,path};
}
