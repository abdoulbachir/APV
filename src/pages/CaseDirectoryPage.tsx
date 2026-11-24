import { useEffect, useRef } from 'react';
import { Link, useSearchParams, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import { APP_CONFIG, SITE_NAME } from '../config';
import { CompactCaseCard } from '../components/CompactCaseCard';
import { ErrorState, LoadingState } from '../components/States';
import { useAsync } from '../hooks/useAsync';
import { usePageMotion } from '../hooks/usePageMotion';
import { fill, useI18n } from '../i18n/I18nContext';
import { browseCases, categories, categoryFor, PAGE_SIZE, sorts, type Sort } from '../utils/case-directory';

export function CaseDirectoryPage() {
  const { t, locale } = useI18n();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const result = useAsync(() => api.getCases('active', locale), [locale], true);
  const root = usePageMotion<HTMLElement>(!!result.data && !result.error, location.search + locale);
  const heading = useRef<HTMLHeadingElement>(null);
  const firstResult = useRef<HTMLHeadingElement>(null);
  const initialFocus = useRef(false);
  const query = params.get('q') ?? '';
  const rawCategory = params.get('category');
  const category = APP_CONFIG.apiMode === 'mock' && categories.some(c => c === rawCategory) ? rawCategory! : 'all';
  const rawSort = params.get('sort');
  const sort: Sort = sorts.find(s => s === rawSort) ?? 'newest';
  const view = params.get('view') === 'list' ? 'list' : 'grid';
  const requestedPage = Number(params.get('page') ?? 1);
  const rows = browseCases(result.data ?? [], query, category, sort, item => {
    const key = categoryFor(item);
    return key ? t.directory.categories[key] : '';
  });
  const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const page = Number.isSafeInteger(requestedPage) ? Math.min(pages, Math.max(1, requestedPage)) : 1;
  const start = (page - 1) * PAGE_SIZE;
  const visible = rows.slice(start, start + PAGE_SIZE);
  const pageButtons = [...new Set([1, page - 1, page, page + 1, pages])].filter(n => n >= 1 && n <= pages).sort((a,b) => a-b);

  useEffect(() => { document.title = t.directory.title + ' — ' + SITE_NAME; }, [t.directory.title]);
  useEffect(() => {
    if (result.data && !initialFocus.current) {
      initialFocus.current = true;
      heading.current?.focus({ preventScroll: true });
      window.scrollTo({ top: 0, behavior: 'instant' });
    }
  }, [result.data]);
  useEffect(() => {
    if (!result.data || result.loading || !params.has('page') || String(page) === params.get('page')) return;
    const next = new URLSearchParams(params);
    if (page === 1) next.delete('page'); else next.set('page', String(page));
    setParams(next, { replace: true });
  }, [page, params, result.data, result.loading, setParams]);

  function change(key: string, value: string, reset = true, replace = false) {
    const next = new URLSearchParams(params);
    if (!value || (key === 'category' && value === 'all') || (key === 'sort' && value === 'newest') || (key === 'view' && value === 'grid') || (key === 'page' && value === '1')) next.delete(key);
    else next.set(key, value);
    if (reset) next.delete('page');
    setParams(next, { replace });
  }
  function goPage(n: number) {
    change('page', String(n), false);
    firstResult.current?.focus({ preventScroll: true });
    firstResult.current?.scrollIntoView({ block: 'start' });
  }
  function clear() {
    const next = new URLSearchParams(params);
    for (const key of ['q', 'category', 'sort', 'page']) next.delete(key);
    setParams(next);
  }

  if (result.loading && !result.data) return <LoadingState />;
  if (result.error || !result.data) return <ErrorState onRetry={result.retry} />;

  return (
    <section className="directory section-wrap" ref={root} aria-busy={result.loading}>
      <header className="directory-head">
        <Link className="back-link" to="/">← {t.nav.home}</Link>
        {APP_CONFIG.apiMode === 'mock' && <p className="preview-label">{t.redesign.preview}</p>}
        <p className="eyebrow"><span />{t.home.activeCases}</p>
        <h1 ref={heading} tabIndex={-1}>{t.directory.title}</h1>
        <p>{t.directory.intro}</p>
      </header>
      <div className="directory-controls">
        <label className="directory-search"><span>{t.directory.search}</span><input type="search" value={query} placeholder={t.directory.searchPlaceholder} onChange={e => change('q', e.target.value, true, true)} /></label>
        {APP_CONFIG.apiMode === 'mock' && <label><span>{t.directory.category}</span><select value={category} onChange={e => change('category', e.target.value)}><option value="all">{t.directory.allCategories}</option>{categories.map(c => <option key={c} value={c}>{t.directory.categories[c]}</option>)}</select></label>}
        <label><span>{t.directory.sort}</span><select value={sort} onChange={e => change('sort', e.target.value)}>{sorts.map(s => <option key={s} value={s}>{t.directory.sorts[s]}</option>)}</select></label>
        <button className="action action-outline" type="button" onClick={clear}>{t.directory.clear}</button>
      </div>
      <div className="directory-toolbar">
        <h2 ref={firstResult} tabIndex={-1} aria-live="polite">{fill(t.directory.count, {count: String(rows.length)})}</h2>
        <div className="directory-view" role="group" aria-label={t.directory.view}>
          <button type="button" aria-pressed={view === 'grid'} onClick={() => change('view', 'grid', false)}>{t.directory.grid}</button>
          <button type="button" aria-pressed={view === 'list'} onClick={() => change('view', 'list', false)}>{t.directory.list}</button>
        </div>
      </div>
      {visible.length ? <>
        <div className={'directory-grid directory-view-' + view}>{visible.map(item => <CompactCaseCard key={item.code} item={item} from={location.pathname + location.search}/>)}</div>
        <p className="directory-range">{fill(t.directory.range, { start: String(start + 1), end: String(start + visible.length), total: String(rows.length) })}</p>
        {pages > 1 && <nav className="directory-pagination" aria-label={t.directory.pagination}>
          <button type="button" disabled={page === 1} onClick={() => goPage(page - 1)}>{t.directory.previous}</button>
          {pageButtons.map((n,i) => <span className="directory-page-item" key={n}>{i > 0 && n - pageButtons[i-1] > 1 && <span aria-hidden="true">…</span>}<button type="button" aria-current={n === page ? 'page' : undefined} aria-label={fill(t.directory.page, {number: String(n)})} onClick={() => goPage(n)}>{n}</button></span>)}
          <button type="button" disabled={page === pages} onClick={() => goPage(page + 1)}>{t.directory.next}</button>
        </nav>}
      </> : <div className="directory-empty"><h3>{t.directory.empty}</h3><button type="button" className="action action-outline" onClick={clear}>{t.directory.clear}</button></div>}
    </section>
  );
}
