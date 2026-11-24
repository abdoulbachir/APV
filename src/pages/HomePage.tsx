import { useEffect } from 'react';
import { api } from '../api/client';
import { CaseCard } from '../components/CaseCard';
import { ErrorState, LoadingState } from '../components/States';
import { StatsStrip } from '../components/StatsStrip';
import { Portrait } from '../components/Portrait';
import { Link } from 'react-router-dom';
import { useAsync } from '../hooks/useAsync';
import { useI18n } from '../i18n/I18nContext';
import { formatMoney } from '../utils/format';

export function HomePage() {
  const { t, locale } = useI18n();
  const result = useAsync(async () => {
    const [stats, cases] = await Promise.all([api.getStats(locale), api.getCases('active', locale)]);
    return { stats, cases };
  }, [locale]);

  useEffect(() => { document.title = t.meta.homeTitle; }, [t]);
  if (result.loading) return <LoadingState />;
  if (result.error || !result.data) return <ErrorState onRetry={result.retry} />;

  const activeCases = result.data.cases.filter((item) => item.kind === 'case');
  const fund = result.data.cases.find((item) => item.kind === 'fund');

  return (
    <>
      <section className="home-hero">
        <div className="hero-field" aria-hidden="true"><i /><i /><i /><i /><i /></div>
        <div className="hero-copy">
          <p className="eyebrow"><span />{t.home.eyebrow}</p>
          <h1><span>{t.home.headlineLead}</span><em>{t.home.headlineAccent}</em></h1>
          <p className="hero-purpose">{t.home.purpose}</p>
          {activeCases[0] && <Link className="action action-lime hero-action" to={`/cases/${activeCases[0].code}`}>{t.home.viewCase}<span aria-hidden="true">↗</span></Link>}
        </div>
        {activeCases[0] && <div className="hero-visual"><Portrait name={activeCases[0].publicName} /><span className="hero-orbit orbit-one" aria-hidden="true" /><span className="hero-orbit orbit-two" aria-hidden="true" /><div className="hero-case-tag"><span>{activeCases[0].code}</span><strong>{activeCases[0].publicName}</strong></div></div>}
        <StatsStrip stats={result.data.stats} />
      </section>

      <section className="active-section section-wrap" id="cases">
        <header className="section-heading">
          <p className="section-number">01 /</p>
          <div><h2>{t.home.activeCases}</h2><p>{t.home.activeIntro}</p></div>
        </header>
        {activeCases.length ? activeCases.map((item) => <CaseCard key={item.code} item={item} />) : <p className="empty-note">{t.common.emptyCases}</p>}
      </section>

      <section className="fund-band section-wrap">
        <div className="fund-orbit" aria-hidden="true"><i /><i /></div>
        <div className="fund-copy">
          <p className="eyebrow eyebrow-light"><span />{t.home.generalFund}</p>
          <h2>{t.home.fundTitle}</h2>
          <p>{t.home.fundBody}</p>
        </div>
        <div className="fund-total">
          <span>{t.home.fundBalance}</span>
          <strong>{formatMoney((fund?.received ?? 0) - (fund?.delivered ?? 0), locale)}</strong>
          <span className="future-link" aria-disabled="true">{t.home.fundSoon}</span>
        </div>
      </section>

      <section className="how-section section-wrap" id="how">
        <div className="how-title">
          <p className="eyebrow"><span />{t.home.howEyebrow}</p>
          <h2>{t.home.howTitle}</h2>
        </div>
        <ol className="how-steps">
          {t.home.steps.map((step) => <li key={step.number}><span>{step.number}</span><div><h3>{step.title}</h3><p>{step.text}</p></div></li>)}
        </ol>
        <div className="completed-callout"><span aria-hidden="true">↘</span><p>{t.home.completed}</p><span className="future-link" aria-disabled="true">{t.home.completedSoon}</span></div>
      </section>
    </>
  );
}
