import { CYAN, TONE } from '../primitives';

/** kem — a waffle cone with a dripping scoop and a cherry. Accent: cone sleeve. */
export default function IceCreamArt() {
  return (
    <>
      <path d="M21 33h22L32 60z" fill={TONE.crust} />
      <path d="M25 40.5l11 9M39 40.5l-11 9" strokeWidth={2} />
      <path d="M21.5 34h21l-2.4 5.8H23.9z" fill={CYAN} />
      <path
        d="M17 33A15 14 0 0 1 47 33C45 35 43.5 33.5 41.5 35.5S37 34 35 36S30 34.5 28 36S24 34 22 35.5S18.5 34.5 17 33Z"
        fill="currentColor"
      />
      <path d="M32 13c0-3 2-5 4-5.5" />
      <circle cx="32" cy="16" r="3" fill={TONE.chili} />
    </>
  );
}
