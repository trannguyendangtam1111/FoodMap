import { BowlFoot, CREAM, CYAN, TONE } from '../primitives';

/**
 * pho — a wide, shallow bowl: flat noodles, beef slices, a herb sprig and
 * chopsticks resting across the rim. Accent: bowl band.
 */
export default function PhoArt() {
  return (
    <>
      <BowlFoot y={51.5} />
      <path d="M6 35h52c-1.5 10.5-12 17-26 17S7.5 45.5 6 35z" fill="currentColor" />
      <path d="M7 39.5h50l-1.9 4.3H8.9z" fill={CYAN} />
      <path d="M8.5 35c3-5.5 11.5-8 23.5-8s20.5 2.5 23.5 8z" fill={CREAM} />
      <path d="M14 33h9M27 31.5h7" />
      <ellipse cx="41" cy="31.5" rx="6" ry="2.6" fill={TONE.chili} />
      <path d="M19.5 30.5c.5-4 3.5-6.5 7.5-6.5-.5 4-3.5 6.5-7.5 6.5z" fill={TONE.pandan} />
      <path d="M4 25.5 60 17M4.5 30 60.5 21.5" />
    </>
  );
}
