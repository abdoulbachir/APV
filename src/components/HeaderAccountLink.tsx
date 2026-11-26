import { useEffect, useRef, useSyncExternalStore } from 'react';
import { Link } from 'react-router-dom';
import { APP_CONFIG } from '../config';
import { createIdentityClient, readIdentitySession } from '../api/identity-client';
import { sessionChannelName, sessionChangeSignal } from '../identity/SessionProvider';
import { readSignedIn, reportSignedIn, sessionProviderActive, subscribeSignedIn } from '../identity/signed-in-signal';
import { useI18n } from '../i18n/I18nContext';

const client = createIdentityClient();

/**
 * The header's way to an account: "Sign in", or "My account" for a signed-in visitor.
 *
 * It shows one fact held in memory, whether GET /me answered 200, and nothing about the account.
 * Nothing is stored in the browser. On the identity screens their session store reads GET /me and
 * reports the fact; this component reads only on pages where those screens are not mounted, so no
 * page asks twice. There it asks at load, when a tab announces a session change, when the tab
 * becomes visible and when the page returns from the back-forward cache, so it does not go on
 * saying "My account" after a sign-out. A failed read counts as not signed in.
 */
export function HeaderAccountLink() {
  const { t, locale } = useI18n();
  const signedIn = useSyncExternalStore(subscribeSignedIn, readSignedIn, readSignedIn);
  const localeRef = useRef(locale);
  localeRef.current = locale;
  useEffect(() => {
    if (APP_CONFIG.apiMode !== 'real') return;
    let alive = true;
    let latest = 0;
    const read = () => {
      if (sessionProviderActive()) return;
      const mine = ++latest;
      const settle = (value: boolean) => { if (alive && mine === latest && !sessionProviderActive()) reportSignedIn(value); };
      readIdentitySession(client, localeRef.current).then(session => settle(session.status === 'authenticated'), () => settle(false));
    };
    let channel: BroadcastChannel | null = null;
    try { channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(sessionChannelName); } catch { channel = null; }
    const onMessage = (event: MessageEvent) => { if (event.data === sessionChangeSignal) read(); };
    const onVisibility = () => { if (document.visibilityState === 'visible') read(); };
    const onPageShow = (event: PageTransitionEvent) => { if (event.persisted) read(); };
    channel?.addEventListener('message', onMessage);
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pageshow', onPageShow);
    read();
    return () => {
      alive = false;
      channel?.removeEventListener('message', onMessage);
      channel?.close();
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('pageshow', onPageShow);
    };
  }, []);
  return signedIn
    ? <Link className="header-account" to="/account">{t.nav.account}</Link>
    : <Link className="header-account" to="/sign-in">{t.nav.signIn}</Link>;
}
