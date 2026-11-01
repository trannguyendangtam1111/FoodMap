import { useEffect, useState } from 'react';

/**
 * Seconds left before a 429 may be retried, counted down from the server's
 * Retry-After (DecisionApiError.retryAfterSeconds); 0 when there is nothing to
 * wait for. Display only — it never retries anything by itself.
 */
export default function useRetryWait(error) {
  const seconds = error?.status === 429 && Number.isFinite(error.retryAfterSeconds) ? error.retryAfterSeconds : 0;
  const [left, setLeft] = useState(seconds);
  useEffect(() => {
    setLeft(seconds);
    if (seconds <= 0) return undefined;
    const until = Date.now() + seconds * 1000;
    const timer = setInterval(() => {
      const s = Math.max(0, Math.ceil((until - Date.now()) / 1000));
      setLeft(s);
      if (s === 0) clearInterval(timer);
    }, 1000);
    return () => clearInterval(timer);
  }, [error, seconds]);
  return left;
}
