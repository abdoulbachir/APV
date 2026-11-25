import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useLocation, useNavigationType } from 'react-router-dom';
import { APP_CONFIG } from '../config';
import { api } from '../api/client';
import { SITE_NAME } from '../config';
import { getCaseArtwork } from '../assets/case-artwork';
import { Portrait } from '../components/Portrait';
import { ErrorState, LoadingState } from '../components/States';
import { useAsync } from '../hooks/useAsync';
import { usePageMotion } from '../hooks/usePageMotion';
import { fill, useI18n } from '../i18n/I18nContext';
import { imageSource } from '../api/media';
import type { CaseUpdate, PublicDonor, LedgerEntry } from '../api/public-types';
import { useHistory } from '../hooks/useHistory';
import { formatDate, formatMoney } from '../utils/format';

function ledgerLabel(kind: LedgerEntry['kind'], t: ReturnType<typeof useI18n>['t']) {
  return ({ donation: t.casePage.ledgerDonation, disbursement: t.casePage.ledgerDisbursement, transfer_in: t.casePage.ledgerTransferIn, transfer_out: t.casePage.ledgerTransferOut } as Partial<Record<LedgerEntry['kind'],string>>)[kind];
}

export function CasePage() {
  const { code = '' } = useParams();
  const location = useLocation();
  const navigationType=useNavigationType();
  const heading=useRef<HTMLHeadingElement>(null);
  const arrival=useRef({code,type:navigationType,active:document.activeElement,handled:false});
  // Track case changes, not locale changes, and avoid stealing focus after a visitor moves it.
  if(arrival.current.code!==code) arrival.current={code,type:navigationType,active:document.activeElement,handled:false};
  const source = location.state?.directoryFrom;
  const returnTo = typeof source === 'string' && (source === '/cases' || source.startsWith('/cases?')) ? source : null;
  useEffect(() => { window.scrollTo({ top: 0, behavior: 'instant' }); }, [code]);
  const { t, locale } = useI18n();
  const [sampleNote, setSampleNote] = useState(false);
  const [showStickyAction, setShowStickyAction] = useState(false);
  const requestKey=JSON.stringify([code,locale]);
  const response = useAsync(async()=>({key:requestKey,detail:await api.getCase(code,locale)}),[requestKey]);
  const result={...response,data:response.data?.key===requestKey?response.data.detail:null,loading:response.loading||!!(response.data&&response.data.key!==requestKey)};
  const updateHistory=useHistory<CaseUpdate>(result.data?.updates,result.data?.counts.updates,code,locale,api.getUpdates);
  const donorHistory=useHistory<PublicDonor>(result.data?.donors,result.data?.counts.donors,code,locale,api.getDonors);
  const ledgerHistory=useHistory<LedgerEntry>(result.data?.ledger,result.data?.counts.ledger,code,locale,api.getLedger);
  const motionRef = usePageMotion<HTMLElement>(!!result.data && !result.loading && !result.error, result.data);
  useEffect(() => {
    const title = result.loading || (result.data && result.data.case.code.toLowerCase() !== code.toLowerCase()) ? t.redesign.caseLoadingTitle : result.error || !result.data ? t.redesign.caseUnavailableTitle : result.data.case.title;
    document.title = title + ' — ' + SITE_NAME;
  }, [code, result.data, result.loading, result.error, t]);
  useEffect(()=>{
    const nav=arrival.current;
    if(nav.handled||result.loading||result.error||!result.data)return;
    nav.handled=true;
    if((nav.type==='PUSH'||nav.type==='REPLACE')&&(document.activeElement===document.body||document.activeElement===nav.active)){
      heading.current?.focus({preventScroll:true});
    }
  },[code,result.data,result.loading,result.error]);
  useEffect(() => {
    const updateStickyAction = () => setShowStickyAction(window.scrollY > 520);
    updateStickyAction();
    window.addEventListener('scroll', updateStickyAction, { passive: true });
    return () => window.removeEventListener('scroll', updateStickyAction);
  }, []);
  if (result.loading || (result.data && result.data.case.code.toLowerCase()!==code.toLowerCase())) return <LoadingState />;
  if (result.error || !result.data) return <ErrorState onRetry={result.retry} error={result.error} missingCase returnTo={returnTo ?? '/cases'} />;

  const { case: item, financials, photos } = result.data;
  const updates=updateHistory.items, donors=donorHistory.items, ledger=ledgerHistory.items;
  const actionNote=item.acceptingDonations?t.publicRead.givingNotOpen:t.publicRead.unavailableGiving;
  const givingLabel=fill(t.casePage.give,{name:item.publicName||item.title});
  // Keep the same focusable element after completion. aria-disabled prevents operation
  // without dropping keyboard focus, including while the next request is in flight.
  const historyButton=(history: ReturnType<typeof useHistory<CaseUpdate>> | ReturnType<typeof useHistory<PublicDonor>> | ReturnType<typeof useHistory<LedgerEntry>>)=><>
    {history.error && <p role="alert">{history.error==='changed'?t.publicRead.historyChanged:t.publicRead.retryHistory}</p>}
    {history.showControls && <>
      <button type="button" className="action action-outline" aria-disabled={history.loading||(!history.hasMore&&history.error!=='changed')} onClick={()=>{
        if(history.loading)return;
        if(history.error==='changed')result.retry();
        else if(history.hasMore)void history.load();
      }}>{history.error==='changed'?t.common.retry:history.loading?t.common.loading:history.hasMore?t.publicRead.more:t.publicRead.allLoaded}</button>
      <p role="status" className="history-status">{history.lastLoaded>0?fill(t.publicRead.historyLoaded,{count:String(history.lastLoaded),shown:String(history.items.length),total:String(history.total)})+(history.hasMore?'':' '+t.publicRead.allLoaded):''}</p>
    </>}
  </>;
  const receivedPercent = item.goal && item.goal > 0 ? Math.max(0, item.received / item.goal * 100) : null;
  const deliveredPercent = item.goal && item.goal > 0 ? Math.max(0, item.delivered / item.goal * 100) : null;
  const formatPercent = (value: number) => new Intl.NumberFormat(locale === 'fr' ? 'fr-FR' : 'en-US', { style: 'percent', maximumFractionDigits: 1 }).format(value / 100);
  const storyParagraphs = item.story.split('\n\n');
  const showSampleNote = () => { setSampleNote(true); window.setTimeout(() => setSampleNote(false), 3600); };

  return (
    <article className="case-page" ref={motionRef}>
      <header className="case-hero section-wrap">
        <Link className="back-link" to={returnTo ?? "/"}><span aria-hidden="true">←</span>{returnTo ? t.directory.back : t.casePage.back}</Link>
        {APP_CONFIG.sampleData && <p className="preview-label">{t.redesign.preview}</p>}
        <div className="case-hero-grid">
          <div className="case-portrait-wrap"><Portrait name={item.publicName} photoUrl={item.photoUrl} photoAlt={item.photoAlt} photoAltLanguage={item.photoAltLanguage} artwork={getCaseArtwork(item.code)} /><span className="case-code">{item.code}</span></div>
          <div className="case-identity">
            <p className="eyebrow"><span />{t.casePage.supportLabel}</p>
            <h1 ref={heading} tabIndex={-1}>{item.publicName || item.title}</h1>
            <p className="case-title" lang={item.titleLanguage}>{item.title}</p>
            {item.deadline && <p className="deadline-pill">{fill(t.casePage.deadline, { date: formatDate(item.deadline, locale, true) })}</p>}
            <button className="action action-orange case-primary-action" type="button" onClick={showSampleNote} disabled={!item.acceptingDonations}>{givingLabel}<span aria-hidden="true">↗</span></button>
            {(sampleNote || !item.acceptingDonations) && !showStickyAction && <p className="sample-note" role="status">{actionNote}</p>}
          </div>
        </div>
      </header>

      <section className="story-progress section-wrap">
        <div className="story-copy" data-motion="reveal">
          <p className="section-number">02 /</p><h2>{t.casePage.story}</h2>
          {storyParagraphs.map((paragraph,index) => <p lang={item.storyLanguage} key={index}>{paragraph}</p>)}
        </div>
        <div className="progress-panel" data-motion="funding">
          <h2>{t.casePage.progress}</h2>
          <div className="goal-label"><span>{t.casePage.goal}</span><strong>{item.goal === null ? '—' : formatMoney(item.goal, locale)}</strong></div>
          <div className="progress-values">
            <div className="value-received">
              <div className="progress-label"><span>{t.publicRead.allocated}</span><strong>{formatMoney(item.received, locale)}</strong></div>
              {receivedPercent !== null && <><div className="funding-track" aria-hidden="true"><span className="funding-received" style={{ width: Math.min(100, receivedPercent) + '%' }} /></div><small>{formatPercent(receivedPercent)} {t.caseCard.ofGoal}</small></>}
            </div>
            <div className="value-delivered">
              <div className="progress-label"><span>{t.casePage.delivered}</span><strong>{formatMoney(item.delivered, locale)}</strong></div>
              {deliveredPercent !== null && <><div className="funding-track" aria-hidden="true"><span className="funding-delivered" style={{ width: Math.min(100, deliveredPercent) + '%' }} /></div><small>{formatPercent(deliveredPercent)} {t.caseCard.ofGoal}</small></>}
            </div>
          </div>
          <p><strong>{t.publicRead.available}: {formatMoney(financials.available,locale)}</strong></p>{financials.hasShortfall && <p role="status">{t.publicRead.shortfall}</p>}
          <p className="progress-note">{t.casePage.progressNote}</p>
        </div>
      </section>

      {photos.length>0 && <section className="section-wrap"><h2>{t.publicRead.gallery}</h2><div className="featured-grid">{photos.map(photo=><img key={photo.id} src={imageSource(photo.thumbnailUrl)} alt={photo.alt} lang={photo.altLanguage} loading="lazy" style={{width:'100%',height:'auto'}} />)}</div>{APP_CONFIG.sampleData && <p>{t.redesign.illustration}</p>}</section>}
      <section className="updates-section section-wrap">
        <div className="content-heading" data-motion="reveal"><p className="section-number">03 /</p><h2>{t.casePage.updates}</h2></div>
        {updates.length ? <div className="timeline" data-motion="timeline">{updates.map((update) => <article key={update.id}><time dateTime={update.date}>{formatDate(update.date, locale, true)}</time><span className="timeline-node" aria-hidden="true" /><div><p lang={update.textLanguage}>{update.text}</p>{update.photoUrl && <img src={imageSource(update.photoUrl)} alt={update.photoAlt??''} lang={update.photoAltLanguage??undefined} loading="lazy" style={{maxWidth:'100%'}} /> }<small>{update.authorAvatarUrl && <img src={imageSource(update.authorAvatarUrl)} alt="" width={24} height={24} loading="lazy" />}{fill(t.casePage.updateBy, { author: update.author })}</small></div></article>)}</div> : <p className="empty-note">{t.casePage.noUpdates}</p>}{historyButton(updateHistory)}
      </section>

      <section className="trust-grid section-wrap">
        <div className="donors-section">
          <div className="content-heading" data-motion="reveal"><p className="section-number">04 /</p><h2>{t.casePage.donors}</h2></div>
          {donors.length ? <ul className="donor-list" data-motion="reveal">{donors.map(donor => <li key={donor.id}>{donor.avatarUrl && donor.name ? <img className="donor-dot" src={imageSource(donor.avatarUrl)} alt="" /> : <span className="donor-dot" aria-hidden="true" />}<div><strong>{donor.name ?? t.casePage.anonymous}</strong><time dateTime={donor.date}>{formatDate(donor.date, locale)}</time></div><b>{formatMoney(donor.amount, locale)}</b></li>)}</ul> : <p className="empty-note">{t.casePage.noDonors}</p>}
          {historyButton(donorHistory)}<p className="list-note">{t.casePage.donorsNote}</p>
        </div>
        <div className="ledger-section">
          <div className="content-heading" data-motion="reveal"><p className="section-number">05 /</p><div><h2>{t.casePage.ledger}</h2><p>{t.casePage.ledgerIntro}</p></div></div>
          {ledger.length ? <ul className="ledger-list" data-motion="reveal">{ledger.map(entry => <li key={entry.id} className={`ledger-${entry.kind}`}><div><span>{ledgerLabel(entry.kind, t) ?? entry.label}</span><strong>{entry.label}</strong>{entry.counterpartCaseCode && <small>{entry.counterpartCaseCode}</small>}{entry.correctedAmount>0 && <small>{t.publicRead.corrected}: {formatMoney(entry.correctedAmount,locale)} · {t.publicRead.net}: {formatMoney(entry.netAmount,locale)}</small>}<time dateTime={entry.date}>{formatDate(entry.date, locale)}</time></div><b>{entry.allocatedDelta < 0 || entry.deliveredDelta > 0 ? '−' : '+'}{formatMoney(entry.amount, locale)}</b></li>)}</ul> : <p className="empty-note">{t.casePage.noLedger}</p>}{historyButton(ledgerHistory)}
        </div>
      </section>

      <section className="rules-band section-wrap">
        <p className="eyebrow eyebrow-light"><span />{t.casePage.beforeGiving}</p>
        <div className="rules-grid" data-motion="steps"><p>{t.casePage.excessRule}</p><p>{t.casePage.deadlineRule}</p><p>{t.casePage.decisionRule}</p><p>{t.casePage.pendingRule}</p></div>
      </section>

      {showStickyAction && <div className="give-dock">
          <button className="action action-orange give-action" type="button" onClick={showSampleNote} disabled={!item.acceptingDonations}>{givingLabel}<span aria-hidden="true">↗</span></button>
          {(sampleNote || !item.acceptingDonations) && <p role="status">{actionNote}</p>}
        </div>}
    </article>
  );
}
