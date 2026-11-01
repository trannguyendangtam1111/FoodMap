// Loading placeholder block. Size and radius come from className,
// e.g. <Skeleton className="h-4 w-24" /> or <Skeleton className="h-9 w-9 rounded-full" />.
export default function Skeleton({ className = '' }) {
  // Only apply the default radius when the caller didn't pick one — the
  // extended `rounded-tag` utility is emitted after `rounded-full` and would win.
  const radius = /\brounded-/.test(className) ? '' : 'rounded-tag';
  return (
    <span
      aria-hidden="true"
      className={`block animate-pulse bg-surface-sunken ${radius} ${className}`}
    />
  );
}
