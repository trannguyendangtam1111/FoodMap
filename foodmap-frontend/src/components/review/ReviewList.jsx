import { useCallback, useEffect, useState } from 'react';
import { reviewApi } from '../../api/review.api';
import { useAuth } from '../../hooks/useAuth';
import ReviewItem from './ReviewItem';
import ReviewForm from './ReviewForm';
import StarRating from '../ui/StarRating';
import Spinner from '../ui/Spinner';
import Button from '../ui/Button';
import { formatRating } from '../../utils/format';

const PAGE_SIZE = 10;

/**
 * ReviewList — paginated review section for a restaurant detail page.
 *
 * Props
 * ─────
 * restaurantId  number   The restaurant whose reviews to fetch.
 * avgRating     number   Current average (passed from parent to show summary).
 * reviewCount   number   Current total count.
 * foods         array    Food items for the "reviewing dish" selector in the form.
 */
export default function ReviewList({ restaurantId, avgRating = 0, reviewCount = 0, foods = [] }) {
  const { isAuthenticated, user } = useAuth();

  const [reviews, setReviews]         = useState([]);
  const [page, setPage]               = useState(0);
  const [totalPages, setTotalPages]   = useState(0);
  const [totalElements, setTotal]     = useState(reviewCount);
  const [isLoading, setIsLoading]     = useState(false);
  const [error, setError]             = useState('');
  const [showForm, setShowForm]       = useState(false);

  // Whether the current user has already posted a review (any page).
  // Set eagerly on form success, or lazily when we encounter their review in the list.
  const [hasReviewed, setHasReviewed] = useState(false);

  const load = useCallback(async (pageNum = 0) => {
    setIsLoading(true);
    setError('');
    try {
      const { data } = await reviewApi.getForRestaurant(restaurantId, {
        page: pageNum,
        size: PAGE_SIZE,
      });
      const incoming = data.content ?? [];
      setReviews((prev) => (pageNum === 0 ? incoming : [...prev, ...incoming]));
      setTotalPages(data.totalPages);
      setTotal(data.totalElements);
      setPage(pageNum);

      // Mark "already reviewed" if we spot the current user's review in any batch.
      if (user && incoming.some((r) => r.user?.id === user.id)) {
        setHasReviewed(true);
      }
    } catch {
      setError('Không tải được đánh giá.');
    } finally {
      setIsLoading(false);
    }
  }, [restaurantId, user]);

  useEffect(() => { load(0); }, [load]);

  // ── Handlers ──────────────────────────────────────────────────────────────

  const handleReviewPosted = () => {
    setShowForm(false);
    setHasReviewed(true);
    load(0); // reload from page 0 so the new review appears first
  };

  const handleDelete = useCallback(async (reviewId) => {
    await reviewApi.delete(restaurantId, reviewId);
    // Remove from local state immediately — no need to re-fetch.
    setReviews((prev) => prev.filter((r) => r.id !== reviewId));
    setTotal((n) => Math.max(0, n - 1));
    setHasReviewed(false); // user can write a new review now
  }, [restaurantId]);

  // ── Derived values ────────────────────────────────────────────────────────

  const canWriteReview = isAuthenticated && !hasReviewed && !showForm;

  // Distribution is only accurate when all reviews are loaded; hide bars otherwise
  const allReviewsLoaded = reviews.length >= totalElements && totalElements > 0;
  const ratingCounts = [5, 4, 3, 2, 1].map((star) => ({
    star,
    count: reviews.filter((r) => r.rating === star).length,
  }));
  const maxCount = Math.max(...ratingCounts.map((r) => r.count), 1);

  return (
    <section>
      {/* ── Section header ────────────────────────────────────────────────── */}
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-display text-h3 text-ink">
          Đánh giá
          {totalElements > 0 && (
            <span className="ml-2 font-sans text-body font-normal text-ink-muted">({totalElements})</span>
          )}
        </h2>
        {canWriteReview && (
          <Button variant="secondary" size="sm" onClick={() => setShowForm(true)}>
            Viết đánh giá
          </Button>
        )}
        {isAuthenticated && hasReviewed && (
          <span className="text-small text-ink-muted">Bạn đã đánh giá quán này</span>
        )}
        {!isAuthenticated && (
          <span className="text-small text-ink-muted">Đăng nhập để viết đánh giá</span>
        )}
      </div>

      {/* ── Rating summary bar ────────────────────────────────────────────── */}
      {avgRating > 0 && totalElements > 0 && (
        <div className="mb-6 flex gap-6 rounded-card bg-surface-sunken p-4">
          {/* Big number */}
          <div className="flex flex-shrink-0 flex-col items-center justify-center">
            <span className="font-display text-[2.5rem] font-black leading-none tabular-nums text-ink">
              {formatRating(avgRating)}
            </span>
            <StarRating value={avgRating} size="sm" className="mt-2" />
            <span className="mt-1 text-small text-ink-muted">{totalElements} đánh giá</span>
          </div>

          {/* Bar chart — only rendered when all reviews are loaded to avoid a
              misleading partial distribution */}
          {allReviewsLoaded && (
            <div className="min-w-0 flex-1 space-y-1.5">
              {ratingCounts.map(({ star, count }) => (
                <div key={star} className="flex items-center gap-2">
                  <span className="w-3 flex-shrink-0 text-right text-label tabular-nums text-ink-muted">{star}</span>
                  <svg className="h-3 w-3 flex-shrink-0 text-star" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                    <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                  </svg>
                  <div className="h-2 flex-1 overflow-hidden rounded-full bg-line">
                    <div
                      className="h-full rounded-full bg-star transition-[width] duration-slow ease-standard"
                      style={{ width: `${(count / maxCount) * 100}%` }}
                    />
                  </div>
                  <span className="w-5 flex-shrink-0 text-label tabular-nums text-ink-muted">{count}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ── Review form ───────────────────────────────────────────────────── */}
      {showForm && (
        <div className="mb-6">
          <ReviewForm
            restaurantId={restaurantId}
            foods={foods}
            onSuccess={handleReviewPosted}
            onCancel={() => setShowForm(false)}
          />
        </div>
      )}

      {error && <p className="mb-4 text-small text-danger-ink">{error}</p>}

      {/* ── Review items ──────────────────────────────────────────────────── */}
      {reviews.length === 0 && !isLoading ? (
        <p className="py-10 text-center text-body text-ink-muted">
          Chưa có đánh giá nào — hãy là người đầu tiên chia sẻ trải nghiệm!
        </p>
      ) : (
        <div>
          {reviews.map((review) => (
            <ReviewItem
              key={review.id}
              review={review}
              isOwn={user && review.user?.id === user.id}
              onDelete={handleDelete}
            />
          ))}
        </div>
      )}

      {isLoading && (
        <div className="flex justify-center py-6">
          <Spinner />
        </div>
      )}

      {page + 1 < totalPages && !isLoading && (
        <div className="mt-4 text-center">
          <Button variant="ghost" size="sm" onClick={() => load(page + 1)}>
            Xem thêm đánh giá
          </Button>
        </div>
      )}
    </section>
  );
}
