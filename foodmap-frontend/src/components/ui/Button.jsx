import Spinner from './Spinner';

// Pressable surfaces use "sticker depth": a 2px ink outline and a hard offset
// shadow that collapses on press. Ghost buttons stay flat on purpose.
// Disabled buttons get pointer-events-none, so hover/press never apply to them.
const variants = {
  primary: `
    border-ink bg-brand-400 text-ink shadow-sticker
    hover:bg-brand-500
    active:translate-y-[2px] active:shadow-sticker-pressed`,
  secondary: `
    border-line-strong/85 bg-surface text-ink shadow-sticker-soft
    hover:bg-surface-sunken
    active:translate-y-[2px] active:shadow-sticker-soft-pressed`,
  ghost: `
    border-transparent text-brand-700
    hover:bg-brand-50 hover:text-brand-800`,
  danger: `
    border-danger-ink bg-surface text-danger-ink shadow-sticker-soft
    hover:bg-danger-tint
    active:translate-y-[2px] active:shadow-sticker-soft-pressed`,
};

const sizes = {
  sm: 'h-9 px-3.5 text-small',
  md: 'h-11 px-5 text-body',
  lg: 'h-12 px-6 text-body-lg',
};

/** Class string for button-styled elements — also used for router <Link>s. */
export function buttonClass({ variant = 'primary', size = 'md', className = '' } = {}) {
  return `
    inline-flex select-none items-center justify-center gap-2 rounded-full border-2 font-semibold
    transition-[background-color,color,box-shadow,transform] duration-instant ease-standard
    disabled:pointer-events-none disabled:opacity-50
    ${variants[variant]} ${sizes[size]} ${className}`;
}

export default function Button({
  variant = 'primary',
  size = 'md',
  isLoading = false,
  disabled = false,
  className = '',
  children,
  ...props
}) {
  return (
    <button
      disabled={disabled || isLoading}
      aria-busy={isLoading || undefined}
      className={buttonClass({ variant, size, className })}
      {...props}
    >
      {isLoading && <Spinner size="sm" tone="current" />}
      {children}
    </button>
  );
}
