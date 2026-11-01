import { CREAM, CYAN, OUTLINE, TONE } from '../primitives';

/** sushi — a nigiri and a maki with its seaweed band, on a board. Accent: board. */
export default function SushiArt() {
  return (
    <>
      <rect x="5" y="50" width="54" height="6" rx="3" fill={CYAN} />
      <path d="M7 50v-6c0-4 3-6.5 8-6.5h12c5 0 8 2.5 8 6.5v6z" fill={CREAM} />
      <path d="M5.5 40c1-6 6-9 13-9h6c7 0 12 3 12.5 9-4-1.5-9-2-15-2s-11 .5-16.5 2z" fill="currentColor" />
      <path d="M14 34.5l2.5 4M21 33l2 4.5M28 33.5l1.5 4.5" stroke={CREAM} strokeWidth={2} />
      <path d="M38 34v14c0 2 4 3.5 9.5 3.5S57 50 57 48V34z" fill={OUTLINE} />
      <ellipse cx="47.5" cy="34" rx="9.5" ry="4.5" fill={CREAM} />
      <ellipse cx="47.5" cy="34" rx="4" ry="2" fill={TONE.pandan} />
    </>
  );
}
