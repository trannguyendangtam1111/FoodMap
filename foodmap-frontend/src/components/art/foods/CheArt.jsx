import { CREAM, CYAN, TONE } from '../primitives';

/**
 * che — a clear footed glass with layered toppings (beans, pandan jelly,
 * coconut cream) and a long spoon. Accent: glass rim.
 */
export default function CheArt() {
  return (
    <>
      <path d="M36 25 47 4.5l3.5 1.8L40 26.5z" fill={CREAM} />
      <path d="M18.3 37h27.4l-1.7 9H20z" fill="currentColor" />
      <path d="M16.7 28h30.6l-1.6 9H18.3z" fill={TONE.pandan} />
      <path d="M15.4 21h33.2l-1.3 7H16.7z" fill={CREAM} />
      <path d="M15 19h34l-5 27H20z" />
      <rect x="13" y="16.5" width="38" height="4.5" rx="2.25" fill={CYAN} />
      <path d="M29 46h6v5h-6z" fill={CREAM} />
      <rect x="21" y="51" width="22" height="4.5" rx="2.25" fill={CREAM} />
    </>
  );
}
