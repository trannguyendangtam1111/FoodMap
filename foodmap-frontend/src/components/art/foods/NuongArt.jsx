import { CREAM, CYAN, TONE } from '../primitives';

/** nuong — a grill grate on legs with two skewers. Accent: grill handle. */
export default function NuongArt() {
  return (
    <>
      <path d="M18 49l-3.5 9M46 49l3.5 9" />
      <rect x="53" y="40" width="8" height="4.5" rx="2.25" fill={CYAN} />
      <path d="M9 37h46l-4 13H13z" fill="currentColor" />
      <rect x="7" y="32" width="50" height="5.5" rx="2.75" fill={CREAM} />
      <path d="M17 32v5.5M27 32v5.5M37 32v5.5M47 32v5.5" strokeWidth={2} />
      <path d="M8 27.5 54 19.3" />
      <rect x="17" y="20" width="24" height="7.5" rx="3.75" fill={TONE.crust} transform="rotate(-10 29 23.75)" />
      <path d="M14 17.4 58 9.6" />
      <rect x="25" y="10" width="22" height="7" rx="3.5" fill={TONE.chili} transform="rotate(-10 36 13.5)" />
    </>
  );
}
