import { Link } from 'react-router-dom';
import { useI18n } from '../../i18n';

/**
 * FoodMap mark + wordmark, always linking home.
 * `compact` shows only the mark below the lg breakpoint, leaving room for
 * search in the mobile/tablet header.
 */
export default function Logo({ compact = false, className = '' }) {
  const { t } = useI18n();
  return (
    <Link
      to="/"
      aria-label={t('layout.logoHome')}
      className={`inline-flex min-h-11 min-w-11 flex-shrink-0 items-center justify-center gap-1.5 rounded-tag
                  font-display font-black text-brand-600 ${className}`}
    >
      <img src="/favicon.svg" alt="" width="28" height="28" className="h-7 w-7" />
      <span className={compact ? 'hidden lg:inline' : ''}>FoodMap</span>
    </Link>
  );
}
