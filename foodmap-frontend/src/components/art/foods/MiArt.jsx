import { BowlFoot, CYAN, TONE } from '../primitives';

/** mi — yellow wavy noodles lifted out of the bowl by chopsticks. Accent: bowl band. */
export default function MiArt() {
  return (
    <>
      <BowlFoot y={53.5} />
      <path d="M10 38h44c0 10-9.5 16-22 16S10 48 10 38z" fill="currentColor" />
      <path d="M10.8 42.5h42.4l-1.6 4H12.4z" fill={CYAN} />
      <ellipse cx="32" cy="38" rx="21" ry="4" fill={TONE.turmeric} />
      <path
        d="M26 14Q23 17 25 20Q28 23 27 26Q24 29 25 32Q27 35 26 38H34Q35 35 33 32Q32 29 35 26Q36 23 33 20Q31 17 34 14Z"
        fill={TONE.turmeric}
      />
      <path d="M30 15Q28 20 30 25Q32 30 30 37" strokeWidth={2} />
      <path d="M22 13 58 4M23 17l36-8.5" />
    </>
  );
}
