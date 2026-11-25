import { Link } from 'react-router-dom';
import type { Case } from '../types';
import { fill, useI18n } from '../i18n/I18nContext';
import { formatDate, formatMoney } from '../utils/format';
import { getCaseArtwork } from '../assets/case-artwork';
import { Portrait } from './Portrait';
export function CaseCard({ item }: { item: Case }) {
  const { t, locale } = useI18n();
  const ratio = item.goal && item.goal > 0 ? Math.max(0, item.received / item.goal * 100) : null;
  const delivery = item.goal && item.goal > 0 ? Math.max(0, item.delivered / item.goal * 100) : null;
  const formatPercent = (value: number) => new Intl.NumberFormat(locale === 'fr' ? 'fr-FR' : 'en-US', { style: 'percent', maximumFractionDigits: 1 }).format(value / 100);
  const paragraph = item.story.split('\n\n')[0];
  const excerpt = paragraph.length > 230 ? paragraph.slice(0, 230).replace(/\s+\S*$/, '') + '…' : paragraph;
  const money = (cents: number) => formatMoney(cents, locale);
  return <article className="case-card" data-motion="funding">
    <div className="case-card-portrait"><Portrait name={item.publicName} photoUrl={item.photoThumbnailUrl} photoAlt={item.photoAlt} photoAltLanguage={item.photoAltLanguage} artwork={getCaseArtwork(item.code)} variant="small" /><span className="case-code">{item.code}</span></div>
    <div className="case-card-copy">
      <div className="case-card-top"><p className="case-person">{item.publicName}</p>{item.deadline && <p className="case-deadline">{t.home.deadlineLabel} <strong>{formatDate(item.deadline, locale, true)}</strong></p>}</div>
      <h3 lang={item.titleLanguage}>{item.title}</h3>
      <p className="case-excerpt" lang={item.storyLanguage}>{excerpt}</p>
      <div className="mini-line">
        <div className="mini-amounts"><span><b>{money(item.received)}</b>{t.publicRead.allocated}</span><span><b>{money(item.delivered)}</b>{t.stats.delivered}</span><span><b>{item.goal === null ? '—' : money(item.goal)}</b>{t.casePage.goal}</span></div>
        {ratio !== null && delivery !== null && <>
          <div className="mini-progress-row"><small>{t.publicRead.allocated} · {formatPercent(ratio)}</small><div className="funding-track" aria-hidden="true"><span className="funding-received" style={{ width: Math.min(100, ratio) + '%' }} /></div></div>
          <div className="mini-progress-row"><small>{t.stats.delivered} · {formatPercent(delivery)}</small><div className="funding-track" aria-hidden="true"><span className="funding-delivered" style={{ width: Math.min(100, delivery) + '%' }} /></div></div>
          <div className="mini-caption"><small>{money(Math.max(0, (item.goal ?? 0) - item.received))} {t.redesign.remaining}</small></div>
        </>}
      </div>
      <Link className="action action-lime" to={'/cases/' + item.code} aria-label={fill(t.caseCard.openCase, {name:item.publicName})}>{t.redesign.readStory}<span aria-hidden="true">↗</span></Link>
    </div>
  </article>;
}
