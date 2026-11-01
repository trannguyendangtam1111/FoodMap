import { CYAN, TONE } from '../primitives';

/** banh-xeo — a half-moon folded yellow crêpe with herbs, on a plate. Accent: plate. */
export default function BanhXeoArt() {
  return (
    <>
      <ellipse cx="32" cy="47" rx="28" ry="6" fill={CYAN} />
      <path d="M7 44c-3-7 1-14 8-15.5 0 7-3 12.5-8 15.5z" fill={TONE.pandan} />
      <path d="M8 46a24 21 0 0 1 48 0z" fill="currentColor" />
      <circle cx="22" cy="37" r="2" fill={TONE.crust} />
      <circle cx="31" cy="31" r="2" fill={TONE.crust} />
      <circle cx="41" cy="36" r="2" fill={TONE.crust} />
      <path d="M24 46c1 2.5 3 3 5 2.5M35 46c1 2.5 3 3 5 2.5" strokeWidth={2} />
    </>
  );
}
