import Button from '../ui/Button';
import Skeleton from '../ui/Skeleton';
import DecisionReveal from '../decision/DecisionReveal';
import { retryDetail } from '../food/RetryNotice';
import useRetryWait from '../../hooks/useRetryWait';

/**
 * FoodRestaurantSuggestion — "Gợi ý cho bạn": ONE restaurant with the food,
 * drawn by the server (GET /decisions/today?food=), shown with the V1
 * restaurant card (DecisionReveal: only the facts it has, the server's own
 * reasons — e.g. "Thực đơn có Phở Bò Tái").
 *
 * One action slot that keeps its element, so keyboard focus stays on it:
 * "Quán khác" (the server draws again, never one already suggested), then
 * "Xem lại từ đầu" once every restaurant has been shown, or "Thử lại" after
 * an error. While a request runs it is aria-disabled, not removed.
 *
 * A restored suggestion (back from a restaurant) is shown without the reveal
 * and without an announcement; new ones are announced politely.
 */
export default function FoodRestaurantSuggestion({
  state, foodKey, foodLabel, phrase, shownCount, headingId, onMore, onRestart, onRetry,
}) {
  const { status, decision, error, fresh } = state;
  const wait = useRetryWait(status === 'error' ? error : null);
  const busy = status === 'loading';
  const restaurant = decision?.restaurant ?? null;
  const food = foodLabel.toLocaleLowerCase('vi');

  const action = status === 'error'
    ? { label: 'Thử lại', run: onRetry }
    : status === 'allShown'
      ? { label: 'Xem lại từ đầu', run: onRestart }
      : { label: 'Quán khác', run: onMore };
  const held = busy || wait > 0;
  const showAction = status !== 'empty' && (restaurant || status === 'allShown' || status === 'error');

  const announcement = !fresh
    ? ''
    : status === 'ready' && restaurant
      ? `Gợi ý: ${restaurant.name}.`
      : status === 'allShown'
        ? `Bạn đã xem hết ${shownCount} quán ${food} ${phrase}.`
        : status === 'error'
          ? `Chưa tải được gợi ý. ${retryDetail(error, wait)}`
          : '';

  return (
    <section aria-labelledby={headingId} className="min-w-0">
      <h2 id={headingId} className="font-display text-h3 text-ink">Gợi ý cho bạn</h2>

      <div aria-busy={busy || undefined} className="mt-3">
        {/* "Thử lại" is the action slot below — one control, one name */}
        {status === 'error' && (
          <div className="mb-3 rounded-card border border-line bg-surface p-4">
            <p className="text-body font-semibold text-ink">Chưa tải được gợi ý.</p>
            <p className="mt-0.5 text-small text-ink-muted">{retryDetail(error, wait)}</p>
          </div>
        )}
        {status === 'allShown' ? (
          <div className="rounded-tile border-2 border-dashed border-line-strong/70 bg-surface p-4 sm:p-5">
            <p className="text-body text-ink">Bạn đã xem hết {shownCount} quán {food} {phrase}.</p>
          </div>
        ) : status === 'empty' ? (
          <p className="text-body text-ink-muted">Chưa có gợi ý quán {food} {phrase}.</p>
        ) : restaurant ? (
          <DecisionReveal
            key={decision.decisionId ?? restaurant.id}
            decision={decision}
            animate={fresh && status === 'ready'}
            inert={busy}
            showContext={false}
            directions
            linkState={foodKey ? { fromFood: foodKey } : undefined}
            className={busy ? 'opacity-60' : ''}
          />
        ) : status !== 'error' && <SuggestionSkeleton />}
      </div>

      {showAction && (
        <div data-suggestion-actions className="scroll-mb-bottom-nav mt-3">
          <Button
            variant="secondary"
            data-suggestion-action
            aria-disabled={held || undefined}
            onClick={() => { if (!held) action.run(); }}
            className={`w-full sm:w-auto ${held ? 'opacity-60' : ''}`}
          >
            {busy ? 'Đang chọn…' : action.label}
          </Button>
        </div>
      )}

      <p role="status" aria-live="polite" aria-atomic="true" className="sr-only">{announcement}</p>
    </section>
  );
}

function SuggestionSkeleton() {
  return (
    <div aria-hidden="true" className="overflow-hidden rounded-tile border-2 border-line bg-surface md:flex lg:block">
      <Skeleton className="h-24 w-full rounded-none sm:h-36 md:h-auto md:min-h-[12rem] md:w-56 lg:h-36 lg:min-h-0 lg:w-full" />
      <span className="block flex-1 p-4 sm:p-5">
        <Skeleton className="h-6 w-3/4" />
        <Skeleton className="mt-3 h-4 w-1/2" />
        <Skeleton className="mt-2 h-4 w-2/3" />
        <Skeleton className="mt-4 h-11 w-32 rounded-full" />
      </span>
    </div>
  );
}
