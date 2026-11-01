import TiffinArt from '../home/TiffinArt';
import FoodGlyph from '../art/FoodGlyph';
import './tiffin.css';

const C = (name) => `rgb(var(--fm-${name}))`;

/**
 * TiffinBox — the animated cà mèn. Pure presentation: it shows the pose
 * for `phase` (a useTiffinDecision PHASE) and nothing else. Motion is CSS
 * only (tiffin.css), so there are no JS loops or timers to clean up.
 *
 * What rises out when the lid opens: the decided `food` as its FoodGlyph
 * (the same drawing as FoodArt), or — before any food is known — the
 * FoodMap pin. Either sits in the same `pin` part, so the motion is the same.
 *
 * Decorative (aria-hidden). With `interactive`, a click on the box runs
 * `onActivate` as a pointer shortcut — the accessible control is always the
 * real button next to it.
 */
export default function TiffinBox({ phase, food = null, interactive = false, onActivate, className = '', artClassName = '' }) {
  const clickable = interactive && phase === 'idle';
  return (
    <div
      aria-hidden="true"
      data-phase={phase}
      data-interactive={clickable || undefined}
      onClick={clickable ? onActivate : undefined}
      className={`fm-tiffin select-none ${clickable ? 'cursor-pointer' : ''} ${className}`}
    >
      <TiffinArt className={artClassName}>
        <g data-part="pin">
          {food ? (
            <FoodGlyph artKey={food.artKey ?? food.key} tone={food.tone} x={56} y={20} size={48} />
          ) : (
            <>
              <path d="M80 66c-10-10-16-17-16-26a16 16 0 0 1 32 0c0 9-6 16-16 26z" fill={C('brand-400')} />
              <circle cx="80" cy="40" r="6" fill={C('cream')} strokeWidth={3} />
            </>
          )}
        </g>
      </TiffinArt>
    </div>
  );
}
