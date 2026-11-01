import { BowlFoot, CREAM, CYAN, TONE } from '../primitives';

/** chay — a leaf behind a bowl of tofu cubes. Accent: bowl band. */
export default function ChayArt() {
  return (
    <>
      <path d="M38 36c-1-13 7-23 20-26 2 12-6 23-20 26z" fill="currentColor" />
      <path d="M41 33c4-6 9-12 14-18" strokeWidth={2} />
      <rect x="17" y="25" width="11" height="11" rx="2" fill={CREAM} />
      <rect x="28" y="28" width="10" height="10" rx="2" fill={TONE.turmeric} />
      <BowlFoot y={52.5} fill={CREAM} />
      <path d="M10 36h44c0 10.5-9.5 17-22 17S10 46.5 10 36z" fill={CREAM} />
      <path d="M10.8 40.5h42.4l-1.7 4.2H12.5z" fill={CYAN} />
    </>
  );
}
