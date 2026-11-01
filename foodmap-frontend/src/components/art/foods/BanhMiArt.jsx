import { CREAM, CYAN, TONE } from '../primitives';

/**
 * banh-mi — a split baguette in side view with a layered filling and herbs
 * sticking out. Accent: paper wrap.
 */
export default function BanhMiArt() {
  return (
    <>
      <path d="M9 33c-3.5-2.5-5-6.5-3.5-10.5 3.5 1 6 4.5 5.5 9z" fill={TONE.pandan} />
      <path d="M51 31.5c1.5-4 5-6.5 9-6.5-.5 4-3.5 7-8 7.5z" fill={TONE.pandan} />
      <path d="M7 41h50c0 6-4 9.5-10 9.5H17C11 50.5 7 47 7 41z" fill="currentColor" />
      <path d="M7 41h12v9.3h-2C11 50.3 7 46.8 7 41z" fill={CYAN} />
      <rect x="8" y="35.5" width="48" height="5.5" rx="2.75" fill={TONE.chili} />
      <path d="M9 35.5c1-9 10-14.5 23-14.5s22 5.5 23 14.5z" fill="currentColor" />
      <path d="M20 28l4-4M30 26l4-4M40 27l4-4" stroke={CREAM} />
    </>
  );
}
