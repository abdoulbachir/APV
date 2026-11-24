import type { Stats } from '../types';
import { useI18n } from '../i18n/I18nContext';
import { formatMoney } from '../utils/format';

export function StatsStrip({ stats }: { stats: Stats }) {
  const { t, locale } = useI18n();
  return (
    <section className="stats-strip" aria-label={t.stats.aria}>
      <div className="stat stat-received"><span className="stat-index" aria-hidden="true" /><strong>{formatMoney(stats.received, locale, true)}</strong><span>{t.stats.received}</span><small>{t.stats.receivedNote}</small></div>
      <div className="stat stat-delivered"><span className="stat-index" aria-hidden="true" /><strong>{formatMoney(stats.delivered, locale, true)}</strong><span>{t.stats.delivered}</span><small>{t.stats.deliveredNote}</small></div>
      <div className="stat stat-helped"><span className="stat-index" aria-hidden="true" /><strong>{stats.peopleHelped}</strong><span>{t.stats.helped}</span><small>{t.stats.helpedNote}</small></div>
    </section>
  );
}
