import { APP_CONFIG } from '../config';
import type { Case, CaseDetail, CaseQuery, CaseUpdate, LedgerEntry, Locale, Page, PageQuery, PublicDonor, Site, Stats } from './public-types';
export class ApiError extends Error {
  constructor(public code:string,message:string,public status:number){super(message);}
}
export interface ApiClient {
  getSite(locale:Locale):Promise<Site>;
  getStats(locale:Locale):Promise<Stats>;
  getCases(query:CaseQuery,locale:Locale):Promise<Page<Case>>;
  getCase(code:string,locale:Locale):Promise<CaseDetail>;
  getUpdates(code:string,query:PageQuery,locale:Locale):Promise<Page<CaseUpdate>>;
  getDonors(code:string,query:PageQuery,locale:Locale):Promise<Page<PublicDonor>>;
  getLedger(code:string,query:PageQuery,locale:Locale):Promise<Page<LedgerEntry>>;
}
function params(query:CaseQuery|PageQuery){const p=new URLSearchParams();for(const [key,value] of Object.entries(query))if(value!==undefined&&value!=='')p.set(key,String(value));return p.size?'?'+p:'';}
async function request<T>(path:string,locale:Locale):Promise<T>{
 const response=await fetch(APP_CONFIG.apiBaseUrl+path,{credentials:'include',headers:{Accept:'application/json','Accept-Language':locale}});
 if(!response.ok){const body=await response.json().catch(()=>null);throw new ApiError(body?.error?.code??'unknown',body?.error?.message??response.statusText,response.status);}
 return response.json() as Promise<T>;
}
const casePath=(code:string)=>'/cases/'+encodeURIComponent(code);
const realClient:ApiClient={
 getSite:l=>request('/site',l),getStats:l=>request('/stats',l),getCases:(q,l)=>request('/cases'+params(q),l),getCase:(c,l)=>request(casePath(c),l),
 getUpdates:(c,q,l)=>request(casePath(c)+'/updates'+params(q),l),getDonors:(c,q,l)=>request(casePath(c)+'/donors'+params(q),l),getLedger:(c,q,l)=>request(casePath(c)+'/ledger'+params(q),l),
};
const mockModule=APP_CONFIG.apiMode==='mock'?()=>import('./mock-client'):null;
async function mock<K extends keyof ApiClient>(method:K,...args:Parameters<ApiClient[K]>):Promise<Awaited<ReturnType<ApiClient[K]>>>{
 if(!mockModule)throw new Error('Mock mode disabled');
 const client=(await mockModule()).mockClient;
 const call=client[method] as (...a:Parameters<ApiClient[K]>)=>ReturnType<ApiClient[K]>;
 return await call(...args) as Awaited<ReturnType<ApiClient[K]>>;
}
export const api:ApiClient=APP_CONFIG.apiMode==='real'?realClient:{
 getSite:l=>mock('getSite',l),getStats:l=>mock('getStats',l),getCases:(q,l)=>mock('getCases',q,l),getCase:(c,l)=>mock('getCase',c,l),
 getUpdates:(c,q,l)=>mock('getUpdates',c,q,l),getDonors:(c,q,l)=>mock('getDonors',c,q,l),getLedger:(c,q,l)=>mock('getLedger',c,q,l)
};
