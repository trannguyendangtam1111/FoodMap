import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import Button, { buttonClass } from '../ui/Button';
import Icon from '../ui/Icon';
import FoodReveal, { supplyLine } from '../food/FoodReveal';
import RollReel from './RollReel';
import { ROLL_STEPS } from './rollMotion';
import useTiffinDecision, { PHASE } from '../../hooks/useTiffinDecision';
import usePrefersReducedMotion from '../../hooks/usePrefersReducedMotion';
import useRetryWait from '../../hooks/useRetryWait';
import useAvailableFoods from '../../hooks/useAvailableFoods';
import useLocationScope from '../../hooks/useLocationScope';
import { MAX_EXCLUDED_FOODS, foodResultId } from '../../hooks/useFoodDecisionSource';
import { scopeKey, scopeSearchOf } from '../../utils/scope';
import { DECISION_ANCHOR } from './TiffinDecision';
import { ROLL_WORDS, describeDecisionError, describeEmpty } from './decisionCopy';

/**
 * RollDecision — "Ăn gì hôm nay?" presented as Roll (Roll V1 specification).
 *
 * The same food decision as the cà mèn: the same source (useFoodDecisionSource:
 * location rule, GET /decisions/food, the one in-memory session and its
 * exclusions) and the same state machine (useTiffinDecision), with Roll's own
 * timing. The server picks the food; nothing here chooses, ranks or draws.
 *
 * The reel (RollReel) is decoration: the foods the server says are available
 * in the scope (MEAL, its catalog order, minus the foods already shown), each
 * once per loop, none marked. It rolls while the decision runs and lands on
 * the server's answer — learnt the moment it arrives, so the landing can be
 * planned — and the result is shown only once it has landed. A food the reel
 * did not list (a stale list) is appended once, at the end.
 *
 *   IDLE → PRESSING → [LOCATING] → DECIDING (rolling) → OPENING (landing) → REVEALED
 *
 * Reduced motion, or a single food: no reel motion; the result appears as
 * soon as the server answers.
 */

/** The hook's steps, from Roll's approved motion (rollMotion.ROLL_STEPS). */
export const ROLL_TIMING = Object.freeze({
  press: ROLL_STEPS.first.press,
  shake: ROLL_STEPS.first.accel + ROLL_STEPS.first.steady,          // the shortest roll: 1150 ms
  rerollShake: ROLL_STEPS.again.accel + ROLL_STEPS.again.steady,    // 600 ms
  open: ROLL_STEPS.first.land + ROLL_STEPS.first.settle,            // landing + settle: 820 ms
  rerollOpen: ROLL_STEPS.again.land + ROLL_STEPS.again.settle,      // 620 ms
  timeout: 5000,
});

/** If the reel has not reported its landing by then, show the result anyway. */
export const LANDING_SAFETY_MS = 3000;

/**
 * The foods the reel may show: the server's available foods for the scope,
 * MEAL only (what the decision draws from), in the server's order, without
 * the foods already shown this session. Decoration — never the choice.
 */
export function rollStripFoods(foods, seen = []) {
  const shown = new Set(seen);
  return (foods ?? []).filter((f) => f.kind === 'MEAL' && !shown.has(f.key));
}

/** The reel's foods with `food` on it: as listed, or appended once at the end (deterministic). */
export function withFood(foods, food) {
  if (!food?.key || foods.some((f) => f.key === food.key)) return foods;
  return [...foods, food];
}

export default function RollDecision({ decide, prepare, session, onBusyChange }) {
  const reducedMotion = usePrefersReducedMotion();

  // Runs are started here, from the decision's own calls — not by watching
  // phases, which React may batch: `prepare` opens every run (its kind tells a
  // first run from "Chạy lại"), `decide` is called once per run when the
  // request starts. The reel starts then — never before the press step is
  // over — with the foods available at that moment, frozen. If that run's
  // scope has no food list yet (its location was granted on this very press),
  // the run waits for it (`waitFor`) and starts rolling once it arrives.
  const runKind = useRef({ kind: 'first', at: 0 });
  const liveFoodsRef = useRef([]);
  const liveKeyRef = useRef(null);              // the scope liveFoodsRef holds the foods of (null: none yet)
  const reducedRef = useRef(reducedMotion);
  reducedRef.current = reducedMotion;
  const tappedPrepare = useCallback((args) => {
    const out = prepare ? prepare(args) : undefined;
    runKind.current = { kind: args?.kind === 'open' ? 'first' : 'again', at: Date.now() };
    return out;
  }, [prepare]);

  const callSeq = useRef(0);
  const [run, setRun] = useState(null);         // { id, kind, startAt, foods, animate, waitFor }
  const [answer, setAnswer] = useState(null);   // { seq, food | null } — the server's answer, as soon as it arrives
  const tappedDecide = useCallback(async (args) => {
    const seq = ++callSeq.current;
    const { kind, at } = runKind.current;
    const key = args?.scope ? scopeKey(args.scope) : liveKeyRef.current;
    const known = key === liveKeyRef.current;
    const foods = known ? liveFoodsRef.current : [];
    setRun({
      id: seq,
      kind,
      startAt: kind === 'first' ? Math.max(Date.now(), at + ROLL_TIMING.press) : Date.now(),
      foods,
      animate: !reducedRef.current && foods.length > 1,
      waitFor: known || reducedRef.current ? null : key,
    });
    try {
      const response = await decide(args);
      if (!args?.signal?.aborted) setAnswer({ seq, food: response?.food ?? null });
      return response;
    } catch (failure) {
      setAnswer({ seq, food: null });
      throw failure;
    }
  }, [decide]);

  const { phase, result, empty, error, busy, restored, seen, open, reroll, startOver } = useTiffinDecision({
    decide: tappedDecide, prepare: tappedPrepare, session, reducedMotion, timing: ROLL_TIMING, getResultId: foodResultId, maxExclude: MAX_EXCLUDED_FOODS,
  });
  const wait = useRetryWait(phase === PHASE.ERROR ? error : null);
  const { scope } = useLocationScope();
  const available = useAvailableFoods(scope);   // the "Thèm gì?" cache: no second request for the same scope
  const liveFoods = available.status === 'ready' ? rollStripFoods(available.data.foods, seen) : [];
  const liveKey = available.status === 'ready' ? scopeKey(scope) : null;
  liveFoodsRef.current = liveFoods;
  liveKeyRef.current = liveKey;

  // A run waiting for its scope's foods: start rolling once they are known,
  // while it is still deciding (later, the result simply appears).
  useEffect(() => {
    if (!run?.waitFor || run.waitFor !== liveKey) return;
    if (phase !== PHASE.PRESSING && phase !== PHASE.LOCATING && phase !== PHASE.DECIDING) return;
    const foods = liveFoodsRef.current;
    setRun({ ...run, foods, animate: foods.length > 1, startAt: Math.max(Date.now(), run.startAt), waitFor: null });
  }, [run, liveKey, phase]);

  const runAnswer = run && answer?.seq === run.id ? answer : null;

  // ── What is shown: the result only once the reel has landed on it ─────────
  const [shown, setShown] = useState(() => (restored ? result : null));
  const [shownFresh, setShownFresh] = useState(false);
  const [landedId, setLandedId] = useState(null);
  const awaiting = phase === PHASE.REVEALED && result != null && result !== shown;
  useEffect(() => {
    if (!awaiting) return undefined;
    const reelDone = !run?.animate || landedId === run.id;
    if (reelDone) {
      setShown(result);
      setShownFresh(true);
      return undefined;
    }
    const id = setTimeout(() => { setShown(result); setShownFresh(true); }, LANDING_SAFETY_MS);
    return () => clearTimeout(id);
  }, [awaiting, result, run, landedId]);

  const uiBusy = busy || awaiting;
  useEffect(() => { onBusyChange?.(uiBusy); }, [uiBusy, onBusyChange]);

  // While a run waits for its scope's foods (from the location step on), the
  // tiles already in place stay (no flash of empty plates, no answer shown
  // early); if the foods never come, the reel shows the rest view.
  const lastReelFoods = useRef([]);
  const holdTiles = uiBusy && (run?.waitFor || (!run && liveKey == null));
  const reelFoods = holdTiles ? lastReelFoods.current
    : run && !run.waitFor ? withFood(run.foods, runAnswer?.food) : withFood(liveFoods, shown?.food);
  lastReelFoods.current = reelFoods;
  // The run ended without a food (error, timeout, empty, cancelled) — whether or
  // not its request ever settles, the reel comes to rest between two tiles.
  const runOver = run != null && !busy && (phase === PHASE.ERROR || phase === PHASE.EMPTY || phase === PHASE.IDLE);
  const reelTarget = runOver ? { key: null } : runAnswer ? { key: runAnswer.food?.key ?? null } : null;
  const onLanded = useCallback((runId) => setLandedId(runId), []);

  const sectionRef = useRef(null);
  const headingRef = useRef(null);

  // First reveal: focus the food name; bring the actions clear of the bottom nav.
  const hadResult = useRef(shown != null);
  useEffect(() => {
    if (!hadResult.current && shown) {
      headingRef.current?.focus({ preventScroll: true });
      sectionRef.current?.querySelector('[data-decision-actions]')
        ?.scrollIntoView?.({ block: 'nearest', behavior: reducedMotion ? 'auto' : 'smooth' });
    }
    hadResult.current = shown != null;
  }, [shown]); // eslint-disable-line react-hooks/exhaustive-deps

  // Header / bottom-nav "Ăn gì hôm nay?" (/#an-gi): bring the panel into view, focus its action.
  const { hash, key } = useLocation();
  useEffect(() => {
    if (hash !== `#${DECISION_ANCHOR}`) return;
    sectionRef.current?.scrollIntoView?.({ block: 'center', behavior: reducedMotion ? 'auto' : 'smooth' });
    sectionRef.current?.querySelector('[data-decision-action]')?.focus({ preventScroll: true });
  }, [hash, key]); // eslint-disable-line react-hooks/exhaustive-deps

  const failed = phase === PHASE.EMPTY || phase === PHASE.ERROR;
  let hint = shown ? 'FoodMap gợi ý cho bạn' : 'Chạy Roll, FoodMap chọn một món cho bạn.';
  if (phase === PHASE.LOCATING) hint = 'Đang lấy vị trí của bạn…';
  else if (uiBusy) hint = shown ? 'FoodMap đang chọn món khác…' : 'FoodMap đang chọn món cho bạn…';
  else if (!shown && phase === PHASE.EMPTY) hint = describeEmpty(empty);
  else if (!shown && phase === PHASE.ERROR) hint = describeDecisionError(error, wait, ROLL_WORDS);

  return (
    <section
      id={DECISION_ANCHOR}
      ref={sectionRef}
      aria-labelledby="decision-title"
      data-decision-mode="roll"
      className="relative scroll-mt-24 overflow-hidden rounded-panel border border-brand-200 bg-brand-50
                 px-4 pb-6 pt-6 text-center sm:px-6 sm:pb-7 sm:pt-8"
    >
      <p role="status" aria-live="polite" aria-atomic="true" className="sr-only">
        {restored && !shownFresh && phase === PHASE.REVEALED ? '' : announcement(phase, shown, awaiting, empty, error, wait)}
      </p>

      <h2 id="decision-title" className="font-display text-h2 text-ink">Ăn gì hôm nay?</h2>
      {/* Below 430 px a hint takes one or two lines: two are kept, so the reel
          below never moves when the hint changes (on landing, on starting). */}
      <p
        id="decision-hint"
        className={`mt-1 flex min-h-[2lh] items-center justify-center text-body min-[430px]:block min-[430px]:min-h-0
                    ${!shown && phase === PHASE.ERROR ? 'text-danger-ink' : 'text-ink-muted'}`}
      >
        {hint}
      </p>

      <RollReel
        className="mt-4"
        foods={reelFoods}
        run={run?.animate ? run : null}
        target={reelTarget}
        restKey={shown && !uiBusy ? shown.food.key : null}
        animate={!reducedMotion && (run ? run.animate : reelFoods.length > 1)}
        dimmed={shown != null && !uiBusy}
        onLanded={onLanded}
      />

      {shown ? (
        <Result
          phase={phase}
          result={shown}
          fresh={shownFresh}
          empty={empty}
          error={error}
          wait={wait}
          busy={uiBusy}
          headingRef={headingRef}
          onReroll={reroll}
          onStartOver={startOver}
        />
      ) : (
        <Button
          size="lg"
          onClick={() => { if (!uiBusy && wait <= 0) open(); }}
          data-decision-action=""
          aria-disabled={uiBusy || wait > 0 || undefined}
          aria-busy={uiBusy || undefined}
          aria-describedby="decision-hint"
          className="mt-5 min-w-[11rem] aria-disabled:pointer-events-none aria-disabled:opacity-80"
        >
          {uiBusy ? <BusyMark /> : <Icon name="utensils" size={20} strokeWidth={2} />}
          {uiBusy ? 'Đang chọn…' : failed ? 'Thử lại' : 'Chạy Roll'}
        </Button>
      )}
    </section>
  );
}

function announcement(phase, shown, awaiting, empty, error, wait) {
  if (awaiting) return shown ? 'Đang chọn món khác…' : 'Đang chọn món cho bạn…';
  switch (phase) {
    case PHASE.LOCATING:
      return 'Đang lấy vị trí của bạn…';
    case PHASE.DECIDING:
    case PHASE.OPENING:
      return shown ? 'Đang chọn món khác…' : 'Đang chọn món cho bạn…';
    case PHASE.REVEALED:
      return shown ? `Gợi ý: ${shown.food.label}. ${supplyLine(shown)}.` : '';
    case PHASE.EMPTY:
      return describeEmpty(empty);
    case PHASE.ERROR:
      return describeDecisionError(error, wait, ROLL_WORDS);
    default:
      return '';
  }
}

// ── Result: the food the server chose, once the reel has landed on it ───────

function Result({ phase, result, fresh, empty, error, wait, busy, headingRef, onReroll, onStartOver }) {
  const exhausted = phase === PHASE.EMPTY;
  // A 400 most likely means the exclude list itself — start a clean session (as the cà mèn).
  const restart = exhausted || (phase === PHASE.ERROR && error?.status === 400);
  const waiting = wait > 0;
  const food = result.food;
  const findHref = `/mon/${encodeURIComponent(food.key)}${scopeSearchOf(result.scope)}`;

  return (
    <div className="relative mt-4 text-left">
      {exhausted ? (
        <div className="rounded-tile border-2 border-dashed border-brand-300 bg-surface/80 px-5 py-6 text-center">
          <p className="font-display text-h3 text-ink">{describeEmpty(empty)}</p>
          <p className="mt-1 text-small text-ink-muted">Xem lại từ đầu, hoặc chọn món ở mục “Thèm gì?” bên dưới.</p>
        </div>
      ) : (
        <FoodReveal
          key={result.decisionId ?? food.key}
          ref={headingRef}
          decision={result}
          animate={fresh}
          inert={busy}
          className={`transition-opacity duration-fast ease-standard ${busy ? 'opacity-50' : ''}`}
        />
      )}

      {phase === PHASE.ERROR && (
        <p className="mt-3 text-small font-semibold text-danger-ink">{describeDecisionError(error, wait, ROLL_WORDS)}</p>
      )}

      <div data-decision-actions="" className="scroll-mb-bottom-nav mt-4 flex flex-col gap-2 sm:flex-row sm:flex-wrap">
        <Button
          variant="secondary"
          onClick={() => { if (!waiting && !busy) (restart ? onStartOver : onReroll)(); }}
          data-decision-action=""
          aria-disabled={busy || waiting || undefined}
          aria-busy={busy || undefined}
          className="scroll-mb-bottom-nav w-full aria-disabled:pointer-events-none sm:w-auto"
        >
          {busy ? <BusyMark /> : <Icon name="utensils" size={18} strokeWidth={2} />}
          {restart ? 'Xem lại từ đầu' : 'Chạy lại'}
        </Button>
        {!exhausted && (
          <Link
            to={findHref}
            aria-disabled={busy || undefined}
            tabIndex={busy ? -1 : undefined}
            onClick={(e) => { if (busy) e.preventDefault(); }}
            className={buttonClass({
              variant: 'primary',
              className: '!h-auto min-h-11 py-2 text-center w-full aria-disabled:pointer-events-none aria-disabled:opacity-60 sm:w-auto',
            })}
          >
            <Icon name="search" size={18} strokeWidth={2} />
            Tìm quán {food.label.toLocaleLowerCase('vi')}
          </Link>
        )}
      </div>
    </div>
  );
}

/** Decorative spinner for busy buttons (the live region carries the words). */
function BusyMark() {
  return (
    <span
      aria-hidden="true"
      data-motion="essential"
      className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"
    />
  );
}
