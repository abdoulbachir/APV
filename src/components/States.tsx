import { useI18n } from '../i18n/I18nContext';

export function LoadingState() {
  const { t } = useI18n();
  return <div className="state state-loading" role="status"><span className="loading-path" aria-hidden="true"><i /><i /><i /></span><p>{t.common.loading}</p></div>;
}

export function ErrorState({ onRetry }: { onRetry: () => void }) {
  const { t } = useI18n();
  return <div className="state state-error" role="alert"><span className="state-symbol" aria-hidden="true">!</span><h1>{t.common.errorTitle}</h1><p>{t.common.errorBody}</p><button className="action action-dark" type="button" onClick={onRetry}>{t.common.retry}</button></div>;
}
