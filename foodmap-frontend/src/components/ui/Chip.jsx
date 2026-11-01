// Chip = category or filter label. Category tones are identity colours only —
// they never mark a primary action. For status labels use Badge.
const tones = {
  neutral:     'bg-surface-sunken text-ink-muted border-line',
  brand:       'bg-brand-50 text-brand-800 border-brand-200',
  chili:       'bg-chili-tint text-chili-ink border-chili/40',
  turmeric:    'bg-turmeric-tint text-turmeric-ink border-turmeric/50',
  crust:       'bg-crust-tint text-crust-ink border-crust/40',
  taro:        'bg-taro-tint text-taro-ink border-taro/40',
  pandan:      'bg-pandan-tint text-pandan-ink border-pandan/40',
  dragonfruit: 'bg-dragonfruit-tint text-dragonfruit-ink border-dragonfruit/40',
};

const sizes = {
  sm: 'h-7 px-2.5 text-label gap-1',
  md: 'h-9 px-3.5 text-small font-semibold gap-1.5',
};

// `wrap`: long labels break onto more lines instead of overflowing.
const wrapSizes = {
  sm: 'min-h-7 py-1 px-2.5 text-label gap-1',
  md: 'min-h-9 py-1.5 px-3.5 text-small font-semibold gap-1.5',
};

/**
 * Renders a <button> when `onClick` is given (filter chip, with `selected`),
 * otherwise a static <span>. `wrap` lets a long label wrap (static chips).
 */
export default function Chip({
  children,
  tone = 'neutral',
  size = 'sm',
  icon = null,
  selected,
  wrap = false,
  onClick,
  className = '',
  ...props
}) {
  const base = wrap
    ? `inline-flex max-w-full items-center whitespace-normal break-words rounded-card border ${wrapSizes[size]}`
    : `inline-flex flex-shrink-0 items-center whitespace-nowrap rounded-full border ${sizes[size]}`;
  const content = (
    <>
      {icon && <span aria-hidden="true" className="flex-shrink-0 leading-none">{icon}</span>}
      {wrap ? <span className="min-w-0">{children}</span> : children}
    </>
  );

  if (!onClick) {
    return <span className={`${base} ${tones[tone]} ${className}`} {...props}>{content}</span>;
  }

  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected ?? undefined}
      className={`${base} transition-[background-color,border-color,transform] duration-fast ease-standard
                  active:scale-95
                  ${selected
                    ? 'border-ink bg-brand-400 text-ink'
                    : `${tones[tone]} hover:border-ink/40`}
                  ${className}`}
      {...props}
    >
      {content}
    </button>
  );
}
