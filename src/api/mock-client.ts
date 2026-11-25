import dataset from '../../contract/fixtures/dataset.public.json';
import { APP_CONFIG } from '../config';
import { ApiError, type ApiClient } from './client';
import { createMockModel, ModelError, type PublicFixture } from './mock-model';
const model=createMockModel(dataset as PublicFixture);
async function result<T>(fn:()=>T):Promise<T>{
 await new Promise(resolve=>window.setTimeout(resolve,APP_CONFIG.mockDelayMs));
 if(import.meta.env.DEV&&new URLSearchParams(window.location.search).get('mockError')==='1')throw new ApiError('mock_network_error','Simulated connection error',503);
 try{return structuredClone(fn());}catch(e){if(e instanceof ModelError)throw new ApiError(e.code,e.message,e.status);throw e;}
}
export const mockClient:ApiClient={
 getSite:()=>result(model.site),getStats:()=>result(model.stats),getCases:(q,l)=>result(()=>model.list(q,l)),getCase:(c,l)=>result(()=>model.detail(c,l)),
 getUpdates:(c,q,l)=>result(()=>model.updates(c,l,q)),getDonors:(c,q)=>result(()=>model.donors(c,q)),getLedger:(c,q,l)=>result(()=>model.ledger(c,l,q)),
};
