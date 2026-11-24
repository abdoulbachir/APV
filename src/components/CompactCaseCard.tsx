import { Link } from 'react-router-dom';
import type { Case } from '../types';
import { useI18n, fill } from '../i18n/I18nContext';
import { formatMoney, formatDate } from '../utils/format';
import { categoryFor, goalRatio } from '../utils/case-directory';
import { APP_CONFIG } from '../config';
import { Portrait } from './Portrait';
import { getCaseArtwork } from '../assets/case-artwork';

export function CompactCaseCard({ item, from }: { item: Case; from?: string }) {
  const { t, locale } = useI18n();
  const category = categoryFor(item);
  const ratio = goalRatio(item);
  const percent = ratio === null ? null : new Intl.NumberFormat(locale === 'fr' ? 'fr-FR' : 'en-US', { style: 'percent', maximumFractionDigits: 1 }).format(ratio);
  const featuredArtwork = APP_CONFIG.apiMode === 'mock' && item.code === 'HELP-014';
  return (
    <article className="compact-case" data-motion="funding">
      <div className={'compact-art compact-art-' + (category ?? 'other')}>
        {item.photoUrl || featuredArtwork ? <Portrait name={item.publicName} photoUrl={item.photoUrl} artwork={getCaseArtwork(item.code)} variant="small" /> :
          <div className="compact-symbol" aria-hidden="true"><svg viewBox="0 0 180 100" fill="none"><path d="M-5 83C35 83 34 25 79 25s38 53 106 35M-5 95C45 95 45 43 83 43s40 44 102 25" /><circle cx="79" cy="25" r="5"/><circle cx="83" cy="43" r="3"/></svg></div>}
        <span className="compact-code">{item.code}</span>
      </div>
      <div className="compact-copy">
        {category && <p className="compact-category">{t.directory.categories[category]}</p>}
        <h3>{item.title}</h3>
        <p className="compact-name">{item.publicName}</p>
        <div className="compact-money"><strong>{formatMoney(item.received, locale)}</strong><span>{t.stats.received}</span></div>
        <p className="compact-goal">{t.casePage.goal}: {item.goal === null ? '—' : formatMoney(item.goal, locale)}</p>
        {ratio !== null && <><div className="funding-track" aria-hidden="true"><span className="funding-received" style={{width: Math.min(100, ratio * 100) + '%'}}/></div><p className="compact-percent">{percent} {t.caseCard.ofGoal}</p></>}
        <p className="compact-delivered">{t.stats.delivered}: <strong>{formatMoney(item.delivered, locale)}</strong></p>
        <p className="compact-deadline">{item.deadline ? t.home.deadlineLabel + ' ' + formatDate(item.deadline, locale, true) : t.directory.noDeadline}</p>
        <Link className="action action-outline compact-link" to={'/cases/' + encodeURIComponent(item.code)} state={from ? { directoryFrom: from } : undefined} aria-label={fill(t.caseCard.openCase, { name: item.publicName })}>{t.redesign.readStory}<span aria-hidden="true">↗</span></Link>
      </div>
    </article>
  );
}
