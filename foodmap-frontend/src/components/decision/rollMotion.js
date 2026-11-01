/**
 * Roll's reel motion — pure functions, no DOM, no randomness.
 *
 * Position `u` is measured in tiles: u = i means tile i of the reel's food
 * list sits centred in the frame (fractions are in between). The reel is a
 * loop, so tile i shows at every u ≡ i (mod n). Speeds are in tiles per ms.
 *
 * A motion is a list of segments with constant acceleration (velocity
 * changes linearly): speeding up, a steady run, and a constant deceleration
 * to rest. The landing is planned once the server's food is known: the reel
 * always stops with THAT tile centred — the target comes from the server,
 * this module only computes how to arrive there smoothly:
 *   - deterministic: the same inputs give the same motion;
 *   - never overshoots and never reverses; speed only decreases while landing,
 *     so it never slows on a neighbour and then creeps on (no near miss).
 */

/** The approved steps (Roll V1 specification §5.3), in ms. */
export const ROLL_STEPS = Object.freeze({
  first: Object.freeze({ press: 100, accel: 250, steady: 900, land: 700, settle: 120 }),
  again: Object.freeze({ press: 100, accel: 150, steady: 450, land: 500, settle: 120 }),
});

/** Steady speed (14 tiles/s) and the range a landing may adjust it within. */
export const CRUISE = 0.014;
export const CRUISE_MIN = 0.006;
export const CRUISE_MAX = 0.03;

const BLEND = 200;    // ms to move from one steady speed to another
const REST = 300;     // ms to come to rest between two tiles (nothing to land on)

export const mod = (a, n) => ((a % n) + n) % n;

function seg(t0, t1, u0, v0, v1) {
  return { t0, t1, u0, v0, v1, u1: t1 === Infinity ? Infinity : u0 + ((v0 + v1) / 2) * (t1 - t0) };
}

function at(s, t) {
  const dt = Math.min(Math.max(t, s.t0), s.t1) - s.t0;
  const a = s.t1 === Infinity || s.t1 === s.t0 ? 0 : (s.v1 - s.v0) / (s.t1 - s.t0);
  return { u: s.u0 + s.v0 * dt + 0.5 * a * dt * dt, v: s.v0 + a * dt };
}

/** Position and speed at time t. */
export function sample(motion, t) {
  const { segments } = motion;
  if (t <= segments[0].t0) return { u: segments[0].u0, v: segments[0].v0 };
  for (const s of segments) {
    if (t < s.t1) return at(s, t);
  }
  const last = segments[segments.length - 1];
  return { u: last.u1, v: last.v1 };
}

/** The segments up to time t, the last one cut at t. */
function until(motion, t) {
  const out = [];
  for (const s of motion.segments) {
    if (s.t0 >= t) break;
    if (s.t1 <= t) { out.push(s); continue; }
    out.push(seg(s.t0, t, s.u0, s.v0, at(s, t).v));
    break;
  }
  return out;
}

const nonEmpty = (s) => s.t1 > s.t0;

/** Start rolling from rest at `u`: speed up to CRUISE, then keep going until a landing is planned. */
export function startRoll({ u, now, kind = 'first' }) {
  const steps = ROLL_STEPS[kind] ?? ROLL_STEPS.first;
  const accel = seg(now, now + steps.accel, u, 0, CRUISE);
  const steady = seg(accel.t1, Infinity, accel.u1, CRUISE, CRUISE);
  return {
    kind,
    steps,
    start: now,
    accelEnd: accel.t1,
    decelFrom: accel.t1 + steps.steady,   // the earliest moment the landing may begin
    segments: [accel, steady],
    stopAt: null,
    finalU: null,
  };
}

/**
 * Land on tile `index` of a reel of `n` tiles. The deceleration never starts
 * before `decelFrom` (the approved shortest run) and always lasts the
 * approved `land` time, at constant deceleration.
 *
 * Answer before the landing may begin (the usual case): the steady speed is
 * adjusted once (within CRUISE_MIN–CRUISE_MAX, as close to CRUISE as
 * possible) so that the planned stop falls exactly on the tile. Otherwise
 * (a late answer): keep the steady speed just long enough, then stop.
 */
export function planLanding(motion, { now, index, n }) {
  const D = motion.steps.land;
  const t = Math.max(now, motion.start);
  const { u: ua, v: va } = sample(motion, t);

  if (t < motion.decelFrom) {
    const blendEnd = Math.min(motion.decelFrom, t < motion.accelEnd ? motion.accelEnd : t + BLEND);
    const tb = blendEnd - t;
    const tc = motion.decelFrom - blendEnd;
    const A = (va * tb) / 2;
    const B = tb / 2 + tc + D / 2;
    const base = mod(index - ua, n);
    let best = null;
    for (let m = 0; m < 1000; m += 1) {
      const vc = (base + m * n - A) / B;
      if (vc > CRUISE_MAX) break;
      if (vc >= CRUISE_MIN && (best == null || Math.abs(vc - CRUISE) < Math.abs(best - CRUISE))) best = vc;
    }
    if (best != null) {
      const b = seg(t, blendEnd, ua, va, best);
      const c = seg(blendEnd, motion.decelFrom, b.u1, best, best);
      const d = seg(motion.decelFrom, motion.decelFrom + D, c.u1, best, 0);
      return { ...motion, segments: [...until(motion, t), b, c, d].filter(nonEmpty), stopAt: d.t1, finalU: d.u1 };
    }
  }

  // Late: finish speeding up as started, keep the steady speed, then stop.
  const from = Math.max(t, motion.decelFrom, motion.accelEnd);
  const { u: uf, v } = sample(motion, from);
  const extra = mod(index - uf - (v * D) / 2, n) / v;
  const c = seg(from, from + extra, uf, v, v);
  const d = seg(c.t1, c.t1 + D, c.u1, v, 0);
  return { ...motion, segments: [...until(motion, from), c, d].filter(nonEmpty), stopAt: d.t1, finalU: d.u1 };
}

/**
 * Nothing to land on (no food, an error, a cancelled run): come to rest
 * between two tiles, so the frame never seems to hold a food.
 */
export function planRest(motion, { now }) {
  const t = Math.max(now, motion.start);
  const { u, v } = sample(motion, t);
  const head = until(motion, t);
  if (v <= 0) {
    return { ...motion, segments: head.length ? head : [seg(t, t, u, 0, 0)], stopAt: t, finalU: u };
  }
  // Two halves: (v → peak) then (peak → 0); distance (v + 2·peak)·REST/4, ending at a half tile.
  let S = mod(0.5 - u, 1);
  while (S < (v * REST) / 4) S += 1;
  const peak = ((4 * S) / REST - v) / 2;
  const a = seg(t, t + REST / 2, u, v, peak);
  const b = seg(a.t1, a.t1 + REST / 2, a.u1, peak, 0);
  return { ...motion, segments: [...head, a, b].filter(nonEmpty), stopAt: b.t1, finalU: b.u1 };
}
