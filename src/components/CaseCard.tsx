import { Link } from 'react-router-dom';
import type { Case } from '../types';
import { fill, useI18n } from '../i18n/I18nContext';
import { formatDate, formatMoney } from '../utils/format';
import { Portrait } from './Portrait';

export function CaseCard({ item }: { item: Case }) {
  const { t, locale } = useI18n();
  const ratio = item.goal ? Math.min(100, Math.round(item.received / item.goal * 100)) : 0;
  return (
    <article className="case-card">
      <div className="case-card-portrait"><Portrait name={item.publicName} variant="small" /><span className="case-code">{item.code}</span><span className="media-orbit" aria-hidden="true" /></div>
      <div className="case-card-copy">
        <div className="case-card-top"><p className="case-person">{item.publicName}</p>{item.deadline && <p className="case-deadline">{t.home.deadlineLabel} <strong>{formatDate(item.deadline, locale, true)}</strong></p>}</div>
        <h3>{item.title}</h3>
        <div className="mini-line" aria-label={`${ratio}%`}>
          <div className="mini-amounts">
            <span><b>{formatMoney(item.received, locale)}</b> {t.caseCard.received}</span>
            <span><b>{formatMoney(item.delivered, locale)}</b> {t.caseCard.delivered}</span>
          </div>
          <div className="mini-line-track"><span style={{ width: `${ratio}%` }} /></div>
          <small>{ratio}% {t.caseCard.ofGoal}</small>
        </div>
        <Link className="action action-orange" to={`/cases/${item.code}`} aria-label={fill(t.caseCard.openCase, { name: item.publicName })}>{t.home.viewCase}<span aria-hidden="true">↗</span></Link>
      </div>
    </article>
  );
}
