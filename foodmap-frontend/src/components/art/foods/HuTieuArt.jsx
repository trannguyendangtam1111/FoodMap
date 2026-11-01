import { BowlFoot, CREAM, CYAN, OUTLINE, TONE } from '../primitives';

const SHRIMP = 'M16.5 33c-2-7 3-13 9.5-12.5';

/**
 * hu-tieu — a clear broth with a curled shrimp and a halved quail egg
 * standing above the rim. Accent: bowl band.
 */
export default function HuTieuArt() {
  return (
    <>
      <BowlFoot y={51.5} />
      <path d="M9 34h46c0 11-10 18-23 18S9 45 9 34z" fill="currentColor" />
      <path d="M10 39h44l-1.9 4H11.9z" fill={CYAN} />
      <ellipse cx="32" cy="34" rx="23" ry="4.5" fill={CREAM} />
      {/* shrimp: an outlined thick curl (outline stroke under a tone stroke) */}
      <path d={SHRIMP} stroke={OUTLINE} strokeWidth={9} />
      <path d={SHRIMP} stroke={TONE.crust} strokeWidth={4} />
      <path d="M26 20.5l4.5-4 .8 6.3z" fill={TONE.crust} />
      <path d="M37 33a7.5 8 0 0 1 15 0z" fill={CREAM} />
      <circle cx="44.5" cy="29.5" r="3" fill={TONE.turmeric} />
    </>
  );
}
