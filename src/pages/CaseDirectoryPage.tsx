import { useEffect, useRef } from 'react';
import { Link, useSearchParams, useLocation, useNavigationType } from 'react-router-dom';
import { api } from '../api/client';
import { APP_CONFIG, SITE_NAME } from '../config';
import { CompactCaseCard } from '../components/CompactCaseCard';
import { ErrorState, LoadingState } from '../components/States';
import { useAsync } from '../hooks/useAsync';
import { usePageMotion } from '../hooks/usePageMotion';
import { fill, useI18n } from '../i18n/I18nContext';
import { PAGE_SIZE, searchValid, sorts, type Sort } from '../utils/case-directory';

export function CaseDirectoryPage() {
  const { t, locale } = useI18n();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const navigationType = useNavigationType();
  const arrival = useRef({ type: navigationType, active: document.activeElement });
  const heading = useRef<HTMLHeadingElement>(null);
  const firstResult = useRef<HTMLHeadingElement>(null);
  const initialFocus = useRef(false);
  const query = params.get('q') ?? '';
  const rawCategory = params.get('category');
  const siteResult = useAsync(() => api.getSite(locale), [locale], true);
  const categories = siteResult.data?.categories ?? [];
  const category = categories.some(c=>c.id===rawCategory) ? rawCategory! : 'all';
  const rawSort = params.get('sort');
  const sort: Sort = sorts.find(s => s === rawSort) ?? 'newest';
  const view = params.get('view') === 'list' ? 'list' : 'grid';
  const requestedPage = Number(params.get('page') ?? 1);
  const validQuery = searchValid(query);
  const requested = Number.isSafeInteger(requestedPage) ? Math.min(100000, Math.max(1, requestedPage)) : 1;
  const requestKey=JSON.stringify([locale,query,category,sort,requested]);
  const result = useAsync(async () => ({
    key: requestKey,
    response: await api.getCases({status:'active', q:validQuery?query:'', category:category==='all'?undefined:category, sort, page:requested, pageSize:PAGE_SIZE},locale)
  }), [requestKey], true);
  const current = result.data?.key===requestKey ? result.data.response : null;
  const fetching = result.loading || !current;
  const root = usePageMotion<HTMLElement>(!!result.data && !result.error, result.data);
  const pages = current?.totalPages ?? 1;
  const page = Math.min(pages,requested);
  const start = (page - 1) * PAGE_SIZE;
  const visible = validQuery ? current?.items ?? [] : [];
  const total = validQuery ? current?.totalItems ?? 0 : 0;
  const pageButtons = [...new Set([1, page - 1, page, page + 1, pages])].filter(n => n >= 1 && n <= pages).sort((a,b) => a-b);

  useEffect(() => { document.title = t.directory.title + ' — ' + SITE_NAME; }, [t.directory.title]);
  useEffect(() => {
    if (siteResult.data && current && !initialFocus.current) {
      initialFocus.current = true;
      // A fresh document or history traversal should not steal focus.
      // A new SPA navigation may focus the title if the visitor has not moved elsewhere.
      if (arrival.current.type === 'PUSH' || arrival.current.type === 'REPLACE') {
        if (document.activeElement === document.body || document.activeElement === arrival.current.active) {
          heading.current?.focus({ preventScroll: true });
          window.scrollTo({ top: 0, behavior: 'instant' });
        }
      }
    }
  }, [siteResult.data,current]);
  useEffect(() => {
    if (!siteResult.data || !current || result.loading || result.error) return;
    const next = new URLSearchParams(params);
    // Preserve unrelated parameters such as lang and dev-only test scenarios.
    const canonical: Record<string, string | null> = {
      q: query ? query : null,
      category: category === 'all' ? null : category,
      sort: sort === 'newest' ? null : sort,
      view: view === 'grid' ? null : view,
      page: page === 1 ? null : String(page),
    };
    for (const [key, value] of Object.entries(canonical)) {
      if (value === null) next.delete(key); else next.set(key, value);
    }
    if (next.toString() !== params.toString()) setParams(next, { replace: true });
  }, [page, query, category, sort, view, params, siteResult.data, current, result.loading, result.error, setParams]);

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

  if ((siteResult.loading && !siteResult.data) || (result.loading && !result.data)) return <LoadingState />;
  if (siteResult.error || result.error || !result.data || !siteResult.data) return <ErrorState onRetry={()=>{siteResult.retry();result.retry();}} />;

  return (
    <section className="directory section-wrap" ref={root} aria-busy={fetching}>
      <header className="directory-head">
        <Link className="back-link" to="/">← {t.nav.home}</Link>
        {APP_CONFIG.sampleData && <p className="preview-label">{t.redesign.preview}</p>}
        <p className="eyebrow"><span />{t.home.activeCases}</p>
        <h1 ref={heading} tabIndex={-1}>{t.directory.title}</h1>
        <p>{t.directory.intro}</p>
      </header>
      <div className="directory-controls">
        <label className="directory-search"><span>{t.directory.search}</span><input type="search" value={query} placeholder={t.directory.searchPlaceholder} aria-invalid={!validQuery} onChange={e => change('q', e.target.value, true, true)} />{!validQuery && <small role="alert">{t.publicRead.tooLong}</small>}</label>
        {<label><span>{t.directory.category}</span><select value={category} onChange={e => change('category', e.target.value)}><option value="all">{t.directory.allCategories}</option>{categories.filter(c=>c.active||c.id===category).map(c => <option key={c.id} value={c.id}>{c.labels[locale]}</option>)}</select></label>}
        <label><span>{t.directory.sort}</span><select value={sort} onChange={e => change('sort', e.target.value)}>{sorts.map(s => <option key={s} value={s}>{t.directory.sorts[s]}</option>)}</select></label>
        <button className="action action-outline" type="button" onClick={clear}>{t.directory.clear}</button>
      </div>
      <div className="directory-toolbar">
        <h2 ref={firstResult} tabIndex={-1} aria-live="polite">{fill(t.directory.count, {count: String(total)})}</h2>
        <div className="directory-view" role="group" aria-label={t.directory.view}>
          <button type="button" aria-pressed={view === 'grid'} onClick={() => change('view', 'grid', false)}>{t.directory.grid}</button>
          <button type="button" aria-pressed={view === 'list'} onClick={() => change('view', 'list', false)}>{t.directory.list}</button>
        </div>
      </div>
      {fetching ? <p role="status">{t.common.loading}</p> : visible.length ? <>
        <div className={'directory-grid directory-view-' + view}>{visible.map(item => <CompactCaseCard key={item.code} item={item} categoryLabel={categories.find(c=>c.id===item.category)?.labels[locale]} from={location.pathname + location.search}/>)}</div>
        <p className="directory-range">{fill(t.directory.range, { start: String(start + 1), end: String(start + visible.length), total: String(total) })}</p>
        {pages > 1 && <nav className="directory-pagination" aria-label={t.directory.pagination}>
          <button type="button" disabled={page === 1} onClick={() => goPage(page - 1)}>{t.directory.previous}</button>
          {pageButtons.map((n,i) => <span className="directory-page-item" key={n}>{i > 0 && n - pageButtons[i-1] > 1 && <span aria-hidden="true">…</span>}<button type="button" aria-current={n === page ? 'page' : undefined} aria-label={fill(t.directory.page, {number: String(n)})} onClick={() => goPage(n)}>{n}</button></span>)}
          <button type="button" disabled={page === pages} onClick={() => goPage(page + 1)}>{t.directory.next}</button>
        </nav>}
      </> : <div className="directory-empty"><h3>{t.directory.empty}</h3><button type="button" className="action action-outline" onClick={clear}>{t.directory.clear}</button></div>}
    </section>
  );
}
