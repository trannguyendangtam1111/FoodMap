import { BowlFoot, CREAM, CYAN, TONE } from '../primitives';

/**
 * bun — a deeper bowl, a round vermicelli nest and a tall herb pile;
 * no chopsticks. Accent: bowl band.
 */
export default function BunArt() {
  return (
    <>
      <path d="M27 21c-3.5-5-2.5-11 2-14.5 2.5 5.5 1.5 11-2 14.5z" fill={TONE.pandan} />
      <path d="M33 21c1-6 5.5-9.5 11-9.5-1 5.5-5 9-11 9.5z" fill={TONE.pandan} />
      <path d="M20.5 23.5c-1-4.5 1-8 5-9.5.5 4.5-1.5 8-5 9.5z" fill={TONE.pandan} />
      <path d="M15 31c0-6.5 7.5-10.5 17-10.5S49 24.5 49 31z" fill={CREAM} />
      <path d="M21 27.5c2-2 4 2 6 0s4 2 6 0 4 2 6 0" strokeWidth={2} />
      <BowlFoot y={53.2} />
      <path d="M12 31h40c0 14.5-9 23-20 23S12 45.5 12 31z" fill="currentColor" />
      <path d="M12.4 36h39.2l-1.5 4.8H13.9z" fill={CYAN} />
    </>
  );
}
