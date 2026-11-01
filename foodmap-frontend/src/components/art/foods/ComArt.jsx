import { CREAM, CYAN, TONE } from '../primitives';

/** com — a plate: rice dome, a grilled chop with grill marks, cucumber slices. Accent: plate edge. */
export default function ComArt() {
  return (
    <>
      <ellipse cx="32" cy="45" rx="28" ry="11" fill={CYAN} />
      <ellipse cx="32" cy="44" rx="22" ry="7.5" fill={CREAM} />
      {/* a tall packed rice mound (cơm tấm): keeps the outline unlike a flat crêpe */}
      <path d="M9 44c0-13 6-20.5 13.5-20.5S36 31 36 44z" fill={CREAM} />
      <path d="M16 35l2 1M23 29.5l2-1M28 37l2 1" strokeWidth={2} />
      <path d="M31 41c0-6 6-10 13-10s12 3 12 8-5 9-13 9c-7 0-12-3-12-7z" fill="currentColor" />
      <path d="M38 35l5 8M45 34l5 8" strokeWidth={2} />
      <circle cx="15" cy="47" r="3.2" fill={TONE.pandan} />
      <circle cx="21.5" cy="48.5" r="3.2" fill={TONE.pandan} />
    </>
  );
}
