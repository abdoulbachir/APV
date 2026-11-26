import { useEffect, useRef } from 'react';
import { Link, Outlet } from 'react-router-dom';
import { HeaderAccountLink } from './HeaderAccountLink';
import { SITE_NAME } from '../config';
import { useI18n } from '../i18n/I18nContext';
import { LanguageSwitch } from './LanguageSwitch';
import { ThemeSwitch } from './ThemeSwitch';

export function Layout() {
  const { t, locale } = useI18n();
  // On a phone the header is two rows: it leaves while the page is scrolled down and returns as soon
  // as it is scrolled up. The style sheet applies this at phone width only, and shows the header
  // whenever something in it has the focus. Near the top of the page it is always shown.
  const header = useRef<HTMLElement>(null);
  useEffect(() => {
    let last = window.scrollY;
    const onScroll = () => {
      const now = window.scrollY; const element = header.current;
      if (!element || Math.abs(now - last) < 8) return;
      if (now > last && now > element.offsetHeight) element.dataset.away = 'true'; else delete element.dataset.away;
      last = now;
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);
  return (
    <div className="site-shell">
      <a className="skip-link" href="#main" onClick={event => {
        const main = document.getElementById('main');
        if (!main) return;
        event.preventDefault();
        main.focus({ preventScroll: true });
        main.scrollIntoView({ block: 'start' });
      }}>{t.navigationA11y.skip}</a>
      <header className="site-header" ref={header}>
        <Link className="brand" to="/" aria-label={`${SITE_NAME} — ${t.nav.home}`}>
          <span className="brand-mark" aria-hidden="true"><i /><i /><i /></span>
          <span>{SITE_NAME}</span>
        </Link>
        <nav className="desktop-nav" aria-label={t.navigationA11y.primary}>
          <Link to="/cases">{t.nav.explore}</Link>
          <Link to="/#how">{t.nav.how}</Link>
        </nav>
        <div className="header-actions"><ThemeSwitch /><LanguageSwitch /><HeaderAccountLink /><Link className="header-give" to="/cases">{t.nav.give}<span aria-hidden="true">↗</span></Link></div>
      </header>
      <main id="main" tabIndex={-1}><Outlet /></main>
      <footer className="site-footer">
        <div className="footer-line" aria-hidden="true"><i /><i /><i /></div>
        <div>
          <strong>{SITE_NAME}</strong>
          <p>{t.footer.note}</p>
          <Link className="footer-record-link" to="/#record">{t.redesign.publicRecord}</Link>
          <p><Link to="/sign-in">{locale==='fr'?'Mon compte / Connexion':'My account / Sign in'}</Link></p>
        </div>
        <p className="tax-line">{t.footer.line}</p>
        <LanguageSwitch />
      </footer>
    </div>
  );
}
