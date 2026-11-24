import { useEffect } from 'react';
import { Link, isRouteErrorResponse, useRouteError, useSearchParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { SITE_NAME } from '../config';
import { useI18n } from '../i18n/I18nContext';

export function LoadingState() {
  const { t } = useI18n();
  return <div className="state state-loading" role="status"><span className="loading-path" aria-hidden="true"><i /><i /><i /></span><p>{t.common.loading}</p></div>;
}

export function MissingState({ kind = 'page', returnTo = '/cases' }: { kind?: 'page' | 'case'; returnTo?: string }) {
  const { t } = useI18n();
  const title = kind === 'case' ? t.errors.missingCase : t.errors.missingPage;
  useEffect(() => { document.title = title + ' — ' + SITE_NAME; }, [title]);
  return <div className="state state-error"><span className="state-symbol" aria-hidden="true">404</span><h1>{title}</h1><p>{kind === 'case' ? t.errors.missingCaseBody : t.errors.missingPageBody}</p><Link className="action action-dark" to={kind === 'case' ? returnTo : '/cases'}>{t.errors.browse}</Link><Link className="back-link" to="/">{t.errors.home}</Link></div>;
}

export function ErrorState({ onRetry, error, missingCase = false, returnTo }: { onRetry: () => void; error?: Error | null; missingCase?: boolean; returnTo?: string }) {
  const { t } = useI18n();
  const [params, setParams] = useSearchParams();
  function retry() {
    if (import.meta.env.DEV && params.get('mockError') === '1') {
      const next = new URLSearchParams(params);
      next.delete('mockError');
      setParams(next, { replace: true });
    }
    onRetry();
  }
  if (missingCase && error instanceof ApiError && error.status === 404) return <MissingState kind="case" returnTo={returnTo} />;
  return <div className="state state-error" role="alert"><span className="state-symbol" aria-hidden="true">!</span><h1>{t.common.errorTitle}</h1><p>{t.common.errorBody}</p><button className="action action-dark" type="button" onClick={retry}>{t.common.retry}</button></div>;
}

export function RouteErrorState() {
  const error = useRouteError();
  if (isRouteErrorResponse(error) && error.status === 404) return <MissingState />;
  return <ErrorState onRetry={() => window.location.reload()} />;
}
