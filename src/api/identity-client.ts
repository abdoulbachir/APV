// Transport for the accepted v0.3.0 identity contract. No mock fallback, storage,
// automatic redirects or automatic mutation retries. The caller owns form secrets.
import { csrfHeaderName, identityPaths, type IdentityOperations, type Account, type CsrfToken, type SessionState } from './generated/identity-api';
import type { ErrorCode, Locale } from './generated/public-api';

type Operation = keyof IdentityOperations;
export type IdentityRead = { [K in Operation]: IdentityOperations[K]['method'] extends 'GET' ? K : never }[Operation];
export type IdentityMutation = Exclude<Operation, IdentityRead>;
type RequestOf<K extends Operation> = IdentityOperations[K]['request'];
type ResponseOf<K extends Operation> = IdentityOperations[K]['response'];
type FieldIssue = { name: string; code: string };

const methods = {
 getCsrfToken:'GET', getAuthOptions:'GET', getMyAccount:'GET',
 register:'POST', login:'POST', logout:'POST', changePassword:'POST', setPasswordWithCredential:'POST',
 verifyMfa:'POST', beginMfaEnrollment:'POST', confirmMfaEnrollment:'POST', regenerateRecoveryCodes:'POST',
} as const satisfies { [K in Operation]: IdentityOperations[K]['method'] };
const statuses = {
 getCsrfToken:200, getAuthOptions:200, getMyAccount:200, register:202, login:200, logout:204,
 changePassword:204, setPasswordWithCredential:204, verifyMfa:200, beginMfaEnrollment:200,
 confirmMfaEnrollment:200, regenerateRecoveryCodes:200,
} as const satisfies { [K in Operation]: IdentityOperations[K]['status'] };

const errorCodes = [
 'validation_error','case_not_found','not_found','rate_limited','internal_error','unavailable',
 'unauthenticated','mfa_required','mfa_enrollment_required','invalid_credentials','mfa_code_invalid',
 'credential_invalid','forbidden','csrf_invalid','method_not_allowed','unsupported_media_type',
 'payload_too_large','already_signed_in','registration_closed',
] as const satisfies readonly ErrorCode[];
const knownErrors: ReadonlySet<string> = new Set(errorCodes);
const fieldNames = new Set(['email','password','displayName','locale','currentPassword','newPassword','credential','method','code']);
const fieldCodes = new Set(['required','invalid_type','invalid_value','out_of_range','too_long','too_short','invalid_format','unknown_field','compromised','same_as_current']);

export class IdentityError extends Error {
 constructor(
  public readonly code: ErrorCode | 'network_error' | 'invalid_response' | 'session_refresh_required',
  public readonly status: number,
  public readonly fields: readonly FieldIssue[] = [],
  public readonly retryAfterSeconds?: number,
 ) { super(code); this.name='IdentityError'; }
}
function object(value: unknown): value is Record<string, unknown> {
 return typeof value==='object' && value!==null && !Array.isArray(value);
}
async function readFailure(response: Response): Promise<IdentityError> {
 const payload: unknown = await response.json().catch(()=>null);
 const error = object(payload) && object(payload.error) ? payload.error : {};
 const code = typeof error.code==='string' && knownErrors.has(error.code) ? error.code as ErrorCode : 'invalid_response';
 // Never propagate raw error messages, unexpected field names, response bodies or
 // transport exceptions into UI/telemetry: they may contain submitted secrets.
 const fields: FieldIssue[] = [];
 if(Array.isArray(error.fields)) for(const field of error.fields) {
  if(object(field) && typeof field.name==='string' && fieldNames.has(field.name) &&
     typeof field.code==='string' && fieldCodes.has(field.code)) fields.push({name:field.name,code:field.code});
 }
 const raw=response.headers.get('Retry-After');
 const seconds=raw!==null && /^\d+$/.test(raw) ? Number(raw) : undefined;
 return new IdentityError(code,response.status,fields,Number.isSafeInteger(seconds)?seconds:undefined);
}

export function createIdentityClient(fetcher: typeof fetch = (...args)=>fetch(...args)) {
 async function request<K extends Operation>(operation:K, body:RequestOf<K>, locale:Locale, token?:string):Promise<ResponseOf<K>> {
  const method=methods[operation];
  const headers:Record<string,string>={Accept:'application/json','Accept-Language':locale};
  if(method==='POST') {
   if(!token) throw new IdentityError('session_refresh_required',0);
   headers[csrfHeaderName]=token;
   if(body!==null) headers['Content-Type']='application/json';
  }
  let response:Response;
  try {
   response=await fetcher('/api'+identityPaths[operation],{
    method,headers,credentials:'same-origin',cache:'no-store',redirect:'error',referrerPolicy:'no-referrer',
    ...(method==='POST' && body!==null?{body:JSON.stringify(body)}:{}),
   });
  } catch {
   throw new IdentityError('network_error',0);
  }
  if(!response.ok) throw await readFailure(response);
  if(response.status!==statuses[operation]) throw new IdentityError('invalid_response',response.status);
  if(response.status===204) return null as ResponseOf<K>;
  const value:unknown=await response.json().catch(()=>null);
  if(!object(value)) throw new IdentityError('invalid_response',response.status);
  if(operation==='getCsrfToken' && (value.headerName!==csrfHeaderName || typeof value.token!=='string' || value.token.length<16 || value.token.length>512))
   throw new IdentityError('invalid_response',response.status);
  return value as ResponseOf<K>;
 }
 return {
  read<K extends IdentityRead>(operation:K, locale:Locale):Promise<ResponseOf<K>> {
   return request(operation,null as RequestOf<K>,locale);
  },
  post<K extends IdentityMutation>(operation:K, body:RequestOf<K>, locale:Locale, token:string):Promise<ResponseOf<K>> {
   return request(operation,body,locale,token);
  },
 };
}
export type IdentityClient = ReturnType<typeof createIdentityClient>;

export type IdentitySession =
 | { status: 'authenticated'; account: Account }
 | { status: Exclude<SessionState,'authenticated'> };
export type SessionReading = IdentitySession | {status:'unavailable';error:IdentityError};

// /me is the only state discovery operation. An ordinary public visit need not
// acquire an anonymous CSRF session. Pending states intentionally have no ID.
export async function readIdentitySession(client:IdentityClient,locale:Locale):Promise<IdentitySession> {
 try {
  return {status:'authenticated',account:await client.read('getMyAccount',locale)};
 } catch(error) {
  if(error instanceof IdentityError && error.status===401 &&
     (error.code==='unauthenticated'||error.code==='mfa_required'||error.code==='mfa_enrollment_required'))
   return {status:error.code==='unauthenticated'?'anonymous':error.code};
  throw error;
 }
}

// This is an in-memory form context, not a credential to persist. Acquire it
// before accepting a secret, then submit with that SAME token. Reacquiring a
// token silently on submit could send a password/code to a different session.
export type PreparedIdentityForm = { csrf:CsrfToken; session:IdentitySession };
export async function prepareIdentityForm(client:IdentityClient,locale:Locale):Promise<PreparedIdentityForm> {
 const csrf=await client.read('getCsrfToken',locale);
 const session=await readIdentitySession(client,locale);
 return {csrf,session};
}

type Reconciliation =
 | {kind:'prepared'; form:PreparedIdentityForm}
 | {kind:'session'; session:IdentitySession}
 | {kind:'unavailable'; error:IdentityError};
export type IdentitySubmission<K extends IdentityMutation> =
 | {kind:'success'; value:ResponseOf<K>}
 | {kind:'refused'; error:IdentityError; reconciliation?:Reconciliation}
 | {kind:'uncertain'; error:IdentityError; reconciliation:Reconciliation};

function asIdentityError(error:unknown):IdentityError {
 return error instanceof IdentityError?error:new IdentityError('network_error',0);
}
async function reconcile(client:IdentityClient,locale:Locale,renewToken:boolean):Promise<Reconciliation> {
 try {
  return renewToken
   ? {kind:'prepared',form:await prepareIdentityForm(client,locale)}
   : {kind:'session',session:await readIdentitySession(client,locale)};
 } catch(error) { return {kind:'unavailable',error:asIdentityError(error)}; }
}

// Exactly one POST. No transport or reconciliation branch can send a second one.
// The UI must clear secrets and invalidate stale private views before acting on
// reconciliation; even equal pending states do not establish the same identity.
// On successful mutations discard this prepared context; prepare the next form
// explicitly. On ordinary wrong-secret errors the existing context may be kept.
// Uncertain success requires operation-specific recovery guidance (section 4.6).
export async function submitIdentityOnce<K extends IdentityMutation>(
 client:IdentityClient, operation:K, body:RequestOf<K>, locale:Locale, form:PreparedIdentityForm,
):Promise<IdentitySubmission<K>> {
 try {
  return {kind:'success',value:await client.post(operation,body,locale,form.csrf.token)};
 } catch(cause) {
  const error=asIdentityError(cause);
  if(error.status===403 && error.code==='csrf_invalid')
   return {kind:'refused',error,reconciliation:await reconcile(client,locale,true)};
  if(error.status===0 || error.status>=500 || error.code==='invalid_response')
   return {kind:'uncertain',error,reconciliation:await reconcile(client,locale,false)};
  if((error.status===401 && (error.code==='unauthenticated'||error.code==='mfa_required'||error.code==='mfa_enrollment_required')) ||
     (error.status===409 && error.code==='already_signed_in'))
   return {kind:'refused',error,reconciliation:await reconcile(client,locale,false)};
  return {kind:'refused',error};
 }
}
