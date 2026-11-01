import { CREAM, CYAN } from '../primitives';

/** ca-phe — a Vietnamese phin on a short glass, drip visible. Accent: phin lid. */
export default function CoffeeArt() {
  return (
    <>
      <path d="M19 38h26l-2.6 18H21.6z" fill={CREAM} />
      <path d="M20.2 46.5h23.6l-1.4 9.5H21.6z" fill="currentColor" />
      <path d="M32 35.5v6.5" />
      <rect x="14" y="31" width="36" height="4.5" rx="2.25" fill={CREAM} />
      <path d="M20 17h24l-2.4 14H22.4z" fill="currentColor" />
      <rect x="21" y="11" width="22" height="6" rx="3" fill={CYAN} />
      <path d="M29 11V7.5h6V11" fill={CREAM} />
    </>
  );
}
