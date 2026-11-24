import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { Portrait } from '../components/Portrait';
import { ErrorState, LoadingState } from '../components/States';
import { useAsync } from '../hooks/useAsync';
import { fill, useI18n } from '../i18n/I18nContext';
import type { LedgerEntry } from '../types';
import { formatDate, formatMoney } from '../utils/format';

function ledgerLabel(kind: LedgerEntry['kind'], t: ReturnType<typeof useI18n>['t']) {
  return ({ donation: t.casePage.ledgerDonation, disbursement: t.casePage.ledgerDisbursement, transfer_in: t.casePage.ledgerTransferIn, transfer_out: t.casePage.ledgerTransferOut })[kind];
}

export function CasePage() {
  const { code = '' } = useParams();
  const { t, locale } = useI18n();
  const [sampleNote, setSampleNote] = useState(false);
  const [showStickyAction, setShowStickyAction] = useState(false);
  const result = useAsync(() => api.getCase(code, locale), [code, locale]);
  useEffect(() => { document.title = t.meta.caseTitle; }, [t]);
  useEffect(() => {
    const updateStickyAction = () => setShowStickyAction(window.scrollY > 520);
    updateStickyAction();
    window.addEventListener('scroll', updateStickyAction, { passive: true });
    return () => window.removeEventListener('scroll', updateStickyAction);
  }, []);
  if (result.loading) return <LoadingState />;
  if (result.error || !result.data) return <ErrorState onRetry={result.retry} />;

  const { case: item, updates, donors, ledger } = result.data;
  const receivedPercent = item.goal ? Math.min(100, Math.round(item.received / item.goal * 100)) : 0;
  const deliveredPercent = item.goal ? Math.min(100, Math.round(item.delivered / item.goal * 100)) : 0;
  const storyParagraphs = item.story.split('\n\n');
  const showSampleNote = () => { setSampleNote(true); window.setTimeout(() => setSampleNote(false), 3600); };

  return (
    <article className="case-page">
      <header className="case-hero section-wrap">
        <Link className="back-link" to="/"><span aria-hidden="true">←</span>{t.casePage.back}</Link>
        <div className="case-hero-grid">
          <div className="case-portrait-wrap"><Portrait name={item.publicName} /><span className="case-code">{item.code}</span></div>
          <div className="case-identity">
            <p className="eyebrow"><span />{t.casePage.supportLabel}</p>
            <h1>{item.publicName}</h1>
            <p className="case-title">{item.title}</p>
            {item.deadline && <p className="deadline-pill">{fill(t.casePage.deadline, { date: formatDate(item.deadline, locale, true) })}</p>}
            <button className="action action-orange case-primary-action" type="button" onClick={showSampleNote}>{fill(t.casePage.give, { name: item.publicName.replace(/\sD\.$/, '') })}<span aria-hidden="true">↗</span></button>
            {sampleNote && !showStickyAction && <p className="sample-note" role="status">{t.casePage.sampleNotice}</p>}
          </div>
        </div>
      </header>

      <section className="story-progress section-wrap">
        <div className="story-copy">
          <p className="section-number">02 /</p><h2>{t.casePage.story}</h2>
          {storyParagraphs.map((paragraph) => <p key={paragraph.slice(0, 24)}>{paragraph}</p>)}
        </div>
        <div className="progress-panel">
          <h2>{t.casePage.progress}</h2>
          <div className="goal-label"><span>{t.casePage.goal}</span><strong>{formatMoney(item.goal ?? 0, locale)}</strong></div>
          <div className="progress-route" aria-hidden="true">
            <div className="progress-base" />
            <div className="progress-received" style={{ height: `${receivedPercent}%` }} />
            <div className="progress-delivered" style={{ height: `${deliveredPercent}%` }} />
            <i className="route-start" /><i className="route-end" />
          </div>
          <div className="progress-values">
            <div className="value-received"><span>{t.casePage.received}</span><strong>{formatMoney(item.received, locale)}</strong><small>{receivedPercent}%</small></div>
            <div className="value-delivered"><span>{t.casePage.delivered}</span><strong>{formatMoney(item.delivered, locale)}</strong><small>{deliveredPercent}%</small></div>
          </div>
          <p className="progress-note">{t.casePage.progressNote}</p>
        </div>
      </section>

      <section className="updates-section section-wrap">
        <div className="content-heading"><p className="section-number">03 /</p><h2>{t.casePage.updates}</h2></div>
        {updates.length ? <div className="timeline">{updates.map((update) => <article key={update.id}><time dateTime={update.date}>{formatDate(update.date, locale, true)}</time><span className="timeline-node" aria-hidden="true" /><div><p>{update.text}</p><small>{fill(t.casePage.updateBy, { author: update.author })}</small></div></article>)}</div> : <p className="empty-note">{t.casePage.noUpdates}</p>}
      </section>

      <section className="trust-grid section-wrap">
        <div className="donors-section">
          <div className="content-heading"><p className="section-number">04 /</p><h2>{t.casePage.donors}</h2></div>
          {donors.length ? <ul className="donor-list">{donors.map((donor, index) => <li key={`${donor.date}-${index}`}><span className="donor-dot" aria-hidden="true" /><div><strong>{donor.name ?? t.casePage.anonymous}</strong><time dateTime={donor.date}>{formatDate(donor.date, locale)}</time></div><b>{formatMoney(donor.amount, locale)}</b></li>)}</ul> : <p className="empty-note">{t.casePage.noDonors}</p>}
          <p className="list-note">{t.casePage.donorsNote}</p>
        </div>
        <div className="ledger-section">
          <div className="content-heading"><p className="section-number">05 /</p><div><h2>{t.casePage.ledger}</h2><p>{t.casePage.ledgerIntro}</p></div></div>
          {ledger.length ? <ul className="ledger-list">{ledger.map((entry, index) => <li key={`${entry.date}-${index}`} className={`ledger-${entry.kind}`}><div><span>{ledgerLabel(entry.kind, t)}</span><strong>{entry.label}</strong><time dateTime={entry.date}>{formatDate(entry.date, locale)}</time></div><b>{entry.kind === 'disbursement' || entry.kind === 'transfer_out' ? '−' : '+'}{formatMoney(entry.amount, locale)}</b></li>)}</ul> : <p className="empty-note">{t.casePage.noLedger}</p>}
        </div>
      </section>

      <section className="rules-band section-wrap">
        <p className="eyebrow eyebrow-light"><span />{t.casePage.beforeGiving}</p>
        <div className="rules-grid"><p>{t.casePage.excessRule}</p><p>{t.casePage.deadlineRule}</p><p>{t.casePage.decisionRule}</p><p>{t.casePage.pendingRule}</p></div>
      </section>

      {showStickyAction && <div className="give-dock">
          <button className="action action-orange give-action" type="button" onClick={showSampleNote}>{fill(t.casePage.give, { name: item.publicName.replace(/\sD\.$/, '') })}<span aria-hidden="true">↗</span></button>
          {sampleNote && <p role="status">{t.casePage.sampleNotice}</p>}
        </div>}
    </article>
  );
}
