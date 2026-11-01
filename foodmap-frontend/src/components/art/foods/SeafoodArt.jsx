import { CREAM, OUTLINE, TONE } from '../primitives';

const SHRIMP = 'M37 47c-9 1-16-5-16-13s6-14 14-14';

/**
 * hai-san — a curled shrimp with a crab claw, on a dish. The dish is
 * currentColor: seafood's tone is the brand cyan, so it is also the one
 * cyan accent.
 */
export default function SeafoodArt() {
  return (
    <>
      <ellipse cx="32" cy="50" rx="27" ry="7" fill="currentColor" />
      <path d={SHRIMP} stroke={OUTLINE} strokeWidth={10} />
      <path d={SHRIMP} stroke={TONE.chili} strokeWidth={5} />
      <path d="M34 16.5 42 13l-1 9z" fill={TONE.chili} />
      <path d="M23.5 27.5l4 2M21.5 35h4.5M24.5 42l3.5-2.5" stroke={CREAM} strokeWidth={2} />
      <path d="M40 46c-1-9 5-16 13-16 3 0 5.5 1.3 7 3.5l-7.5 3 7.5 3.5c-1.5 4.5-6 7-11 7-4 0-7.5-.5-9-1z" fill={TONE.crust} />
    </>
  );
}
