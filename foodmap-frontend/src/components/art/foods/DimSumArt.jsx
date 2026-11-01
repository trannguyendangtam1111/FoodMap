import { CREAM, TONE } from '../primitives';

/**
 * dimsum — a bamboo steamer with three buns. The steamer band is
 * currentColor: dimsum's tone is the brand cyan, so it is also the one cyan
 * accent.
 */
export default function DimSumArt() {
  return (
    <>
      <path d="M11 33c0-6.5 4-10.5 9-10.5s9 4 9 10.5z" fill={CREAM} />
      <path d="M35 33c0-6.5 4-10.5 9-10.5s9 4 9 10.5z" fill={CREAM} />
      <path d="M22 33c0-8 4.5-13 10-13s10 5 10 13z" fill={CREAM} />
      <path d="M30 22.5c1 1.5 3 1.5 4 0M18 24.5c1 1.2 2.6 1.2 3.6 0M42 24.5c1 1.2 2.6 1.2 3.6 0" strokeWidth={2} />
      <path d="M7 33h50v13c0 5-3.5 8-8 8H15c-4.5 0-8-3-8-8z" fill={TONE.crust} />
      <rect x="7" y="38" width="50" height="5.5" fill="currentColor" />
      <path d="M19 43.5v10M32 43.5v10.5M45 43.5v10" strokeWidth={2} />
      <rect x="5" y="31" width="54" height="4.5" rx="2.25" fill={TONE.crust} />
    </>
  );
}
