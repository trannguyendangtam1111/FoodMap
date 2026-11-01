import { BowlFoot, CYAN, TONE } from '../primitives';

/**
 * bun-bo — a bowl with a chili-red broth surface, round beef slices and a
 * lemongrass stalk leaning out on the left. Accent: bowl band.
 */
export default function BunBoArt() {
  return (
    <>
      <path d="M10 33 20.5 8l4 1.7L14.5 35z" fill={TONE.pandan} />
      <BowlFoot y={51.5} />
      <path d="M8 32h48c0 12-10.5 20-24 20S8 44 8 32z" fill="currentColor" />
      <path d="M9 38h46l-2 4.2H11z" fill={CYAN} />
      <ellipse cx="32" cy="32" rx="24" ry="4.8" fill={TONE.chiliInk} />
      <circle cx="27" cy="31" r="4" fill={TONE.crust} />
      <circle cx="39" cy="31.5" r="4" fill={TONE.crust} />
    </>
  );
}
