// Badge = small status label ("Đặc trưng", "Đang mở", "Bạn").
// For categories and filters use Chip instead.
const variants = {
  neutral: 'bg-surface-sunken text-ink-muted',
  brand:   'bg-brand-50 text-brand-800 ring-1 ring-inset ring-brand-200',
  success: 'bg-success-tint text-success-ink',
  warning: 'bg-warning-tint text-warning-ink',
  danger:  'bg-danger-tint text-danger-ink',
};

const dots = {
  neutral: 'bg-ink-subtle',
  brand:   'bg-brand-500',
  success: 'bg-success',
  warning: 'bg-warning',
  danger:  'bg-danger',
};

export default function Badge({ children, variant = 'neutral', dot = false, className = '' }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-label ${variants[variant]} ${className}`}
    >
      {dot && <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${dots[variant]}`} />}
      {children}
    </span>
  );
}
