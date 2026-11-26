import { useEffect, useRef } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { CaseCard } from '../components/CaseCard';
import { CompactCaseCard } from '../components/CompactCaseCard';
import { fill } from '../i18n/I18nContext';
import { ErrorState, LoadingState } from '../components/States';
import { StatsStrip } from '../components/StatsStrip';
import { useAsync } from '../hooks/useAsync';
import { usePageMotion } from '../hooks/usePageMotion';
import { useI18n } from '../i18n/I18nContext';
import { formatMoney } from '../utils/format';
import { APP_CONFIG } from '../config';
import { artworkSource } from '../assets/case-artwork';

const heroArt = artworkSource('renewal');

export function HomePage() {
  const { t, locale } = useI18n();
  const { hash, key } = useLocation();
  const handledNavigation = useRef<string | null>(null);
  const result = useAsync(async () => {
    const site = await api.getSite(locale);
    const [stats, cases, fund] = await Promise.all([api.getStats(locale), api.getCases({ status:'active', sort:'featured', pageSize:4 }, locale), 
      // A site that has no general fund yet (a new real database) still has a home page: the fund panel says so.
      api.getCase(site.fundCode,locale).catch(error => { if (error instanceof ApiError && error.code === 'case_not_found') return null; throw error; })]);
    return { stats, cases, fund, site };
  }, [locale], true);
  const motionRef = usePageMotion<HTMLDivElement>(!!result.data && !result.error, result.data);
  useEffect(() => { document.title = t.meta.homeTitle; }, [t]);
  useEffect(() => {
    if (handledNavigation.current === key) return;
    if (!hash) { handledNavigation.current = key; return; }
    if (!result.data || result.error) return;
    const target = document.getElementById(hash.slice(1));
    if (target) { handledNavigation.current = key; target.focus({ preventScroll: true }); target.scrollIntoView(); }
  }, [key, hash, result.data, result.error]);
  if (result.loading && !result.data) return <LoadingState />;
  if (result.error || !result.data) return <ErrorState onRetry={result.retry} />;
  const { stats, cases, fund, site } = result.data;
  const [featured, ...additional] = cases.items;
  const deliveredRatio = stats.received > 0 ? stats.delivered / stats.received : null;
  const barMax = Math.max(stats.received, stats.delivered, 1);
  const percent = new Intl.NumberFormat(locale === 'fr' ? 'fr-FR' : 'en-US', { style: 'percent', maximumFractionDigits: 1 });
  return <div className="home-page" ref={motionRef}>
    <section className="home-hero" aria-busy={result.loading}>
      <div className="hero-art"><img src={heroArt.url} alt="" /><svg className="hero-thread" viewBox="0 0 1200 620" preserveAspectRatio="xMidYMid slice" aria-hidden="true" focusable="false"><path className="hero-thread-main" pathLength={1} d="M-40 570 C180 610 320 480 515 485 S780 585 1000 390 S1170 255 1240 230" /><path className="hero-thread-echo" pathLength={1} d="M-40 592 C180 634 325 512 520 508 S785 600 1005 420 S1170 280 1240 250" /></svg><span className="hero-art-caption">{heroArt.generated ? t.redesign.generatedIllustration : t.redesign.illustration}</span></div>
      <div className="hero-copy">
        {APP_CONFIG.sampleData && <p className="preview-label">{t.redesign.preview}</p>}
        <p className="eyebrow"><span />{t.home.eyebrow}</p>
        <h1><span>{t.home.headlineLead}</span><em>{t.home.headlineAccent}</em></h1>
        <p className="hero-purpose">{t.home.purpose}</p>
        <div className="hero-actions">
          <Link className="action action-lime" to="/#cases">{t.redesign.explore}<span aria-hidden="true">↗</span></Link>
          <Link className="action action-outline" to="/#how">{t.nav.how}<span aria-hidden="true">↓</span></Link>
        </div>
      </div>
      <StatsStrip stats={stats} />
    </section>
    <section className="active-section section-wrap" id="cases" tabIndex={-1} aria-labelledby="cases-heading">
      <header className="v2-heading" data-motion="reveal"><p className="eyebrow"><span />{t.home.activeCases}</p><div><h2 id="cases-heading">{t.redesign.activeLead} <em>{t.redesign.activeAccent}</em></h2><p>{t.home.activeIntro}</p></div></header>
      <div className="case-list">{featured ? <CaseCard item={featured} /> : <p className="empty-note">{t.common.emptyCases}</p>}</div>
      {additional.length > 0 && <div className="featured-grid">{additional.map(item => <CompactCaseCard key={item.code} item={item} categoryLabel={site.categories.find(c=>c.id===item.category)?.labels[locale]} />)}</div>}
      <div className="directory-entry"><Link className="action action-lime" to="/cases">{fill(t.directory.exploreAll, {count: String(cases.totalItems)})}<span aria-hidden="true">↗</span></Link></div>
    </section>
    <section className="transparency-section section-wrap" id="record" tabIndex={-1} aria-labelledby="record-heading">
      <header className="v2-heading" data-motion="reveal"><p className="eyebrow"><span />{t.redesign.publicRecord}</p><div><h2 id="record-heading">{t.redesign.transparency}</h2><p>{t.redesign.transparencyIntro}</p></div></header>
      <div className="transparency-grid">
        <div className="record-panel" data-motion="funding">
          <h3>{t.redesign.fundsOverview}</h3>
          <div className="record-row"><span>{t.stats.received}</span><strong>{formatMoney(stats.received, locale)}</strong><div className="record-track"><span style={{width: Math.max(0, stats.received / barMax * 100) + '%'}} /></div></div>
          <div className="record-row delivered-row"><span>{t.stats.delivered}</span><strong>{formatMoney(stats.delivered, locale)}</strong><div className="record-track"><span style={{width: Math.max(0, stats.delivered / barMax * 100) + '%'}} /></div></div>
          {deliveredRatio !== null && <p className="record-ratio"><strong>{percent.format(deliveredRatio)}</strong> {t.redesign.deliveredRatio}</p>}
        </div>
        <div className="fund-panel" data-motion="reveal"><div className="fund-intro"><p className="eyebrow">{t.home.generalFund}</p><h3>{t.home.fundTitle}</h3>{fund ? <strong className="fund-value">{formatMoney(fund.financials.available, locale)}</strong> : <p>{t.redesign.unavailable}</p>}<p>{t.home.fundBalance}</p><p className="fund-explain">{t.home.fundBody}</p>{APP_CONFIG.sampleData && <p className="fund-preview">{t.home.fundSoon}</p>}</div>{fund && <dl className="fund-breakdown"><div><dt>{t.publicRead.fundDonations}</dt><dd>{formatMoney(fund.financials.externalDonations, locale)}</dd></div><div><dt>{t.publicRead.fundAllocated}</dt><dd>{formatMoney(fund.financials.transfersOut-fund.financials.transfersIn, locale)}</dd></div></dl>}<p className="fund-give"><Link className="action action-lime" to="/give">{t.home.fundGive}<span aria-hidden="true">↗</span></Link></p></div>
      </div>
    </section>
    <section className="how-section section-wrap" id="how" tabIndex={-1} aria-labelledby="how-heading">
      <header className="v2-heading" data-motion="reveal"><p className="eyebrow"><span />{t.home.howEyebrow}</p><div><h2 id="how-heading">{t.redesign.howLead}</h2></div></header>
      <ol className="how-steps" data-motion="steps">{t.home.steps.map(step => <li key={step.number}><span>{step.number}</span><div><h3>{step.title}</h3><p>{step.text}</p></div></li>)}</ol>
      <div className="completed-callout" data-motion="reveal"><span aria-hidden="true">↘</span><p>{t.home.completed}</p><span className="future-link">{t.redesign.completedUnavailable}</span></div>
      {APP_CONFIG.sampleData && <p className="preview-notice">{t.redesign.givingPreview}</p>}
    </section>
  </div>;
}
