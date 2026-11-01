import { BowlFoot, CREAM, CYAN, TONE } from '../primitives';

/**
 * chao — a deep bowl with a smooth porridge surface, scallion dots, a
 * ceramic spoon and a little steam. Accent: bowl band.
 */
export default function ChaoArt() {
  return (
    <>
      <path d="M40 31 52 9l3.5 1.8L44 33z" fill={CREAM} />
      <path d="M20 22c-2-3 2-5 0-8M27 20c-2-3 2-5 0-8" strokeWidth={2} />
      <BowlFoot y={52.2} />
      <path d="M12 30h40c0 14.5-9 23-20 23S12 44.5 12 30z" fill="currentColor" />
      <path d="M12.6 35h38.8l-1.5 4.6H14.1z" fill={CYAN} />
      <ellipse cx="32" cy="30" rx="18.5" ry="4.2" fill={CREAM} />
      <circle cx="24" cy="30" r="1.6" fill={TONE.pandan} />
      <circle cx="30" cy="31.5" r="1.6" fill={TONE.pandan} />
      <ellipse cx="41" cy="30.5" rx="5" ry="2.6" fill={CREAM} />
    </>
  );
}
