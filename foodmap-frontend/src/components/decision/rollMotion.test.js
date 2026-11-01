import { describe, expect, it } from 'vitest';
import { CRUISE, CRUISE_MAX, CRUISE_MIN, ROLL_STEPS, mod, planLanding, planRest, sample, startRoll } from './rollMotion';

const T0 = 1_000_000;
/** Distance of u from tile `index` on a loop of n (0 = centred). */
const off = (u, index, n) => {
  const d = mod(u - index, n);
  return Math.min(d, n - d);
};

/** Sample a planned motion every ms from `from` to its stop. */
function trace(motion, from) {
  const out = [];
  for (let t = from; t <= motion.stopAt + 5; t += 1) out.push({ t, ...sample(motion, t) });
  return out;
}

describe('startRoll', () => {
  it('speeds up over the approved time, then holds the steady speed', () => {
    const m = startRoll({ u: 0, now: T0, kind: 'first' });
    expect(sample(m, T0).v).toBe(0);
    expect(sample(m, T0 + 125).v).toBeCloseTo(CRUISE / 2, 10);
    expect(sample(m, T0 + 250).v).toBeCloseTo(CRUISE, 10);
    expect(sample(m, T0 + 5000).v).toBeCloseTo(CRUISE, 10);     // keeps rolling while waiting
    expect(m.decelFrom).toBe(T0 + 250 + 900);
    expect(startRoll({ u: 0, now: T0, kind: 'again' }).decelFrom).toBe(T0 + 150 + 450);
  });
});

describe('planLanding — the server\'s tile, centred, every time', () => {
  const cases = [];
  for (const n of [1, 2, 3, 5, 8, 14, 16, 20]) {
    for (const kind of ['first', 'again']) {
      for (const answerAfter of [0, 40, 120, 300, 700, 1100, 1300, 2500, 4900]) {
        for (const start of [-0.5, 0, 3.25]) cases.push({ n, kind, answerAfter, start });
      }
    }
  }

  it.each([[cases.length]])('%i combinations: lands exactly on the target, never early, never overshooting', () => {
    for (const { n, kind, answerAfter, start } of cases) {
      for (let index = 0; index < n; index += 1) {
        const rolling = startRoll({ u: start, now: T0, kind });
        const plan = planLanding(rolling, { now: T0 + answerAfter, index, n });
        const what = JSON.stringify({ n, kind, answerAfter, start, index });

        expect(off(plan.finalU, index, n), what).toBeLessThan(1e-9);                 // the server's tile
        expect(plan.stopAt, what).toBeGreaterThanOrEqual(rolling.decelFrom + ROLL_STEPS[kind].land - 1e-9);
        const last = plan.segments[plan.segments.length - 1];
        expect(last.v1, what).toBe(0);
        expect(last.t1 - last.t0, what).toBeCloseTo(ROLL_STEPS[kind].land, 9);       // the approved landing time
        expect(last.v0, what).toBeGreaterThanOrEqual(CRUISE_MIN - 1e-12);
        expect(last.v0, what).toBeLessThanOrEqual(CRUISE_MAX + 1e-12);
        // the past is not rewritten: same position as the unplanned roll up to the answer
        const tA = T0 + answerAfter;
        expect(sample(plan, tA).u, what).toBeCloseTo(sample(rolling, tA).u, 9);
      }
    }
  });

  it('no overshoot, no reversal, and while landing the speed only decreases (no near miss)', () => {
    for (const n of [3, 14, 20]) {
      for (const answerAfter of [50, 1000, 3000]) {
        for (let index = 0; index < n; index += 1) {
          const plan = planLanding(startRoll({ u: -0.5, now: T0, kind: 'first' }), { now: T0 + answerAfter, index, n });
          const samples = trace(plan, T0);
          const land = plan.segments[plan.segments.length - 1];
          for (let i = 1; i < samples.length; i += 1) {
            expect(samples[i].u).toBeGreaterThanOrEqual(samples[i - 1].u - 1e-9);    // never backwards
            expect(samples[i].u).toBeLessThanOrEqual(plan.finalU + 1e-9);            // never past the stop
            if (samples[i].t > land.t0) expect(samples[i].v).toBeLessThanOrEqual(samples[i - 1].v + 1e-12);
          }
          // the stop is the only moment speed reaches 0 after starting
          expect(samples.filter((s) => s.t > T0 && s.t < plan.stopAt && s.v <= 0)).toHaveLength(0);
        }
      }
    }
  });

  it('an early answer waits for the shortest run: landing starts exactly at decelFrom', () => {
    const rolling = startRoll({ u: 0, now: T0, kind: 'first' });
    const plan = planLanding(rolling, { now: T0 + 60, index: 7, n: 14 });
    const land = plan.segments[plan.segments.length - 1];
    expect(land.t0).toBe(rolling.decelFrom);
    expect(plan.stopAt).toBe(T0 + 250 + 900 + 700);
    expect(plan.stopAt + ROLL_STEPS.first.settle - T0).toBe(1970);     // + press 100 ≈ 2.07 s in all
  });

  it('a late answer keeps rolling at the steady speed until it arrives, then lands', () => {
    const rolling = startRoll({ u: 0, now: T0, kind: 'first' });
    const plan = planLanding(rolling, { now: T0 + 3000, index: 2, n: 14 });
    expect(sample(plan, T0 + 2999).v).toBeCloseTo(CRUISE, 10);
    const land = plan.segments[plan.segments.length - 1];
    expect(land.t0).toBeGreaterThanOrEqual(T0 + 3000);
    expect(land.t0).toBeLessThan(T0 + 3000 + 14 / CRUISE);             // at most one more lap
    expect(off(plan.finalU, 2, 14)).toBeLessThan(1e-9);
  });

  it('is deterministic: the same inputs give the same motion', () => {
    const a = planLanding(startRoll({ u: 1.5, now: T0, kind: 'again' }), { now: T0 + 90, index: 4, n: 11 });
    const b = planLanding(startRoll({ u: 1.5, now: T0, kind: 'again' }), { now: T0 + 90, index: 4, n: 11 });
    expect(b).toEqual(a);
  });

  it('roll again is faster: ≈1.2 s from the press to the settled tile', () => {
    const plan = planLanding(startRoll({ u: 3, now: T0, kind: 'again' }), { now: T0 + 50, index: 0, n: 13 });
    expect(plan.stopAt + ROLL_STEPS.again.settle - T0).toBe(150 + 450 + 500 + 120);
  });
});

describe('planRest — nothing to land on', () => {
  it('comes to rest between two tiles (the frame holds no food), without reversing', () => {
    for (const after of [0, 30, 200, 900, 2000]) {
      const rolling = startRoll({ u: 2, now: T0, kind: 'first' });
      const rest = planRest(rolling, { now: T0 + after });
      expect(mod(rest.finalU, 1)).toBeCloseTo(after === 0 ? 0 : 0.5, 9);   // at once: still where it was
      const samples = trace(rest, T0);
      for (let i = 1; i < samples.length; i += 1) expect(samples[i].u).toBeGreaterThanOrEqual(samples[i - 1].u - 1e-9);
      expect(sample(rest, rest.stopAt + 10).v).toBe(0);
    }
  });
});
