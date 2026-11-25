import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, Outlet, useNavigate } from 'react-router-dom';
import { APP_CONFIG } from '../config';
import { useI18n } from '../i18n/I18nContext';
import { IdentityError, readIdentitySession, submitIdentityOnce, type IdentityMutation, type IdentitySubmission } from '../api/identity-client';
import type { IdentityOperations, Account, SessionState, MfaEnrollment, MfaRecoveryCodes } from '../api/generated/identity-api';
import { SessionProvider, SessionBoundary, useIdentitySession } from './SessionProvider';
import type { FormLease, SessionSnapshot } from './session-store';
import { identityCopy } from './copy';
import { makeAuthenticatorQr } from './local-qr';
import './identity.css';

function useCopy(){return identityCopy(useI18n().locale);}
const destinations:Record<SessionState,string>={anonymous:'/sign-in',authenticated:'/account',mfa_required:'/auth/mfa',mfa_enrollment_required:'/auth/enroll'};
function Panel({title,children}:{title:string;children:ReactNode}){
 const t=useCopy();
 return <section className="identity-wrap"><div className="identity-panel"><p className="identity-eyebrow">{t.eyebrow}</p><h1>{title}</h1>{children}<Link className="identity-home" to="/">{t.home}</Link></div></section>;
}
export function IdentityRoutes(){
 const t=useCopy();
 if(APP_CONFIG.apiMode==='mock')return <Panel title={t.login}><p>{t.preview}</p></Panel>;
 return <SessionProvider><Outlet/></SessionProvider>;
}
function GateFallback({snapshot}:{snapshot:SessionSnapshot}){
 const t=useCopy();
 const {refresh}=useIdentitySession();
 if(snapshot.reading.status==='checking')return <Panel title={t.checking}><p role="status">{t.checking}</p></Panel>;
 if(snapshot.reading.status==='unavailable')return <Panel title={t.login}><p role="alert">{t.tryAgain}</p><button className="identity-primary" type="button" onClick={()=>void refresh()}>{t.retry}</button></Panel>;
 return <Navigate replace to={destinations[snapshot.reading.status]}/>;
}
function Guard({allowed,children}:{allowed:readonly SessionState[];children:(account:Account|null)=>ReactNode}){
 return <SessionBoundary allowed={allowed} fallback={snapshot=><GateFallback snapshot={snapshot}/>}>
  {session=>children(session.status==='authenticated'?session.account:null)}
 </SessionBoundary>;
}
function Notice(){
 const t=useCopy();const {notice}=useIdentitySession();
 if(!notice)return null;
 const message=({accepted:t.accepted,stale:t.stale,lost:t.lost,uncertain:t.uncertain,signedOut:t.signedOut,already:t.already,passwordChanged:t.passwordChanged,passwordSet:t.passwordSet,codesLost:t.codesLost,passwordUncertain:t.passwordUncertain,credentialUncertain:t.credentialUncertain,registerUncertain:t.registerUncertain} as Record<string,string>)[notice];
 return message?<p className="identity-notice" role="status">{message}</p>:null;
}
function useOptions(){
 const {client,snapshot}=useIdentitySession();const {locale}=useI18n();
 const [value,setValue]=useState<'loading'|'open'|'closed'|'error'>('loading');
 const [attempt,setAttempt]=useState(0);
 useEffect(()=>{
  let alive=true;
  setValue('loading');
  client.read('getAuthOptions',locale).then(x=>{if(alive)setValue(x.registrationOpen===true?'open':'closed');},()=>{if(alive)setValue('error');});
  return ()=>{alive=false;};
 },[client,locale,snapshot.generation,attempt]);
 return {value,retry:()=>setAttempt(n=>n+1)};
}
function FormError({error}:{error:IdentityError|null}){
 const t=useCopy();
 return error?<p className="identity-error" role="alert" tabIndex={-1}>{t.errors[error.code]}</p>:null;
}
function Field({name,label,type='text',autoComplete,hint,inputMode,error}:{name:string;label:string;type?:string;autoComplete?:string;hint?:string;inputMode?:'numeric'|'email';error:IdentityError|null}){
 const t=useCopy();const field=error?.fields.find(x=>x.name===name);
 const secretError=((name==='password'||name==='currentPassword')&&error?.code==='invalid_credentials')||(name==='credential'&&error?.code==='credential_invalid')||(name==='code'&&error?.code==='mfa_code_invalid');
 const message=field?(t.fields[field.code as keyof typeof t.fields]??t.fields.invalid_value):secretError&&error?t.errors[error.code]:null;
 const desc=[hint?name+'-hint':null,message?name+'-error':null].filter(Boolean).join(' ')||undefined;
 return <div className="identity-field"><label htmlFor={name}>{label}</label><input id={name} name={name} type={type} autoComplete={autoComplete} inputMode={inputMode} required aria-invalid={message?true:undefined} aria-describedby={desc} autoCapitalize="none" spellCheck={false}/>{hint&&<small id={name+'-hint'}>{hint}</small>}{message&&<p className="identity-field-error" id={name+'-error'}>{message}</p>}</div>;
}
// Each screen owns its token lease. Revalidation remounts the guarded screen;
// form values are never copied into router state or shared account state.
function useFormLease(){
 const {store,snapshot}=useIdentitySession();const {locale}=useI18n();
 const [lease,setLease]=useState<FormLease|null>(null);
 const [error,setError]=useState<IdentityError|null>(null);
 const [attempt,setAttempt]=useState(0);
 useEffect(()=>{
  let alive=true;setLease(null);setError(null);
  store.prepare(locale).then(x=>{if(alive)setLease(x);},e=>{if(alive)setError(e instanceof IdentityError?e:new IdentityError('network_error',0));});
  return ()=>{alive=false;};
 },[store,snapshot.generation,locale,attempt]);
 return {lease:lease&&store.isCurrent(lease.generation)?lease:null,error,retry:()=>setAttempt(n=>n+1)};
}
function Prepared({children}:{children:(lease:FormLease)=>ReactNode}){
 const t=useCopy();const {lease,error,retry}=useFormLease();
 return lease?<>{children(lease)}</>:error?<><FormError error={error}/><button type="button" className="identity-primary" onClick={retry}>{t.retry}</button></>:<p role="status">{t.preparing}</p>;
}
type PostRequest<K extends IdentityMutation>=IdentityOperations[K]['request'];
function useSubmission(lease:FormLease){
 const {client,store,refresh,announce,setNotice}=useIdentitySession();const {locale}=useI18n();
 const [busy,setBusy]=useState(false);const [error,setError]=useState<IdentityError|null>(null);
 const inFlight=useRef(false);const alive=useRef(true);
 useEffect(()=>{alive.current=true;return ()=>{alive.current=false;};},[]);
 async function submit<K extends IdentityMutation>(operation:K,body:PostRequest<K>,onSuccess:(value:IdentityOperations[K]['response'])=>void|Promise<void>){
  if(inFlight.current||!store.beginSubmission(lease.generation))return;
  inFlight.current=true;setBusy(true);setError(null);
  try{
   const result:IdentitySubmission<K>=await submitIdentityOnce(client,operation,body,locale,lease);
   if(!alive.current||!store.isCurrent(lease.generation)){
    // A late mutation can still have changed server state. Revalidate, but
    // never display its private result or replay its request.
    if(result.kind==='success'||result.kind==='uncertain')void refresh(true);
    return;
   }
   if(result.kind==='success'){await onSuccess(result.value);return;}
   if(result.reconciliation){
    const r=result.reconciliation;
    setNotice(result.kind==='uncertain'?(operation==='confirmMfaEnrollment'||operation==='regenerateRecoveryCodes'?'codesLost':operation==='changePassword'?'passwordUncertain':operation==='setPasswordWithCredential'?'credentialUncertain':operation==='register'?'registerUncertain':'uncertain'):result.error.code==='csrf_invalid'?'stale':result.error.code==='unauthenticated'?'lost':result.error.code==='already_signed_in'?'already':null);
    if(r.kind==='prepared')store.reconcile(lease.generation,r.form.session,r.form);
    else if(r.kind==='session')store.reconcile(lease.generation,r.session);
    else store.reconcile(lease.generation,{status:'unavailable',error:r.error});
    announce();
   }else setError(result.error);
  }finally{store.endSubmission();inFlight.current=false;if(alive.current)setBusy(false);}
 }
 return {busy,error,submit};
}
function textValue(data:FormData,name:string){return String(data.get(name)??'');}
function clearSecret(form:HTMLFormElement,name:string){
 const field=form.elements.namedItem(name);
 if(field instanceof HTMLInputElement)field.value='';
}
function focusError(form:HTMLFormElement,error:IdentityError|null){
 if(!error)return;
 const name=error.fields[0]?.name??(error.code==='invalid_credentials'?(form.elements.namedItem('currentPassword')?'currentPassword':'password'):error.code==='mfa_code_invalid'?'code':error.code==='credential_invalid'?'credential':undefined);
 const field=name?form.elements.namedItem(name):null;
 if(field instanceof HTMLElement)field.focus();
 else form.querySelector<HTMLElement>('[role="alert"]')?.focus();
}
function useErrorFocus(error:IdentityError|null){
 const ref=useRef<HTMLFormElement>(null);
 useEffect(()=>{if(ref.current)focusError(ref.current,error);},[error]);
 return ref;
}
function LoginForm({lease}:{lease:FormLease}){
 const t=useCopy();const {refresh,setNotice}=useIdentitySession();
 const {busy,error,submit}=useSubmission(lease);const ref=useErrorFocus(error);
 function onSubmit(e:FormEvent<HTMLFormElement>){
  e.preventDefault();const form=e.currentTarget;const data=new FormData(form);
  const body={email:textValue(data,'email').trim(),password:textValue(data,'password')};
  clearSecret(form,'password');
  void submit('login',body,async()=>{setNotice(null);await refresh(true);});
 }
 return <form ref={ref} onSubmit={onSubmit}><fieldset disabled={busy}><Field name="email" label={t.email} type="email" autoComplete="username" error={error}/><Field name="password" label={t.password} type="password" autoComplete="current-password" error={error}/><FormError error={error}/><button className="identity-primary" type="submit">{busy?t.busy:t.submitLogin}</button></fieldset></form>;
}
function LoginScreen(){
 const t=useCopy();const options=useOptions();
 return <Panel title={t.login}><p>{t.loginIntro}</p><Notice/><Prepared>{lease=><LoginForm lease={lease}/>}</Prepared><Link to="/auth/set-password">{t.setPassword}</Link>{options.value==='open'&&<Link to="/sign-up">{t.create}</Link>}{options.value==='error'&&<p>{t.registrationUnknown} <button type="button" className="identity-link" onClick={options.retry}>{t.retry}</button></p>}</Panel>;
}
export function SignInPage(){return <Guard allowed={['anonymous']}>{()=> <LoginScreen/>}</Guard>;}
function RegisterForm({lease}:{lease:FormLease}){
 const t=useCopy();const {locale}=useI18n();const {refresh,setNotice}=useIdentitySession();const navigate=useNavigate();
 const {busy,error,submit}=useSubmission(lease);const ref=useErrorFocus(error);
 function onSubmit(e:FormEvent<HTMLFormElement>){
  e.preventDefault();const form=e.currentTarget;const data=new FormData(form);
  const body={email:textValue(data,'email').trim(),displayName:textValue(data,'displayName'),password:textValue(data,'password'),locale};
  clearSecret(form,'password');
  void submit('register',body,async()=>{setNotice('accepted');await refresh(true);navigate('/sign-in',{replace:true});});
 }
 if(error?.code==='registration_closed')return <p role="status">{t.closed}</p>;
 return <form ref={ref} onSubmit={onSubmit}><fieldset disabled={busy}><Field name="displayName" label={t.name} autoComplete="name" error={error}/><Field name="email" label={t.email} type="email" autoComplete="username" error={error}/><Field name="password" label={t.password} type="password" autoComplete="new-password" hint={t.passwordHint} error={error}/><FormError error={error}/><button className="identity-primary" type="submit">{busy?t.busy:t.submitRegister}</button></fieldset></form>;
}
function RegisterScreen(){
 const t=useCopy();const options=useOptions();
 return <Panel title={t.register}><Notice/>{options.value==='loading'?<p role="status">{t.checking}</p>:options.value==='error'?<><p role="alert">{t.registrationUnknown}</p><button type="button" className="identity-primary" onClick={options.retry}>{t.retry}</button></>:options.value==='closed'?<p>{t.closed}</p>:<><p>{t.registerIntro}</p><Prepared>{lease=><RegisterForm lease={lease}/>}</Prepared></>}<Link to="/sign-in">{t.existing}</Link></Panel>;
}
export function SignUpPage(){return <Guard allowed={['anonymous']}>{()=> <RegisterScreen/>}</Guard>;}
function ChallengeForm({lease}:{lease:FormLease}){
 const t=useCopy();const {refresh,setNotice}=useIdentitySession();const [recovery,setRecovery]=useState(false);
 const {busy,error,submit}=useSubmission(lease);const ref=useErrorFocus(error);
 function onSubmit(e:FormEvent<HTMLFormElement>){
  e.preventDefault();const form=e.currentTarget;const code=textValue(new FormData(form),'code');
  clearSecret(form,'code');
  void submit('verifyMfa',recovery?{method:'recovery_code',code}:{method:'totp',code},async()=>{setNotice(null);await refresh(true);});
 }
 return <form ref={ref} onSubmit={onSubmit}><fieldset disabled={busy}><Field key={String(recovery)} name="code" label={recovery?t.recovery:t.totp} autoComplete="one-time-code" inputMode={recovery?undefined:'numeric'} error={error}/><FormError error={error}/><button className="identity-primary" type="submit">{busy?t.busy:t.verify}</button><button type="button" className="identity-link" onClick={()=>setRecovery(v=>!v)}>{recovery?t.useTotp:t.useRecovery}</button></fieldset></form>;
}
function LogoutForm({lease}:{lease:FormLease}){
 const t=useCopy();const {refresh,setNotice}=useIdentitySession();
 const {busy,error,submit}=useSubmission(lease);
 return <><FormError error={error}/><button className="identity-primary" type="button" disabled={busy} onClick={()=>void submit('logout',null,async()=>{setNotice('signedOut');await refresh(true);})}>{busy?t.busy:t.logout}</button></>;
}
export function ChallengePage(){
 const t=useCopy();
 return <Guard allowed={['mfa_required']}>{()=> <Panel title={t.mfa}><p>{t.mfaIntro}</p><Notice/><Prepared>{lease=><><ChallengeForm lease={lease}/><LogoutForm lease={lease}/></>}</Prepared></Panel>}</Guard>;
}
// A receipt is local to its route and bound to the verified post-mutation
// identity and generation. Revalidation or navigation hides it immediately.
type CodeReceipt={generation:number;accountId:string;codes:MfaRecoveryCodes};
function useCodeReceipt(){
 const {snapshot,store,client,announce,setNotice}=useIdentitySession();const {locale}=useI18n();
 const [receipt,setReceipt]=useState<CodeReceipt|null>(null);
 const alive=useRef(true);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
 useEffect(()=>{
  if(receipt && (snapshot.generation>receipt.generation ||
   (snapshot.generation===receipt.generation &&
   (snapshot.reading.status!=='authenticated'||snapshot.reading.account.id!==receipt.accountId))))setReceipt(null);
 },[snapshot,receipt]);
 async function accept(lease:FormLease,codes:MfaRecoveryCodes){
  // Confirmation rotates session and token. Never display codes until /me
  // confirms a full staff session, and do not retain the old form context.
  try{
   const session=await readIdentitySession(client,locale);
   if(!alive.current||!store.isCurrent(lease.generation)){announce();return;}
   if(session.status==='authenticated' && session.account.role!=='donor' && session.account.mfa.enrolled &&
     (lease.session.status!=='authenticated'||lease.session.account.id===session.account.id) &&
     Array.isArray(codes.codes)&&codes.codes.length===10&&codes.codes.every(x=>typeof x==='string'&&/^[A-Z2-7]{4}(-[A-Z2-7]{4}){3}$/.test(x))&&new Set(codes.codes).size===10){
    setReceipt({generation:lease.generation+1,accountId:session.account.id,codes});
    setNotice(null);
   }else setNotice('codesLost');
   store.reconcile(lease.generation,session);
  }catch(error){
   if(alive.current&&store.isCurrent(lease.generation)){
    setNotice('codesLost');store.reconcile(lease.generation,{status:'unavailable',error:error instanceof IdentityError?error:new IdentityError('network_error',0)});
   }
  }finally{announce();}
 }
 const visible=receipt && snapshot.generation===receipt.generation && snapshot.reading.status==='authenticated' && snapshot.reading.account.id===receipt.accountId?receipt.codes:null;
 return {visible,accept,clear:()=>setReceipt(null)};
}
function RecoveryCodeDisplay({receipt,onDone}:{receipt:MfaRecoveryCodes;onDone:()=>void}){
 const t=useCopy();
 return <Panel title={t.recoveryCodes}><p role="status">{t.codesOnce}</p><ol className="identity-codes">{receipt.codes.map((code,i)=><li key={i}><code>{code}</code></li>)}</ol><p>{t.codesOffline}</p><button type="button" className="identity-primary" onClick={onDone}>{t.codesSaved}</button></Panel>;
}
function AuthenticatorQr({uri}:{uri:string}){
 const t=useCopy();
 const qr=useMemo(()=>{try{return makeAuthenticatorQr(uri);}catch{return null;}},[uri]);
 return qr?<svg className="identity-qr" role="img" aria-label={t.qrLabel} viewBox={'0 0 '+qr.size+' '+qr.size} shapeRendering="crispEdges"><rect width={qr.size} height={qr.size} fill="#fff"/><path d={qr.path} fill="#000"/></svg>:<p>{t.qrUnavailable}</p>;
}
function EnrollmentForm({lease,onCodes}:{lease:FormLease;onCodes:(lease:FormLease,codes:MfaRecoveryCodes)=>Promise<void>}){
 const t=useCopy();
 const [enrollment,setEnrollment]=useState<MfaEnrollment|null>(null);
 const {busy,error,submit}=useSubmission(lease);const ref=useErrorFocus(error);
 function begin(){
  setEnrollment(null);
  void submit('beginMfaEnrollment',null,value=>{setEnrollment(value);});
 }
 function confirm(e:FormEvent<HTMLFormElement>){
  e.preventDefault();const form=e.currentTarget;const code=textValue(new FormData(form),'code');clearSecret(form,'code');
  void submit('confirmMfaEnrollment',{code},async value=>{setEnrollment(null);await onCodes(lease,value);});
 }
 return <><p>{t.enrollIntro}</p>{enrollment?<><AuthenticatorQr uri={enrollment.otpauthUri}/><p>{t.manualSecret}</p><code className="identity-secret">{enrollment.secret}</code><form ref={ref} onSubmit={confirm}><fieldset disabled={busy}><Field name="code" label={t.totp} autoComplete="one-time-code" inputMode="numeric" error={error}/><FormError error={error}/><button type="submit" className="identity-primary">{busy?t.busy:t.confirmEnroll}</button><button type="button" className="identity-link" onClick={begin}>{t.restartEnroll}</button></fieldset></form></>:<><FormError error={error}/><button type="button" className="identity-primary" disabled={busy} onClick={begin}>{busy?t.busy:t.startEnroll}</button></>}</>;
}
export function EnrollmentPreparationPage(){
 const t=useCopy();const navigate=useNavigate();const receipt=useCodeReceipt();
 return <Guard allowed={['mfa_enrollment_required','authenticated']}>{account=>account?
  receipt.visible?<RecoveryCodeDisplay receipt={receipt.visible} onDone={()=>{receipt.clear();navigate('/account',{replace:true});}}/>:
  <Panel title={t.account}><Notice/><Link to="/account">{t.account}</Link>{account.mfa.enrolled&&<Link to="/account/recovery-codes">{t.regenerate}</Link>}</Panel>:
  <Panel title={t.enroll}><Notice/><Prepared>{lease=><><EnrollmentForm lease={lease} onCodes={receipt.accept}/><LogoutForm lease={lease}/></>}</Prepared></Panel>}</Guard>;
}
function PasswordForm({lease,setup}:{lease:FormLease;setup:boolean}){
 const t=useCopy();const {refresh,setNotice}=useIdentitySession();const navigate=useNavigate();
 const {busy,error,submit}=useSubmission(lease);const ref=useErrorFocus(error);
 function onSubmit(e:FormEvent<HTMLFormElement>){
  e.preventDefault();const form=e.currentTarget;const data=new FormData(form);
  const newPassword=textValue(data,'newPassword');
  const currentPassword=textValue(data,'currentPassword');const credential=textValue(data,'credential');
  for(const name of ['newPassword','currentPassword','credential'])clearSecret(form,name);
  if(setup)void submit('setPasswordWithCredential',{credential,newPassword},async()=>{setNotice('passwordSet');await refresh(true);navigate('/sign-in',{replace:true});});
  else void submit('changePassword',{currentPassword,newPassword},async()=>{setNotice('passwordChanged');await refresh(true);navigate('/account',{replace:true});});
 }
 return <form ref={ref} onSubmit={onSubmit}><fieldset disabled={busy}>{setup?
  <Field name="credential" label={t.credential} type="password" autoComplete="off" hint={t.credentialHint} error={error}/>:
  <Field name="currentPassword" label={t.currentPassword} type="password" autoComplete="current-password" error={error}/>}
  <Field name="newPassword" label={t.newPassword} type="password" autoComplete="new-password" hint={t.passwordHint} error={error}/><FormError error={error}/><button className="identity-primary" type="submit">{busy?t.busy:setup?t.setPassword:t.changePassword}</button></fieldset></form>;
}
export function SetPasswordPage(){
 const t=useCopy();
 return <Guard allowed={['anonymous']}>{()=> <Panel title={t.setPassword}><Notice/><p>{t.setPasswordIntro}</p><Prepared>{lease=><PasswordForm lease={lease} setup/>}</Prepared><Link to="/sign-in">{t.login}</Link></Panel>}</Guard>;
}
export function ChangePasswordPage(){
 const t=useCopy();
 return <Guard allowed={['authenticated']}>{()=> <Panel title={t.changePassword}><Notice/><Prepared>{lease=><PasswordForm lease={lease} setup={false}/>}</Prepared><Link to="/account">{t.account}</Link></Panel>}</Guard>;
}
function RegenerateForm({lease,onCodes}:{lease:FormLease;onCodes:(lease:FormLease,codes:MfaRecoveryCodes)=>Promise<void>}){
 const t=useCopy();const {busy,error,submit}=useSubmission(lease);const ref=useErrorFocus(error);
 function onSubmit(e:FormEvent<HTMLFormElement>){
  e.preventDefault();const form=e.currentTarget;const data=new FormData(form);
  const currentPassword=textValue(data,'currentPassword'),code=textValue(data,'code');
  clearSecret(form,'currentPassword');clearSecret(form,'code');
  void submit('regenerateRecoveryCodes',{currentPassword,code},value=>onCodes(lease,value));
 }
 return <form ref={ref} onSubmit={onSubmit}><fieldset disabled={busy}><Field name="currentPassword" label={t.currentPassword} type="password" autoComplete="current-password" error={error}/><Field name="code" label={t.totp} autoComplete="one-time-code" inputMode="numeric" error={error}/><FormError error={error}/><button className="identity-primary" type="submit">{busy?t.busy:t.regenerate}</button></fieldset></form>;
}
export function RecoveryCodesPage(){
 const t=useCopy();const navigate=useNavigate();const receipt=useCodeReceipt();
 return <Guard allowed={['authenticated']}>{account=>account&&(
  account.role==='donor'||!account.mfa.enrolled?<Panel title={t.account}><p role="alert">{t.errors.forbidden}</p><Link to="/account">{t.account}</Link></Panel>:
  receipt.visible?<RecoveryCodeDisplay receipt={receipt.visible} onDone={()=>{receipt.clear();navigate('/account',{replace:true});}}/>:
  <Panel title={t.regenerate}><Notice/><p>{t.regenerateIntro}</p><Prepared>{lease=><RegenerateForm lease={lease} onCodes={receipt.accept}/>}</Prepared><Link to="/account">{t.account}</Link></Panel>)}</Guard>;
}
export function AccountPage(){
 const t=useCopy();
 return <Guard allowed={['authenticated']}>{account=>account&&<Panel title={t.account}><Notice/><p>{t.accountIntro}</p><dl className="identity-account"><dt>{t.name}</dt><dd>{account.displayName}</dd><dt>{t.email}</dt><dd>{account.email}</dd><dt>{t.role}</dt><dd>{t[account.role]}</dd><dt>{t.verification}</dt><dd>{account.emailVerified?t.verified:t.unverified}</dd>{account.mfa.required&&<><dt>{t.recoveryRemaining}</dt><dd>{account.mfa.recoveryCodesRemaining??'—'}</dd></>}</dl><Link to="/account/password">{t.changePassword}</Link>{account.role!=='donor'&&account.mfa.enrolled&&<Link to="/account/recovery-codes">{t.regenerate}</Link>}<Prepared>{lease=><LogoutForm lease={lease}/>}</Prepared></Panel>}</Guard>;
}
