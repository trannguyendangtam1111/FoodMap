import { CREAM, CYAN } from '../primitives';

/** lau — a two-handled pot on a burner, bubbles rising. Accent: burner. */
export default function LauArt() {
  return (
    <>
      <circle cx="23" cy="18" r="2.6" fill={CREAM} />
      <circle cx="31.5" cy="13.5" r="3" fill={CREAM} />
      <circle cx="40" cy="18.5" r="2.4" fill={CREAM} />
      <rect x="16" y="50" width="32" height="6.5" rx="2.5" fill={CYAN} />
      <path d="M10 33H5.5v6.5H10M54 33h4.5v6.5H54" />
      <path d="M10 27h44v14c0 5.5-4 9-9.5 9h-25C14 50 10 46.5 10 41z" fill="currentColor" />
      <rect x="7.5" y="24.5" width="49" height="5.5" rx="2.75" fill={CREAM} />
    </>
  );
}
