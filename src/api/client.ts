import { APP_CONFIG } from '../config';
import { publicReadPaths, type PublicReadOperations } from './generated/public-api';
import type { CaseQuery, Locale, PageQuery } from './public-types';
export class ApiError extends Error {
  constructor(public code:string,message:string,public status:number){super(message);}
}
export interface ApiClient {
  getSite(locale:Locale):Promise<PublicReadOperations['getSite']['response']>;
  getStats(locale:Locale):Promise<PublicReadOperations['getStats']['response']>;
  getCases(query:CaseQuery,locale:Locale):Promise<PublicReadOperations['listCases']['response']>;
  getCase(code:string,locale:Locale):Promise<PublicReadOperations['getCase']['response']>;
  getUpdates(code:string,query:PageQuery,locale:Locale):Promise<PublicReadOperations['listCaseUpdates']['response']>;
  getDonors(code:string,query:PageQuery,locale:Locale):Promise<PublicReadOperations['listCaseDonors']['response']>;
  getLedger(code:string,query:PageQuery,locale:Locale):Promise<PublicReadOperations['listCaseLedger']['response']>;
}
function params(query:CaseQuery|PageQuery){const p=new URLSearchParams();for(const [key,value] of Object.entries(query))if(value!==undefined&&value!=='')p.set(key,String(value));return p.size?'?'+p:'';}
async function request<T>(path:string,locale:Locale):Promise<T>{
 const response=await fetch(APP_CONFIG.apiBaseUrl+path,{credentials:'include',headers:{Accept:'application/json','Accept-Language':locale}});
 if(!response.ok){const body=await response.json().catch(()=>null);throw new ApiError(body?.error?.code??'unknown',body?.error?.message??response.statusText,response.status);}
 return response.json() as Promise<T>;
}
const casePath=(template:string,code:string)=>template.replace('{code}',encodeURIComponent(code));
const realClient:ApiClient={
 getSite:l=>request(publicReadPaths.getSite,l),getStats:l=>request(publicReadPaths.getStats,l),getCases:(q,l)=>request(publicReadPaths.listCases+params(q),l),getCase:(c,l)=>request(casePath(publicReadPaths.getCase,c),l),
 getUpdates:(c,q,l)=>request(casePath(publicReadPaths.listCaseUpdates,c)+params(q),l),getDonors:(c,q,l)=>request(casePath(publicReadPaths.listCaseDonors,c)+params(q),l),getLedger:(c,q,l)=>request(casePath(publicReadPaths.listCaseLedger,c)+params(q),l),
};
async function loadMock(){return (await import('./mock-client')).mockClient;}
export const api:ApiClient=APP_CONFIG.apiMode==='real'?realClient:{
 getSite:async l=>(await loadMock()).getSite(l),getStats:async l=>(await loadMock()).getStats(l),getCases:async(q,l)=>(await loadMock()).getCases(q,l),getCase:async(c,l)=>(await loadMock()).getCase(c,l),
 getUpdates:async(c,q,l)=>(await loadMock()).getUpdates(c,q,l),getDonors:async(c,q,l)=>(await loadMock()).getDonors(c,q,l),getLedger:async(c,q,l)=>(await loadMock()).getLedger(c,q,l)
};
