// In-memory identity state. No cookies, storage, secrets or form bodies are
// retained here. Each invalidation drops the old account and revokes form leases.
import { IdentityError, prepareIdentityForm, readIdentitySession, type IdentityClient, type IdentitySession, type PreparedIdentityForm, type SessionReading } from '../api/identity-client';
import type { Locale } from '../api/generated/public-api';

export type SessionSnapshot = {
 generation: number;
 reading: SessionReading | { status:'checking' };
};
export type FormLease = PreparedIdentityForm & { generation:number };

function asError(error:unknown):IdentityError {
 return error instanceof IdentityError ? error : new IdentityError('network_error',0);
}
function ready(reading:SessionSnapshot['reading']):reading is IdentitySession {
 return reading.status!=='checking' && reading.status!=='unavailable';
}
function sameAuthority(left:IdentitySession,right:IdentitySession):boolean {
 if(left.status!==right.status)return false;
 if(left.status!=='authenticated' || right.status!=='authenticated')return true;
 const a=left.account,b=right.account;
 return a.id===b.id && a.role===b.role && a.mfa.required===b.mfa.required &&
  a.mfa.enrolled===b.mfa.enrolled && [...a.permissions].sort().join(',')===[...b.permissions].sort().join(',');
}

export function createSessionStore(client:IdentityClient) {
 let snapshot:SessionSnapshot={generation:0,reading:{status:'checking'}};
 const listeners=new Set<()=>void>();
 function publish(reading:SessionSnapshot['reading'],generation=snapshot.generation) {
  snapshot={generation,reading};
  for(const listener of listeners)listener();
 }
 function suspend() {
  publish({status:'checking'},snapshot.generation+1);
  return snapshot.generation;
 }
 function isCurrent(generation:number) {
  return generation===snapshot.generation && ready(snapshot.reading);
 }
 async function refresh(locale:Locale) {
  const generation=suspend();
  try {
   const session=await readIdentitySession(client,locale);
   if(generation===snapshot.generation)publish(session,generation);
  } catch(error) {
   if(generation===snapshot.generation)publish({status:'unavailable',error:asError(error)},generation);
  }
 }
 async function prepare(locale:Locale):Promise<FormLease> {
  // Only call before exposing editable secret fields, never as a submission
  // fallback. A pending state has no account ID and is not proof of identity.
  const {generation,reading}=snapshot;
  if(!ready(reading))throw new IdentityError('session_refresh_required',0);
  let form:PreparedIdentityForm;
  try {
   form=await prepareIdentityForm(client,locale);
  } catch(error) {
   if(generation===snapshot.generation)publish({status:'unavailable',error:asError(error)},generation+1);
   throw asError(error);
  }
  if(!isCurrent(generation))throw new IdentityError('session_refresh_required',0);
  if(!sameAuthority(reading,form.session)) {
   publish(form.session,generation+1);
   throw new IdentityError('session_refresh_required',0);
  }
  publish(form.session,generation);
  return {...form,generation};
 }
 return {
  subscribe(listener:()=>void){listeners.add(listener);return ()=>{listeners.delete(listener);};},
  getSnapshot:()=>snapshot,
  suspend,refresh,prepare,isCurrent,
 };
}
export type SessionStore=ReturnType<typeof createSessionStore>;
