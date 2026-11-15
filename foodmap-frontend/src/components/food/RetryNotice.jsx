import Button from '../ui/Button';
import useRetryWait from '../../hooks/useRetryWait';
import { t, useI18n } from '../../i18n';

/**
 * The second line of a load error: a timeout, a 429 (counting down the
 * server's Retry-After when it is readable) or anything else. Never the
 * server's raw message.
 */
export function retryDetail(error, wait) {
  if (error?.timeout) return t('retry.timeout');
  if (error?.status === 429) {
    if (!Number.isFinite(error.retryAfterSeconds) || error.retryAfterSeconds <= 0) {
      return t('retry.tooFastMoment');
    }
    return wait > 0 ? t('retry.tooFastSeconds', { count: wait }) : t('decision.canRetry');
  }
  return t('themgi.err.server');
}

/** A friendly load error with "Thử lại" (held back while a 429 wait runs). */
export default function RetryNotice({ title, error, onRetry, className = '' }) {
  useI18n();
  const wait = useRetryWait(error);
  return (
    <div className={`flex flex-col items-start gap-3 rounded-card border border-line bg-surface p-4
                     sm:flex-row sm:items-center sm:justify-between ${className}`}>
      <div className="min-w-0">
        <p className="text-body font-semibold text-ink">{title}</p>
        <p className="mt-0.5 text-small text-ink-muted">{retryDetail(error, wait)}</p>
      </div>
      <Button variant="secondary" onClick={onRetry} disabled={wait > 0}>{t('common.retry')}</Button>
    </div>
  );
}
