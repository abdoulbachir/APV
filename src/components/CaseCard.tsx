import { Link } from 'react-router-dom';
import type { Case } from '../types';
import { fill, useI18n } from '../i18n/I18nContext';
import { formatDate, formatMoney } from '../utils/format';
import { Portrait } from './Portrait';
export function CaseCard({ item }: { item: Case }) {
  const { t, locale } = useI18n();
  const ratio = item.goal && item.goal > 0 ? Math.max(0, Math.round(item.received / item.goal * 100)) : null;
  const delivery = item.goal && item.goal > 0 ? Math.max(0, item.delivered / item.goal * 100) : 0;
  const money = (cents: number) => formatMoney(cents, locale);
  return <article className="case-card">
    <div className="case-card-portrait"><Portrait name={item.publicName} photoUrl={item.photoUrl} code={item.code} variant="small" /><span className="case-code">{item.code}</span>{!item.photoUrl && <span className="art-caption">{t.redesign.illustration}</span>}</div>
    <div className="case-card-copy">
      <div className="case-card-top"><p className="case-person">{item.publicName}</p>{item.deadline && <p className="case-deadline">{t.home.deadlineLabel} <strong>{formatDate(item.deadline, locale, true)}</strong></p>}</div>
      <h3>{item.title}</h3>
      <p className="case-excerpt">{item.story.split('\n\n')[0]}</p>
      <div className="mini-line">
        <div className="mini-amounts"><span><b>{money(item.received)}</b>{t.stats.received}</span><span><b>{money(item.delivered)}</b>{t.stats.delivered}</span><span><b>{item.goal === null ? '—' : money(item.goal)}</b>{t.casePage.goal}</span></div>
        {ratio !== null && <><div className="mini-line-track" aria-hidden="true"><span style={{width: Math.min(100, ratio) + '%'}} /><i style={{width: Math.min(100, delivery) + '%'}} /></div><div className="mini-caption"><small>{ratio}% {t.redesign.receivedOfGoal}</small><small>{money(Math.max(0, (item.goal ?? 0) - item.received))} {t.redesign.remaining}</small></div></>}
      </div>
      <Link className="action action-lime" to={'/cases/' + item.code} aria-label={fill(t.caseCard.openCase, {name:item.publicName})}>{t.redesign.readStory}<span aria-hidden="true">↗</span></Link>
    </div>
  </article>;
}
