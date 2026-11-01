import { useEffect, useRef } from 'react';
import { Link, useLocation } from 'react-router-dom';
import Button, { buttonClass } from '../ui/Button';
import Icon from '../ui/Icon';
import TiffinBox from './TiffinBox';
import FoodReveal, { supplyLine } from '../food/FoodReveal';
import useTiffinDecision, { PHASE } from '../../hooks/useTiffinDecision';
import usePrefersReducedMotion from '../../hooks/usePrefersReducedMotion';
import useRetryWait from '../../hooks/useRetryWait';
import { MAX_EXCLUDED_FOODS, foodResultId } from '../../hooks/useFoodDecisionSource';
import { scopeSearchOf } from '../../utils/scope';
import { describeDecisionError, describeEmpty } from './decisionCopy';

export { describeDecisionError, describeEmpty };

/** In-page anchor for the header / bottom-nav "Ăn gì hôm nay?" links. */
export const DECISION_ANCHOR = 'an-gi';

/**
 * TiffinDecision — "Ăn gì hôm nay?": the cà mèn the user opens to get one
 * FOOD suggestion (food-first, Master Specification §7, §17).
 *
 * The answer comes from the caller: `decide` (required), `prepare`
 * (optional, e.g. location on press) and `session` (optional, restores the
 * last answer after in-app navigation) — see useTiffinDecision and
 * useFoodDecisionSource. This component never fetches anything itself, never
 * asks for location on mount and never picks a food; it animates and renders
 * the food the server chose.
 *
 * Two views share one panel:
 *   stage  — the big box, until the first food is revealed
 *   result — the revealed food; "Món khác" asks the server for another one
 *            (excluding every food shown), "Tìm quán {món}" opens /mon/{key}
 *            in the scope the food was decided in (never coordinates)
 *
 * Accessibility: real buttons drive everything (the box is a pointer
 * shortcut). While busy they stay focusable with aria-disabled/aria-busy, so
 * focus is never dropped; a polite live region announces progress and the
 * result; the first reveal moves focus to the food name; "Món khác" keeps
 * focus on rerolls.
 */
export default function TiffinDecision({ decide, prepare, session, onBusyChange }) {
  const reducedMotion = usePrefersReducedMotion();
  const { phase, result, pending, empty, error, busy, restored, open, reroll, startOver } = useTiffinDecision({
    decide, prepare, session, reducedMotion, getResultId: foodResultId, maxExclude: MAX_EXCLUDED_FOODS,
  });
  const wait = useRetryWait(phase === PHASE.ERROR ? error : null);

  // The panel holding this presentation (DecisionPanel) locks its mode switch while busy.
  useEffect(() => { onBusyChange?.(busy); }, [busy, onBusyChange]);

  const sectionRef = useRef(null);
  const headingRef = useRef(null);
  const view = result ? 'result' : 'stage';

  // The stage's button disappears with the first reveal — move focus to the
  // food name instead of dropping it to <body>. On rerolls the "Món khác"
  // button keeps focus; the live region announces the change. The browser's
  // own focus scroll would centre the name, which on short phones leaves the
  // actions behind the fixed bottom nav; instead we scroll just enough to
  // bring them clear of it (scroll-mb-bottom-nav).
  const prevView = useRef(view);
  useEffect(() => {
    if (prevView.current === 'stage' && view === 'result') {
      headingRef.current?.focus({ preventScroll: true });
      // The whole actions row: on phones "Tìm quán …" stacks under "Món khác".
      sectionRef.current?.querySelector('[data-decision-actions]')
        ?.scrollIntoView?.({ block: 'nearest', behavior: reducedMotion ? 'auto' : 'smooth' });
    }
    prevView.current = view;
  }, [view]);

  // Header / bottom-nav links land here (/#an-gi): bring the box into view
  // and focus its action. Never starts a decision or asks for location.
  const { hash, key } = useLocation();
  useEffect(() => {
    if (hash !== `#${DECISION_ANCHOR}`) return;
    sectionRef.current?.scrollIntoView?.({ block: 'center', behavior: reducedMotion ? 'auto' : 'smooth' });
    sectionRef.current?.querySelector('[data-decision-action]')?.focus({ preventScroll: true });
  }, [hash, key]); // on navigation only (key changes on every link click)

  // What rises out of the box: the food being opened, else the one on show.
  const boxFood = (pending ?? result)?.food ?? null;

  return (
    <section
      id={DECISION_ANCHOR}
      ref={sectionRef}
      aria-labelledby="decision-title"
      className="relative scroll-mt-24 overflow-hidden rounded-panel border border-brand-200 bg-brand-50
                 px-4 pb-6 pt-6 text-center sm:px-6 sm:pb-7 sm:pt-8"
    >
      {/* map-paper dots, as on the detail hero */}
      <div
        aria-hidden="true"
        className="absolute inset-0 text-brand-300 opacity-40
                   bg-[radial-gradient(currentColor_1.5px,transparent_1.5px)] bg-[length:18px_18px]"
      />

      <p role="status" aria-live="polite" aria-atomic="true" className="sr-only">
        {/* A restored answer is not news: don't re-announce it on return. */}
        {restored && phase === PHASE.REVEALED ? '' : announcement(phase, result, empty, error, wait)}
      </p>

      {view === 'stage'
        ? <Stage phase={phase} food={boxFood} empty={empty} error={error} wait={wait} busy={busy} onOpen={open} />
        : (
          <Result
            phase={phase}
            result={result}
            food={boxFood}
            empty={empty}
            error={error}
            wait={wait}
            busy={busy}
            restored={restored}
            headingRef={headingRef}
            onReroll={reroll}
            onStartOver={startOver}
          />
        )}
    </section>
  );
}

// ── Copy (Master Specification §17; shared helpers in decisionCopy.js) ──────

function announcement(phase, result, empty, error, wait) {
  switch (phase) {
    case PHASE.LOCATING:
      return 'Đang lấy vị trí của bạn…';
    case PHASE.DECIDING:
      return result ? 'Đang chọn món khác…' : 'Đang chọn món cho bạn…';
    case PHASE.REVEALED:
      return `Gợi ý: ${result.food.label}. ${supplyLine(result)}.`;
    case PHASE.EMPTY:
      return describeEmpty(empty);
    case PHASE.ERROR:
      return describeDecisionError(error, wait);
    default:
      return '';
  }
}

// LOCATING looks like deciding: the box is already simmering.
const boxPose = (phase) => (phase === PHASE.LOCATING ? PHASE.DECIDING : phase);

// ── Stage: the closed box ───────────────────────────────────────────────────

function Stage({ phase, food, empty, error, wait, busy, onOpen }) {
  const failed = phase === PHASE.EMPTY || phase === PHASE.ERROR;
  const waiting = wait > 0;
  let copy = 'Để FoodMap chọn giúp bạn.';
  if (phase === PHASE.LOCATING) copy = 'Đang lấy vị trí của bạn…';
  else if (busy) copy = 'FoodMap đang chọn món cho bạn…';
  else if (phase === PHASE.EMPTY) copy = describeEmpty(empty);
  else if (phase === PHASE.ERROR) copy = describeDecisionError(error, wait);

  return (
    // Tablet (md): box beside the text, so the full-width card stays short
    <div className="relative md:flex md:items-center md:justify-center md:gap-10 md:text-left lg:block lg:text-center">
      <TiffinBox
        phase={boxPose(phase)}
        food={food}
        interactive
        onActivate={onOpen}
        className="mx-auto w-fit flex-shrink-0 md:mx-0 lg:mx-auto"
        artClassName="block h-32 w-auto overflow-visible sm:h-44"
      />
      <div>
        <h2 id="decision-title" className="mt-3 font-display text-h2 text-ink sm:mt-4 md:mt-0 lg:mt-4">Ăn gì hôm nay?</h2>
        <p id="decision-hint" className={`mt-1 text-body ${phase === PHASE.ERROR ? 'text-danger-ink' : 'text-ink-muted'}`}>
          {copy}
        </p>
        <Button
          size="lg"
          onClick={() => { if (!waiting) onOpen(); }}
          data-decision-action=""
          aria-disabled={busy || waiting || undefined}
          aria-busy={busy || undefined}
          aria-describedby="decision-hint"
          className="mt-4 min-w-[11rem] aria-disabled:pointer-events-none aria-disabled:opacity-80 sm:mt-5"
        >
          {busy ? <BusyMark /> : <Icon name="tiffin" size={20} strokeWidth={2} />}
          {busy ? 'Đang chọn…' : failed ? 'Thử lại' : 'Mở cà mèn'}
        </Button>
      </div>
    </div>
  );
}

// ── Result: the revealed food ──────────────────────────────────────────────

function Result({ phase, result, food, empty, error, wait, busy, restored, headingRef, onReroll, onStartOver }) {
  const exhausted = phase === PHASE.EMPTY;
  // A 400 most likely means the exclude list itself — start a clean session.
  const restart = exhausted || (phase === PHASE.ERROR && error?.status === 400);
  const waiting = wait > 0;
  // Mini box: replays shake → open on a reroll, otherwise holds its open pose.
  const boxPhase = busy ? boxPose(phase) : exhausted ? PHASE.EMPTY : PHASE.REVEALED;
  const shown = result.food;
  const findHref = `/mon/${encodeURIComponent(shown.key)}${scopeSearchOf(result.scope)}`;

  return (
    <div className="relative text-left">
      <div className="flex items-center gap-3">
        <TiffinBox phase={boxPhase} food={exhausted ? null : food} className="flex-shrink-0" artClassName="block h-14 w-auto overflow-visible" />
        <div className="min-w-0">
          <h2 id="decision-title" className="font-display text-h3 text-ink">Ăn gì hôm nay?</h2>
          <p className="text-small text-ink-muted">
            {busy ? 'FoodMap đang chọn món khác…' : 'FoodMap gợi ý cho bạn'}
          </p>
        </div>
      </div>

      {exhausted ? (
        <div className="mt-4 rounded-tile border-2 border-dashed border-brand-300 bg-surface/80 px-5 py-6 text-center">
          <p className="font-display text-h3 text-ink">{describeEmpty(empty)}</p>
          <p className="mt-1 text-small text-ink-muted">Xem lại từ đầu, hoặc chọn món ở mục “Thèm gì?” bên dưới.</p>
        </div>
      ) : (
        // Keyed by decision: each new food replays the reveal.
        <FoodReveal
          key={result.decisionId ?? shown.key}
          ref={headingRef}
          decision={result}
          animate={!restored}
          inert={busy}
          className={`mt-4 transition-opacity duration-fast ease-standard ${busy ? 'opacity-50' : ''}`}
        />
      )}

      {phase === PHASE.ERROR && (
        <p className="mt-3 text-small font-semibold text-danger-ink">{describeDecisionError(error, wait)}</p>
      )}

      <div data-decision-actions="" className="scroll-mb-bottom-nav mt-4 flex flex-col gap-2 sm:flex-row sm:flex-wrap">
        <Button
          variant="secondary"
          onClick={() => { if (!waiting) (restart ? onStartOver : onReroll)(); }}
          data-decision-action=""
          aria-disabled={busy || waiting || undefined}
          aria-busy={busy || undefined}
          className="scroll-mb-bottom-nav w-full aria-disabled:pointer-events-none sm:w-auto"
        >
          {busy ? <BusyMark /> : <Icon name="tiffin" size={18} strokeWidth={2} />}
          {restart ? 'Xem lại từ đầu' : 'Món khác'}
        </Button>
        {!exhausted && (
          <Link
            to={findHref}
            // While another food is being chosen this one is about to change: not followable.
            aria-disabled={busy || undefined}
            tabIndex={busy ? -1 : undefined}
            onClick={(e) => { if (busy) e.preventDefault(); }}
            className={buttonClass({
              variant: 'primary',
              // grows (never below 44 px) if a long food name wraps
              className: '!h-auto min-h-11 py-2 text-center w-full aria-disabled:pointer-events-none aria-disabled:opacity-60 sm:w-auto',
            })}
          >
            <Icon name="search" size={18} strokeWidth={2} />
            Tìm quán {shown.label.toLocaleLowerCase('vi')}
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
