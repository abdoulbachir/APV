import { Link, Outlet } from 'react-router-dom';
import { SITE_NAME } from '../config';
import { useI18n } from '../i18n/I18nContext';
import { LanguageSwitch } from './LanguageSwitch';

export function Layout() {
  const { t } = useI18n();
  return (
    <div className="site-shell">
      <a className="skip-link" href="#main">{t.nav.home}</a>
      <header className="site-header">
        <Link className="brand" to="/" aria-label={`${SITE_NAME} — ${t.nav.home}`}>
          <span className="brand-mark" aria-hidden="true"><i /><i /><i /></span>
          <span>{SITE_NAME}</span>
        </Link>
        <nav className="desktop-nav" aria-label={t.nav.home}>
          <Link to="/#cases">{t.nav.explore}</Link>
          <Link to="/#how">{t.nav.how}</Link>
        </nav>
        <div className="header-actions"><LanguageSwitch /><Link className="header-give" to="/#cases">{t.nav.give}<span aria-hidden="true">↗</span></Link></div>
      </header>
      <main id="main"><Outlet /></main>
      <footer className="site-footer">
        <div className="footer-line" aria-hidden="true"><i /><i /><i /></div>
        <div>
          <strong>{SITE_NAME}</strong>
          <p>{t.footer.note}</p>
          <Link className="footer-record-link" to="/#record">{t.redesign.publicRecord}</Link>
        </div>
        <p className="tax-line">{t.footer.line}</p>
        <LanguageSwitch />
      </footer>
    </div>
  );
}
