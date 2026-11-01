import { CYAN, TONE } from '../primitives';

/**
 * pizza — a slice with pepperoni and basil, tilted and resting on a board
 * (upright, a slice over a small board read as a stemmed glass). Accent: board.
 */
export default function PizzaArt() {
  return (
    <>
      <ellipse cx="32" cy="53" rx="25" ry="5" fill={CYAN} />
      <g transform="rotate(-38 32 32)">
        <path d="M12 15h40L32 55z" fill={TONE.turmeric} />
        <rect x="8" y="9" width="48" height="8" rx="4" fill="currentColor" />
        <circle cx="25.5" cy="24.5" r="3.6" fill={TONE.chili} />
        <circle cx="37.5" cy="24" r="3.6" fill={TONE.chili} />
        <circle cx="31" cy="35.5" r="3.4" fill={TONE.chili} />
        <path d="M29 45c1.5-2.5 4.5-3.5 7-2.5-1.5 2.5-4 3.5-7 2.5z" fill={TONE.pandan} />
      </g>
    </>
  );
}
