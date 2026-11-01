import { CREAM, CYAN, OUTLINE } from '../primitives';

/** tra-sua — a tall cup with a dome lid, a wide straw and pearls at the bottom. Accent: cup sleeve. */
export default function MilkTeaArt() {
  return (
    <>
      <path d="M33 17 38 3h4.5l-5 14" fill={CREAM} />
      <path d="M19 27h26l-3 30H22z" fill="currentColor" />
      <path d="M20.2 39h23.6l-.85 8.5H21.05z" fill={CYAN} />
      <circle cx="26.5" cy="52" r="2.2" fill={OUTLINE} />
      <circle cx="32" cy="53" r="2.2" fill={OUTLINE} />
      <circle cx="37.5" cy="52" r="2.2" fill={OUTLINE} />
      <path d="M17 27a15 11 0 0 1 30 0z" fill={CREAM} />
      <rect x="16" y="25.5" width="32" height="4" rx="2" fill={CREAM} />
    </>
  );
}
