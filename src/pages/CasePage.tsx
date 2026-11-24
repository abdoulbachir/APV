import { useEffect, useState } from 'react';
import { Link, useParams, useLocation } from 'react-router-dom';
import { APP_CONFIG } from '../config';
import { api } from '../api/client';
import { SITE_NAME } from '../config';
import { getCaseArtwork } from '../assets/case-artwork';
import { Portrait } from '../components/Portrait';
import { ErrorState, LoadingState } from '../components/States';
import { useAsync } from '../hooks/useAsync';
import { usePageMotion } from '../hooks/usePageMotion';
import { fill, useI18n } from '../i18n/I18nContext';
import type { LedgerEntry } from '../types';
import { formatDate, formatMoney } from '../utils/format';

function ledgerLabel(kind: LedgerEntry['kind'], t: ReturnType<typeof useI18n>['t']) {
  return ({ donation: t.casePage.ledgerDonation, disbursement: t.casePage.ledgerDisbursement, transfer_in: t.casePage.ledgerTransferIn, transfer_out: t.casePage.ledgerTransferOut })[kind];
}

export function CasePage() {
  const { code = '' } = useParams();
  const location = useLocation();
  const source = location.state?.directoryFrom;
  const returnTo = typeof source === 'string' && (source === '/cases' || source.startsWith('/cases?')) ? source : null;
  useEffect(() => { window.scrollTo({ top: 0, behavior: 'instant' }); }, [code]);
  const { t, locale } = useI18n();
  const [sampleNote, setSampleNote] = useState(false);
  const [showStickyAction, setShowStickyAction] = useState(false);
  const result = useAsync(() => api.getCase(code, locale), [code, locale]);
  const motionRef = usePageMotion<HTMLElement>(!!result.data && !result.loading && !result.error, result.data);
  useEffect(() => {
    const title = result.loading || (result.data && result.data.case.code.toLowerCase() !== code.toLowerCase()) ? t.redesign.caseLoadingTitle : result.error || !result.data ? t.redesign.caseUnavailableTitle : result.data.case.title;
    document.title = title + ' — ' + SITE_NAME;
  }, [code, result.data, result.loading, result.error, t]);
  useEffect(() => {
    const updateStickyAction = () => setShowStickyAction(window.scrollY > 520);
    updateStickyAction();
    window.addEventListener('scroll', updateStickyAction, { passive: true });
    return () => window.removeEventListener('scroll', updateStickyAction);
  }, []);
  if (result.loading) return <LoadingState />;
  if (result.error || !result.data) return <ErrorState onRetry={result.retry} />;

  const { case: item, updates, donors, ledger } = result.data;
  const receivedPercent = item.goal && item.goal > 0 ? Math.max(0, item.received / item.goal * 100) : null;
  const deliveredPercent = item.goal && item.goal > 0 ? Math.max(0, item.delivered / item.goal * 100) : null;
  const formatPercent = (value: number) => new Intl.NumberFormat(locale === 'fr' ? 'fr-FR' : 'en-US', { style: 'percent', maximumFractionDigits: 1 }).format(value / 100);
  const storyParagraphs = item.story.split('\n\n');
  const showSampleNote = () => { setSampleNote(true); window.setTimeout(() => setSampleNote(false), 3600); };

  return (
    <article className="case-page" ref={motionRef}>
      <header className="case-hero section-wrap">
        <Link className="back-link" to={returnTo ?? "/"}><span aria-hidden="true">←</span>{returnTo ? t.directory.back : t.casePage.back}</Link>
        {APP_CONFIG.apiMode === 'mock' && <p className="preview-label">{t.redesign.preview}</p>}
        <div className="case-hero-grid">
          <div className="case-portrait-wrap"><Portrait name={item.publicName} photoUrl={item.photoUrl} artwork={getCaseArtwork(item.code)} /><span className="case-code">{item.code}</span></div>
          <div className="case-identity">
            <p className="eyebrow"><span />{t.casePage.supportLabel}</p>
            <h1>{item.publicName}</h1>
            <p className="case-title">{item.title}</p>
            {item.deadline && <p className="deadline-pill">{fill(t.casePage.deadline, { date: formatDate(item.deadline, locale, true) })}</p>}
            <button className="action action-orange case-primary-action" type="button" onClick={showSampleNote}>{fill(t.casePage.give, { name: item.publicName })}<span aria-hidden="true">↗</span></button>
            {sampleNote && !showStickyAction && <p className="sample-note" role="status">{t.casePage.sampleNotice}</p>}
          </div>
        </div>
      </header>

      <section className="story-progress section-wrap">
        <div className="story-copy" data-motion="reveal">
          <p className="section-number">02 /</p><h2>{t.casePage.story}</h2>
          {storyParagraphs.map((paragraph) => <p key={paragraph.slice(0, 24)}>{paragraph}</p>)}
        </div>
        <div className="progress-panel" data-motion="funding">
          <h2>{t.casePage.progress}</h2>
          <div className="goal-label"><span>{t.casePage.goal}</span><strong>{item.goal === null ? '—' : formatMoney(item.goal, locale)}</strong></div>
          <div className="progress-values">
            <div className="value-received">
              <div className="progress-label"><span>{t.casePage.received}</span><strong>{formatMoney(item.received, locale)}</strong></div>
              {receivedPercent !== null && <><div className="funding-track" aria-hidden="true"><span className="funding-received" style={{ width: Math.min(100, receivedPercent) + '%' }} /></div><small>{formatPercent(receivedPercent)} {t.caseCard.ofGoal}</small></>}
            </div>
            <div className="value-delivered">
              <div className="progress-label"><span>{t.casePage.delivered}</span><strong>{formatMoney(item.delivered, locale)}</strong></div>
              {deliveredPercent !== null && <><div className="funding-track" aria-hidden="true"><span className="funding-delivered" style={{ width: Math.min(100, deliveredPercent) + '%' }} /></div><small>{formatPercent(deliveredPercent)} {t.caseCard.ofGoal}</small></>}
            </div>
          </div>
          <p className="progress-note">{t.casePage.progressNote}</p>
        </div>
      </section>

      <section className="updates-section section-wrap">
        <div className="content-heading" data-motion="reveal"><p className="section-number">03 /</p><h2>{t.casePage.updates}</h2></div>
        {updates.length ? <div className="timeline" data-motion="timeline">{updates.map((update) => <article key={update.id}><time dateTime={update.date}>{formatDate(update.date, locale, true)}</time><span className="timeline-node" aria-hidden="true" /><div><p>{update.text}</p><small>{fill(t.casePage.updateBy, { author: update.author })}</small></div></article>)}</div> : <p className="empty-note">{t.casePage.noUpdates}</p>}
      </section>

      <section className="trust-grid section-wrap">
        <div className="donors-section">
          <div className="content-heading" data-motion="reveal"><p className="section-number">04 /</p><h2>{t.casePage.donors}</h2></div>
          {donors.length ? <ul className="donor-list" data-motion="reveal">{donors.map((donor, index) => <li key={`${donor.date}-${index}`}><span className="donor-dot" aria-hidden="true" /><div><strong>{donor.name ?? t.casePage.anonymous}</strong><time dateTime={donor.date}>{formatDate(donor.date, locale)}</time></div><b>{formatMoney(donor.amount, locale)}</b></li>)}</ul> : <p className="empty-note">{t.casePage.noDonors}</p>}
          <p className="list-note">{t.casePage.donorsNote}</p>
        </div>
        <div className="ledger-section">
          <div className="content-heading" data-motion="reveal"><p className="section-number">05 /</p><div><h2>{t.casePage.ledger}</h2><p>{t.casePage.ledgerIntro}</p></div></div>
          {ledger.length ? <ul className="ledger-list" data-motion="reveal">{ledger.map((entry, index) => <li key={`${entry.date}-${index}`} className={`ledger-${entry.kind}`}><div><span>{ledgerLabel(entry.kind, t)}</span><strong>{entry.label}</strong><time dateTime={entry.date}>{formatDate(entry.date, locale)}</time></div><b>{entry.kind === 'disbursement' || entry.kind === 'transfer_out' ? '−' : '+'}{formatMoney(entry.amount, locale)}</b></li>)}</ul> : <p className="empty-note">{t.casePage.noLedger}</p>}
        </div>
      </section>

      <section className="rules-band section-wrap">
        <p className="eyebrow eyebrow-light"><span />{t.casePage.beforeGiving}</p>
        <div className="rules-grid" data-motion="steps"><p>{t.casePage.excessRule}</p><p>{t.casePage.deadlineRule}</p><p>{t.casePage.decisionRule}</p><p>{t.casePage.pendingRule}</p></div>
      </section>

      {showStickyAction && <div className="give-dock">
          <button className="action action-orange give-action" type="button" onClick={showSampleNote}>{fill(t.casePage.give, { name: item.publicName })}<span aria-hidden="true">↗</span></button>
          {sampleNote && <p role="status">{t.casePage.sampleNotice}</p>}
        </div>}
    </article>
  );
}
