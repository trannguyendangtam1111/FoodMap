const sizes = {
  sm: 'w-4 h-4 border-2',
  md: 'w-6 h-6 border-2',
  lg: 'w-10 h-10 border-4',
};

// `current` inherits the text colour — use inside buttons and pills.
const tones = {
  brand:   'border-brand-600',
  current: 'border-current',
};

export default function Spinner({ size = 'md', tone = 'brand', className = '' }) {
  return (
    <span
      className={`inline-block animate-spin rounded-full border-t-transparent ${tones[tone]} ${sizes[size]} ${className}`}
      role="status"
      aria-label="Đang tải"
      // Keeps spinning (slowed) under prefers-reduced-motion — see index.css
      data-motion="essential"
    />
  );
}
