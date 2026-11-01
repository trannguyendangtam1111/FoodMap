import { Link } from 'react-router-dom';
import Icon from '../ui/Icon';
import { buttonClass } from '../ui/Button';

/**
 * Static teaser for the map page. Purely illustrative — it does NOT load
 * Goong, mount MapView or request any restaurant data. The pins are
 * decoration in the same style as the real map pins, not real places.
 */
export default function MapTeaser() {
  return (
    <section aria-labelledby="map-teaser-title" className="mx-auto max-w-6xl px-4 py-8 lg:py-10">
      <div className="grid overflow-hidden rounded-panel border border-line bg-surface lg:grid-cols-2">
        <div className="flex flex-col items-start justify-center gap-4 p-6 sm:p-10">
          <h2 id="map-teaser-title" className="font-display text-h2 text-ink">Khám phá trên bản đồ</h2>
          <p className="max-w-md text-body text-ink-muted">
            Kéo, phóng to và xem quán quanh khu bạn ở — từ quán bún đầu hẻm đến quán cà phê quen.
          </p>
          <Link to="/map" className={buttonClass({ className: 'mt-1' })}>
            <Icon name="map" size={18} strokeWidth={2} />
            Khám phá bản đồ
          </Link>
        </div>

        <div className="relative min-h-[13rem] border-t border-line bg-brand-50 lg:min-h-[16rem] lg:border-l lg:border-t-0">
          <MapIllustration className="absolute inset-0 h-full w-full" />
        </div>
      </div>
    </section>
  );
}

const C = (name) => `rgb(var(--fm-${name}))`;

// Decorative pins: [x, y, selected?] — kept inside y 70–190, the band that
// survives the `slice` crop at wide aspect ratios.
const PINS = [[96, 82], [212, 72], [300, 120], [160, 150], [262, 178], [58, 170], [342, 84, true]];

function MapIllustration({ className = '' }) {
  return (
    <svg viewBox="0 0 400 260" preserveAspectRatio="xMidYMid slice" aria-hidden="true" className={className}>
      <rect width="400" height="260" fill={C('cream')} />
      {/* blocks */}
      <g fill={C('surface-sunken')}>
        <rect x="18" y="18" width="110" height="70" rx="10" />
        <rect x="150" y="18" width="90" height="70" rx="10" />
        <rect x="262" y="18" width="120" height="70" rx="10" />
        <rect x="18" y="110" width="80" height="62" rx="10" />
        <rect x="282" y="140" width="100" height="40" rx="10" />
        <rect x="18" y="200" width="150" height="50" rx="10" />
      </g>
      {/* park */}
      <rect x="120" y="110" width="90" height="62" rx="12" fill={C('pandan-tint')} stroke={C('pandan')} strokeOpacity="0.5" />
      {/* river */}
      <path d="M190 260c10-40 60-52 110-60s90-30 110-60" fill="none" stroke={C('brand-200')} strokeWidth="22" strokeLinecap="round" />
      {/* streets */}
      <g stroke={C('surface')} strokeWidth="10" strokeLinecap="round" fill="none">
        <path d="M0 99h400M0 189h260M139 0v260M251 0v190" />
      </g>
      {/* pins */}
      {PINS.map(([x, y, selected]) => (
        <g key={`${x}-${y}`} transform={`translate(${x} ${y})`}>
          <circle r="15" fill={selected ? C('brand-700') : C('brand-400')} stroke={C('ink')} strokeWidth="2.5" />
          <g transform="translate(-9 -9) scale(0.75)" fill="none" stroke={selected ? '#fff' : C('ink')}
             strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
            <path d="M7 3v8M4.5 3v5a2.5 2.5 0 0 0 5 0V3M7 11v10" />
            <path d="M17 21V3c-2 1.5-3 4-3 7v4h3" />
          </g>
        </g>
      ))}
    </svg>
  );
}
