import SiteSearch from '../search/SiteSearch';
import DecisionPanel from '../decision/DecisionPanel';
import useFoodDecisionSource from '../../hooks/useFoodDecisionSource';

/**
 * Homepage hero — two doors.
 *   Left:  "I know what I want" → search (a food opens /mon/{key}; other text, the map search)
 *   Right: "I don't know yet"   → "Ăn gì hôm nay?" (DecisionPanel: the cà mèn
 *          or Roll): it reveals a FOOD the server picks. Nothing happens until the user presses it:
 *          only then may location be asked for and GET /api/v1/decisions/food
 *          called (useFoodDecisionSource).
 */
export default function HeroSection() {
  const { decide, prepare, session } = useFoodDecisionSource();
  return (
    <section aria-labelledby="hero-title" className="mx-auto max-w-6xl px-4 pb-10 pt-8 sm:pt-14 lg:pb-16 lg:pt-20">
      {/* Columns are top-aligned so the copy never moves when the box grows on
          reveal; lg:pt-14 centres it against the closed box (380 vs 268 px). */}
      <div className="grid gap-8 sm:gap-10 lg:grid-cols-12 lg:items-start lg:gap-12">

        {/* ── Door 1: search ─────────────────────────────────────────────── */}
        <div className="lg:col-span-7 lg:pt-14">
          <h1
            id="hero-title"
            className="font-display text-[2.125rem] font-black leading-[1.25] text-ink sm:text-[2.75rem] lg:text-display"
          >
            Biết ăn gì thì tìm.
            <br />
            <span className="text-brand-700">Chưa biết thì mở.</span>
          </h1>
          <p className="mt-4 max-w-lg text-body-lg text-ink-muted">
            Tìm quán quen, khám phá món mới, hoặc để FoodMap gợi ý cho bạn.
          </p>
          <SiteSearch size="lg" className="mt-6 max-w-xl sm:mt-7" />
        </div>

        {/* ── Door 2: "Ăn gì hôm nay?" ───────────────────────────────────── */}
        <div className="lg:col-span-5">
          <DecisionPanel decide={decide} prepare={prepare} session={session} />
        </div>
      </div>
    </section>
  );
}
