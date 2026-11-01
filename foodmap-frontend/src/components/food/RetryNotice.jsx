import Button from '../ui/Button';
import useRetryWait from '../../hooks/useRetryWait';

/**
 * The second line of a load error: a timeout, a 429 (counting down the
 * server's Retry-After when it is readable) or anything else. Never the
 * server's raw message.
 */
export function retryDetail(error, wait) {
  if (error?.timeout) return 'FoodMap phản hồi hơi lâu, bạn thử lại nhé.';
  if (error?.status === 429) {
    if (!Number.isFinite(error.retryAfterSeconds) || error.retryAfterSeconds <= 0) {
      return 'Bạn bấm hơi nhanh, đợi một chút rồi thử lại nhé.';
    }
    return wait > 0 ? `Bạn bấm hơi nhanh, đợi ${wait} giây rồi thử lại nhé.` : 'Giờ bạn thử lại được rồi.';
  }
  return 'FoodMap đang gặp chút trục trặc, bạn thử lại nhé.';
}

/** A friendly load error with "Thử lại" (held back while a 429 wait runs). */
export default function RetryNotice({ title, error, onRetry, className = '' }) {
  const wait = useRetryWait(error);
  return (
    <div className={`flex flex-col items-start gap-3 rounded-card border border-line bg-surface p-4
                     sm:flex-row sm:items-center sm:justify-between ${className}`}>
      <div className="min-w-0">
        <p className="text-body font-semibold text-ink">{title}</p>
        <p className="mt-0.5 text-small text-ink-muted">{retryDetail(error, wait)}</p>
      </div>
      <Button variant="secondary" onClick={onRetry} disabled={wait > 0}>Thử lại</Button>
    </div>
  );
}
