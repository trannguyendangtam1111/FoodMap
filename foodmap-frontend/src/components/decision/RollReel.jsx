import { useCallback, useEffect, useLayoutEffect, useRef } from 'react';
import FoodArt from '../art/FoodArt';
import { ROLL_STEPS, mod, planLanding, planRest, sample, startRoll } from './rollMotion';

/**
 * RollReel — the strip of food tiles behind a fixed cyan frame. DECORATIVE:
 * it never chooses anything. It rolls while a decision runs and stops with
 * the server's food (`target.key`) centred; with nothing to land on it comes
 * to rest between two tiles, so the frame never seems to hold a food.
 *
 * Props
 *   foods     the reel's foods, in order (each shown once per loop; none marked)
 *   run       { id, kind: 'first' | 'again', startAt } — a new id starts rolling at startAt (ms, Date.now clock)
 *   target    { key } for that run once the server has answered (null key: nothing to land on)
 *   restKey   the food to show centred while not rolling (the current result), or null
 *   animate   false → no motion at all (reduced motion, a single food)
 *   dimmed    after the reveal, the reel steps back
 *   onLanded  (runId) — the tile has settled; the result may be shown
 *
 * Motion: transform only, one requestAnimationFrame loop while rolling (the
 * run is open-ended and its landing is planned mid-flight, which a fixed
 * Web Animation can't express). The landing and settle are driven by timers
 * computed from the plan, so they never depend on frames being drawn.
 */
const MIN_HALF_SPAN = 5;   // tiles kept on each side of the frame (covers the widest window)
const now = () => Date.now();
const raf = (fn) => (typeof window.requestAnimationFrame === 'function' ? window.requestAnimationFrame(fn) : setTimeout(fn, 16));
const caf = (id) => (typeof window.cancelAnimationFrame === 'function' ? window.cancelAnimationFrame(id) : clearTimeout(id));

const TILE = 'h-[72px] w-[72px] min-[375px]:h-20 min-[375px]:w-20 min-[430px]:h-[88px] min-[430px]:w-[88px] md:h-24 md:w-24';
const FRAME = 'h-[82px] w-[82px] min-[375px]:h-[90px] min-[375px]:w-[90px] min-[430px]:h-[98px] min-[430px]:w-[98px] md:h-[106px] md:w-[106px]';
const WINDOW = 'h-[100px] min-[375px]:h-[108px] min-[430px]:h-[116px] md:h-[124px]';

export default function RollReel({ foods, run, target, restKey = null, animate = true, dimmed = false, onLanded, className = '' }) {
  const list = foods.length ? foods : [null, null, null, null, null];   // neutral plates until foods are known
  const n = list.length;
  const copies = 2 * Math.ceil(MIN_HALF_SPAN / n) + 1;
  const mid = Math.floor(copies / 2);

  const windowRef = useRef(null);
  const trackRef = useRef(null);
  const rootRef = useRef(null);
  const geometry = useRef({ windowW: 0, tileW: 0, pitch: 1 });
  const uRef = useRef(-0.5);
  const nRef = useRef(n);
  const motionRef = useRef(null);         // { runId, motion, planned }
  const rafRef = useRef(0);
  const timersRef = useRef(new Set());
  const popRef = useRef(null);
  const landedKeyRef = useRef(null);      // the food just landed on, until the next run
  const onLandedRef = useRef(onLanded);
  onLandedRef.current = onLanded;

  const later = (ms, fn) => {
    const id = setTimeout(() => { timersRef.current.delete(id); fn(); }, Math.max(0, ms));
    timersRef.current.add(id);
  };

  const setState = (state, centredKey) => {
    const root = rootRef.current;
    if (!root) return;
    root.dataset.reelState = state;
    if (centredKey === undefined) delete root.dataset.centredKey;
    else root.dataset.centredKey = centredKey ?? '';
  };

  const apply = useCallback((u) => {
    uRef.current = u;
    const track = trackRef.current;
    if (!track) return;
    const { windowW, tileW, pitch } = geometry.current;
    const x = mod(u, nRef.current) + nRef.current * mid;
    track.style.transform = `translate3d(${windowW / 2 - (x * pitch + tileW / 2)}px,0,0)`;
  }, [mid]);

  const measure = useCallback(() => {
    const track = trackRef.current;
    const first = track?.children[0];
    const second = track?.children[1];
    const tileW = first?.offsetWidth ?? 0;
    const pitch = second ? second.offsetLeft - first.offsetLeft : tileW + 8;
    geometry.current = { windowW: windowRef.current?.clientWidth ?? 0, tileW, pitch: pitch > 0 ? pitch : 1 };
    apply(uRef.current);
  }, [apply]);

  const stopLoop = () => {
    if (rafRef.current) caf(rafRef.current);
    rafRef.current = 0;
  };

  const loop = useCallback(() => {
    const m = motionRef.current?.motion;
    if (!m) return;
    const t = now();
    apply(sample(m, t).u);
    if (m.stopAt == null || t < m.stopAt) rafRef.current = raf(loop);
    else rafRef.current = 0;
  }, [apply]);

  // Geometry: on mount, when the tiles change, and on resize.
  useLayoutEffect(() => {
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [measure, n]);

  // Unmount: stop everything; no callback may fire afterwards.
  useEffect(() => {
    const timers = timersRef.current;
    return () => {
      motionRef.current = null;
      stopLoop();
      timers.forEach(clearTimeout);
      timers.clear();
      popRef.current?.cancel?.();
    };
  }, []);

  // The list changed under a rolling reel (the server's food appended): keep
  // the same tile in the frame — re-base u to this loop before the next frame.
  useLayoutEffect(() => {
    const prevN = nRef.current;
    nRef.current = n;
    const active = motionRef.current;
    if (!active || prevN === n) return;
    const shift = Math.floor(uRef.current / prevN) * prevN;
    if (shift !== 0) {
      active.motion = {
        ...active.motion,
        segments: active.motion.segments.map((s) => ({ ...s, u0: s.u0 - shift, u1: s.u1 - shift })),
        finalU: active.motion.finalU == null ? null : active.motion.finalU - shift,
      };
    }
    apply(sample(active.motion, now()).u);
  }, [n, apply]);

  // At rest (not rolling): the current result centred (or the food just landed
  // on, until the page shows it), or between two tiles.
  useLayoutEffect(() => {
    if (motionRef.current) return;
    const key = restKey ?? landedKeyRef.current;
    const index = key ? list.findIndex((f) => f?.key === key) : -1;
    apply(index >= 0 ? index : -0.5);
    setState(!animate ? 'static' : index >= 0 ? 'landed' : 'idle', index >= 0 ? key : null);
  });

  // A new run: start rolling from wherever the reel stands. A layout effect,
  // declared before the landing one: an answer that came during the press is
  // planned in the same commit.
  useLayoutEffect(() => {
    if (!run || !animate) return;
    stopLoop();
    popRef.current?.cancel?.();
    timersRef.current.forEach(clearTimeout);
    timersRef.current.clear();
    landedKeyRef.current = null;
    const motion = startRoll({ u: mod(uRef.current, n), now: run.startAt ?? now(), kind: run.kind });
    motionRef.current = { runId: run.id, motion, planned: false };
    setState('rolling');
    rafRef.current = raf(loop);
  }, [run?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // The server answered for this run: land on its food, or come to rest.
  useLayoutEffect(() => {
    const active = motionRef.current;
    if (!active || active.planned || !run || active.runId !== run.id || !target) return;
    const runId = active.runId;
    const index = target.key ? list.findIndex((f) => f?.key === target.key) : -1;
    const landing = index >= 0;
    active.motion = landing
      ? planLanding(active.motion, { now: now(), index, n })
      : planRest(active.motion, { now: now() });
    active.planned = true;
    if (landing) {
      const landStart = active.motion.segments[active.motion.segments.length - 1].t0;
      later(landStart - now(), () => { if (motionRef.current?.runId === runId) setState('landing'); });
    } else {
      setState('stopping');
    }
    if (!rafRef.current) rafRef.current = raf(loop);

    const settle = landing ? ROLL_STEPS[run.kind]?.settle ?? 120 : 0;
    later(active.motion.stopAt - now(), () => {
      if (motionRef.current?.runId !== runId) return;
      stopLoop();
      const finalU = landing ? index : active.motion.finalU;   // exact: no drift, no tile in between
      apply(finalU);
      landedKeyRef.current = landing ? target.key : null;
      setState(landing ? 'landed' : 'rest', landing ? target.key : null);
      if (landing) {
        const tile = trackRef.current?.children[mid * n + index];
        popRef.current = tile?.animate?.(
          [{ transform: 'scale(1)' }, { transform: 'scale(1.06)' }, { transform: 'scale(1)' }],
          { duration: settle, easing: 'ease-out' },
        ) ?? null;
      }
      later(settle, () => {
        if (motionRef.current?.runId !== runId) return;
        motionRef.current = null;
        if (landing) onLandedRef.current?.(runId);
      });
    });
  });

  return (
    <div
      ref={rootRef}
      aria-hidden="true"
      data-roll-strip=""
      data-foods={foods.map((f) => f.key).join(' ')}
      className={`relative mx-auto w-full max-w-md ${className}`}
    >
      <div
        ref={windowRef}
        className={`relative overflow-hidden rounded-tile border border-brand-200 bg-surface/80 ${WINDOW}
                    [-webkit-mask-image:linear-gradient(to_right,transparent,black_16%,black_84%,transparent)]
                    [mask-image:linear-gradient(to_right,transparent,black_16%,black_84%,transparent)]`}
      >
        <div
          ref={trackRef}
          className={`absolute inset-y-0 left-0 flex items-center gap-2 will-change-transform
                      transition-opacity duration-fast ${dimmed ? 'opacity-60' : ''}`}
        >
          {Array.from({ length: copies }, (_, c) => list.map((food, i) => (
            <FoodArt
              // eslint-disable-next-line react/no-array-index-key
              key={`${c}-${i}-${food?.key ?? 'plate'}`}
              artKey={food?.key}
              parentCategory={food?.parentCategory}
              tone={food?.tone}
              className={`flex-shrink-0 rounded-tile border-2 border-ink/10 ${TILE}`}
            />
          )))}
        </div>
      </div>
      {/* The landing frame: fixed, centred, the same for every food. */}
      <div
        className={`pointer-events-none absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-[18px]
                    border-[3px] border-brand-500 shadow-sticker-soft ${FRAME}`}
      />
    </div>
  );
}
