import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { createIdentityClient, type IdentityClient, type IdentitySession } from '../api/identity-client';
import { useI18n } from '../i18n/I18nContext';
import type { SessionState } from '../api/generated/identity-api';
import { createSessionStore, type SessionSnapshot, type SessionStore } from './session-store';
import './session.css';

const channelName='apv-session-events-v1';
const changeSignal='session-changed';
type SessionContextValue={
 store:SessionStore;
 client:IdentityClient;
 announce:()=>void;
 notice:string|null;
 setNotice:(notice:string|null)=>void;
 snapshot:SessionSnapshot;
 // Call at a confirmed or uncertain session-changing outcome. No data is sent
 // to other tabs; each tab independently reads the server's /me.
 refresh:(announce?:boolean)=>Promise<void>;
};
const SessionContext=createContext<SessionContextValue|null>(null);

export function SessionProvider({children,client}:{children:ReactNode;client?:IdentityClient}) {
 const {locale}=useI18n();
 const localeRef=useRef(locale);
 localeRef.current=locale;
 const [transport]=useState(()=>client??createIdentityClient());
 const [store]=useState(()=>createSessionStore(transport));
 const [notice,setNotice]=useState<string|null>(null);
 const snapshot=useSyncExternalStore(store.subscribe,store.getSnapshot,store.getSnapshot);
 const channel=useRef<BroadcastChannel|null>(null);
 const suspend=useCallback(()=>{
  // Hide the existing DOM synchronously, including before bfcache snapshots.
  // React then removes the guarded subtree and its page-local secrets.
  document.documentElement.dataset.identitySuspended='true';
  store.suspend();
 },[store]);
 const announce=useCallback(()=>{
  try {
   if(channel.current)channel.current.postMessage(changeSignal);
   else if(typeof BroadcastChannel!=='undefined'){
    const temporary=new BroadcastChannel(channelName);
    temporary.postMessage(changeSignal);temporary.close();
   }
  }catch{/* Visibility revalidation remains the fallback. */}
 },[]);
 const refresh=useCallback(async(notify=false)=>{
  document.documentElement.dataset.identitySuspended='true';
  if(notify)announce();
  if(document.visibilityState==='hidden'){store.suspend();return;}
  await store.refresh(localeRef.current);
 },[store,announce]);

 useEffect(()=>{
  try {
   channel.current=typeof BroadcastChannel==='undefined'?null:new BroadcastChannel(channelName);
  } catch {channel.current=null;}
  const onMessage=(event:MessageEvent)=>{
   if(event.data===changeSignal)void refresh();
  };
  const onVisibility=()=>{
   if(document.visibilityState==='hidden')suspend();
   else void refresh();
  };
  const onPageHide=()=>suspend();
  const onPageShow=(event:PageTransitionEvent)=>{if(event.persisted)void refresh();};
  channel.current?.addEventListener('message',onMessage);
  document.addEventListener('visibilitychange',onVisibility);
  window.addEventListener('pagehide',onPageHide);
  window.addEventListener('pageshow',onPageShow);
  void refresh();
  return ()=>{
   channel.current?.removeEventListener('message',onMessage);
   channel.current?.close();channel.current=null;
   document.removeEventListener('visibilitychange',onVisibility);
   window.removeEventListener('pagehide',onPageHide);
   window.removeEventListener('pageshow',onPageShow);
   suspend();
  };
 },[refresh,suspend]);
 useLayoutEffect(()=>{
  if(document.visibilityState!=='hidden' && snapshot.reading.status!=='checking')
   delete document.documentElement.dataset.identitySuspended;
 },[snapshot]);
 const value=useMemo(()=>({store,client:transport,snapshot,refresh,announce,notice,setNotice}),[store,transport,snapshot,refresh,announce,notice]);
 return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useIdentitySession(){
 const context=useContext(SessionContext);
 if(!context)throw new Error('SessionProvider is required');
 return context;
}

// Identity routes supply their own localized loading/error/redirect fallback.
// The backend remains the authorization authority; this only gates the UI.
// Every generation remounts private content so stale form values are discarded.
export function SessionBoundary({allowed,children,fallback}:{
 allowed:readonly SessionState[];
 children:(session:IdentitySession)=>ReactNode;
 fallback:(snapshot:SessionSnapshot)=>ReactNode;
}){
 const {snapshot}=useIdentitySession();
 const reading=snapshot.reading;
 if(reading.status==='checking'||reading.status==='unavailable'||!allowed.includes(reading.status))
  return <>{fallback(snapshot)}</>;
 return <div className="identity-session-content" key={snapshot.generation}>{children(reading)}</div>;
}
