/**
 * TiffinArt — the cyan cà mèn behind "Ăn gì hôm nay?".
 * Same drawing language as CategoryArt (ink outline, flat fills, cream
 * highlights), with the FoodMap map pin as its latch. Decorative.
 *
 * On its own it is a static illustration. Its parts are grouped and tagged
 * with `data-part` (shadow, box, steam, lid) so TiffinBox can animate them;
 * the groups do not change how it renders. `children` are drawn inside the
 * box, above the lid and behind the tiers — so whatever is placed there
 * appears to rise out of the box when the lid lifts.
 */
const C = (name) => `rgb(var(--fm-${name}))`;

export default function TiffinArt({ className = '', children = null }) {
  return (
    <svg
      viewBox="0 0 160 168"
      fill="none"
      stroke={C('ink')}
      strokeWidth={4}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className={className}
    >
      {/* ground shadow */}
      <ellipse data-part="shadow" cx="80" cy="158" rx="54" ry="7" fill={C('brand-200')} stroke="none" />

      <g data-part="box">
        {/* steam */}
        <g data-part="steam">
          <path d="M62 16c-4-5 4-9 0-14M80 13c-4-5 4-9 0-14M98 16c-4-5 4-9 0-14" strokeWidth={3.5} />
        </g>

        {/* carrier frame: side bars + handle */}
        <path d="M20 146V40c0-13 18-19 60-19s60 6 60 19v106" strokeWidth={6} />

        {/* lid + its highlight */}
        <g data-part="lid">
          <path d="M24 64c0-9 9-13 56-13s56 4 56 13v6H24z" fill={C('brand-500')} />
          <path d="M40 59c10-2 24-3 36-3" stroke={C('cream')} strokeWidth={3} />
        </g>

        {children}

        {/* three tiers */}
        <rect x="24" y="70" width="112" height="27" rx="6" fill={C('brand-400')} />
        <rect x="24" y="97" width="112" height="27" rx="6" fill={C('brand-400')} />
        <rect x="24" y="124" width="112" height="25" rx="8" fill={C('brand-400')} />

        {/* tier highlights */}
        <path d="M33 78v10M33 105v10M33 131v9" stroke={C('cream')} strokeWidth={4} />

        {/* latch — the FoodMap pin */}
        <path d="M80 130c-8-8-13-14-13-21a13 13 0 0 1 26 0c0 7-5 13-13 21z" fill={C('cream')} />
        <circle cx="80" cy="109" r="4.5" fill={C('ink')} stroke="none" />
      </g>
    </svg>
  );
}
