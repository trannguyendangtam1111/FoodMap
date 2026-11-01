import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * useTiffinDecision — the "Ăn gì hôm nay?" state machine.
 *
 *   IDLE ─open()─▶ PRESSING ─▶ [LOCATING] ─▶ DECIDING ─▶ OPENING ─▶ REVEALED
 *                                              │                     │
 *                                              ├─▶ EMPTY             └─reroll()─▶ …
 *                                              └─▶ ERROR (failure or timeout)
 *
 * `prepare({ kind, signal })` (optional) runs first, on the user's press —
 * e.g. asking for location. It may return a value or a Promise of an object
 * that is spread into the `decide` call. Its time does NOT count against
 * the decision timeout (the user may be answering a permission prompt);
 * it owns its own budget. LOCATING is shown only while it is still pending.
 * `kind` is 'open' for the main action and 'reroll' otherwise.
 *
 * `decide({ exclude, signal, ...prepared })` must return a Promise of a
 * DecisionResponse. The animation runs alongside it: once the answer is in,
 * DECIDING lasts only as long as the shake needs to read, never longer.
 * The selection itself is entirely the caller's (the backend's) business.
 *
 * `getResultId(response)` (optional) — the id of what was revealed (a
 * restaurant id by default; a food key for the food-first cà mèn), or null
 * when the response holds nothing to reveal → EMPTY, with the response kept
 * as `empty` so the UI can tell why. Revealed ids accumulate (at most
 * `maxExclude`) and are sent as `exclude` on every later run.
 *
 * `session` (optional) — { load(), save({ result, seen }) }. When load()
 * returns a result, the hook starts REVEALED with it (`restored: true`, so
 * the UI can skip the reveal) and keeps excluding `seen`; every new reveal
 * is saved back. Used so returning from a restaurant page keeps the answer.
 *
 * `timing` (optional) — how long each visible step lasts, for presentations
 * other than the cà mèn (Roll): the same keys as TIMING (press, shake = the
 * shortest "deciding", rerollShake = the same on a reroll, open = the reveal,
 * timeout), plus optional rerollOpen (the reveal on a reroll; default open).
 * Defaults to TIMING; reduced motion zeroes the decorative steps of whichever
 * timing is in use. `seen` (returned) is the current exclusion list.
 */

export const PHASE = Object.freeze({
  IDLE: 'idle',
  PRESSING: 'pressing',
  LOCATING: 'locating',
  DECIDING: 'deciding',
  OPENING: 'opening',
  REVEALED: 'revealed',
  EMPTY: 'empty',
  ERROR: 'error',
});

const BUSY = new Set([PHASE.PRESSING, PHASE.LOCATING, PHASE.DECIDING, PHASE.OPENING]);

export const TIMING = Object.freeze({
  press: 140,       // squash on press
  shake: 720,       // shortest readable shake on the first open
  rerollShake: 420, // quicker shake for "Đổi quán khác"
  open: 400,        // lid lifts
  timeout: 5000,    // waiting for the answer never runs longer than this
});

// Reduced motion: same logic, no waiting on decorative motion.
const reduced = (timing) => ({ ...timing, press: 0, shake: 0, rerollShake: 0, open: 0, rerollOpen: 0 });

/** The backend accepts at most 50 IDs in `exclude` (DecisionProperties). */
export const MAX_EXCLUDE = 50;

export class DecisionTimeoutError extends Error {
  constructor() {
    super('The decision took too long.');
    this.name = 'DecisionTimeoutError';
  }
}

const isCancellation = (error) =>
  error?.name === 'AbortError' || error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED';

const isThenable = (value) => value != null && typeof value.then === 'function';

const INITIAL = { phase: PHASE.IDLE, result: null, pending: null, error: null, empty: null };

const restaurantId = (response) => response?.restaurant?.id ?? null;

export default function useTiffinDecision({
  decide, prepare, reducedMotion = false, session, getResultId = restaurantId, maxExclude = MAX_EXCLUDE,
  timing = TIMING,
}) {
  const [saved] = useState(() => session?.load() ?? null);
  const [state, setState] = useState(() => (saved?.result
    ? { ...INITIAL, phase: PHASE.REVEALED, result: saved.result, restored: true }
    : INITIAL));

  const seenRef = useRef(saved?.seen ?? []); // ids already shown → `exclude`
  const runRef = useRef(0);         // bumps on every run and on unmount
  const busyRef = useRef(false);    // synchronous guard against double activation
  const timersRef = useRef(new Set());
  const abortRef = useRef(null);
  const decideRef = useRef(decide);
  const prepareRef = useRef(prepare);
  const reducedRef = useRef(reducedMotion);
  const sessionRef = useRef(session);
  const resultIdRef = useRef(getResultId);
  const maxExcludeRef = useRef(maxExclude);
  const timingRef = useRef(timing);
  timingRef.current = timing;
  resultIdRef.current = getResultId;
  maxExcludeRef.current = maxExclude;
  decideRef.current = decide;
  prepareRef.current = prepare;
  reducedRef.current = reducedMotion;
  sessionRef.current = session;

  const later = useCallback((ms, fn) => {
    const id = setTimeout(() => {
      timersRef.current.delete(id);
      fn();
    }, ms);
    timersRef.current.add(id);
    return id;
  }, []);

  const wait = useCallback(
    (ms) => new Promise((resolve) => (ms > 0 ? later(ms, resolve) : resolve())),
    [later],
  );

  // Unmount: invalidate the run in flight, stop every timer, cancel the request.
  useEffect(() => {
    const timers = timersRef.current;
    return () => {
      runRef.current += 1;
      timers.forEach(clearTimeout);
      timers.clear();
      abortRef.current?.abort();
    };
  }, []);

  const run = useCallback(async ({ kind, press, quick }) => {
    if (busyRef.current) return;
    busyRef.current = true;

    const id = ++runRef.current;
    const stale = () => id !== runRef.current;
    const t = reducedRef.current ? reduced(timingRef.current) : timingRef.current;
    const controller = new AbortController();
    abortRef.current = controller;

    // Preparation starts on the press itself, synchronously.
    let prepared = {};
    let preparing = null;
    if (prepareRef.current) {
      try {
        const out = prepareRef.current({ kind, signal: controller.signal });
        if (isThenable(out)) preparing = out;
        else prepared = out ?? {};
      } catch {
        prepared = {}; // a failed preparation must never block the decision
      }
    }
    let prepDone = !preparing;
    const prepPromise = preparing
      ? preparing.then((v) => v ?? {}, () => ({})).finally(() => { prepDone = true; })
      : null;

    // The request starts as soon as it can — at once without an async
    // preparation, else the moment it settles (even mid-press). Its timeout
    // starts with it.
    let timeoutId;
    const startRequest = (prep) => {
      let call;
      try {
        call = Promise.resolve(decideRef.current({ ...prep, exclude: [...seenRef.current], signal: controller.signal }));
      } catch (error) {
        call = Promise.reject(error);
      }
      const timeout = new Promise((_, reject) => {
        timeoutId = later(t.timeout, () => {
          reject(new DecisionTimeoutError()); // settles the race first …
          controller.abort();                 // … then cancels the request
        });
      });
      return Promise.race([call, timeout]);
    };
    const request = prepPromise
      ? prepPromise.then((prep) => (stale() ? new Promise(() => {}) : startRequest(prep)))
      : startRequest(prepared);
    request.catch(() => {}); // awaited below; don't report it as unhandled meanwhile

    try {
      if (press && t.press > 0) {
        setState((s) => ({ ...s, phase: PHASE.PRESSING, error: null, empty: null }));
        await wait(t.press);
        if (stale()) return;
      }

      // The visible "deciding" starts now (LOCATING looks the same).
      const shakeDone = wait(quick ? t.rerollShake : t.shake);

      if (prepPromise) {
        if (!prepDone) setState((s) => ({ ...s, phase: PHASE.LOCATING, error: null }));
        await prepPromise;
        if (stale()) return;
      }

      setState((s) => ({ ...s, phase: PHASE.DECIDING, error: null, empty: null }));

      const [response] = await Promise.all([request, shakeDone]);
      if (stale()) return;

      const shownId = resultIdRef.current(response);
      if (shownId == null) {
        setState((s) => ({ ...s, phase: PHASE.EMPTY, pending: null, empty: response ?? null }));
        return;
      }

      seenRef.current = [...seenRef.current.filter((x) => x !== shownId), shownId].slice(-maxExcludeRef.current);

      const openMs = quick ? (t.rerollOpen ?? t.open) : t.open;
      if (openMs > 0) {
        setState((s) => ({ ...s, phase: PHASE.OPENING, pending: response }));
        await wait(openMs);
        if (stale()) return;
      }

      setState({ phase: PHASE.REVEALED, result: response, pending: null, error: null, empty: null });
      sessionRef.current?.save({ result: response, seen: seenRef.current });
    } catch (error) {
      if (stale()) return;
      if (isCancellation(error)) {
        // Cancelled, not failed: go back to where the user was, no error.
        setState((s) => ({ ...s, phase: s.result ? PHASE.REVEALED : PHASE.IDLE, pending: null, error: null }));
        return;
      }
      setState((s) => ({ ...s, phase: PHASE.ERROR, pending: null, error }));
    } finally {
      clearTimeout(timeoutId);
      timersRef.current.delete(timeoutId);
      busyRef.current = false;
      if (abortRef.current === controller) abortRef.current = null;
    }
  }, [later, wait]);

  /** Main action (and retry from the closed box): press → shake → open. */
  const open = useCallback(() => run({ kind: 'open', press: true, quick: false }), [run]);

  /** "Đổi quán khác": a quicker shake, excluding every restaurant already shown. */
  const reroll = useCallback(() => run({ kind: 'reroll', press: false, quick: true }), [run]);

  /** "Xem lại từ đầu": forget what was shown (clear `exclude`) and pick again. */
  const startOver = useCallback(() => {
    if (busyRef.current) return;
    seenRef.current = [];
    run({ kind: 'reroll', press: false, quick: true });
  }, [run]);

  return {
    ...state,
    busy: BUSY.has(state.phase),
    seen: seenRef.current,
    open,
    reroll,
    startOver,
  };
}
