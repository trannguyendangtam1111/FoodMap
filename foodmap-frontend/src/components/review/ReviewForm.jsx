import { useId, useState } from 'react';
import { reviewApi } from '../../api/review.api';
import Button from '../ui/Button';
import StarRating from '../ui/StarRating';

const RATING_LABELS = ['', 'Tệ', 'Tạm được', 'Ổn', 'Ngon', 'Tuyệt vời'];

const FIELD_CLASS =
  'w-full rounded-field border border-line bg-surface px-3.5 text-body text-ink outline-none ' +
  'placeholder:text-ink-subtle transition-[border-color,box-shadow] duration-fast ease-standard ' +
  'hover:border-ink-subtle focus:border-brand-600 focus:ring-4 focus:ring-brand-400/25';

/**
 * ReviewForm — inline form for creating a new restaurant review.
 *
 * Props
 * ─────
 * restaurantId  number              Restaurant to review.
 * foods         FoodSummaryDTO[]    Available dishes (optional selector).
 * onSuccess     () => void          Called after a review is successfully posted.
 * onCancel      () => void          Called when the user cancels.
 */
export default function ReviewForm({ restaurantId, foods = [], onSuccess, onCancel }) {
  const [rating, setRating]       = useState(0);
  const [comment, setComment]     = useState('');
  const [foodId, setFoodId]       = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError]         = useState('');

  const fieldId = useId();

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (rating === 0) {
      setError('Vui lòng chọn số sao trước khi đăng.');
      return;
    }
    setError('');
    setIsLoading(true);
    try {
      await reviewApi.create(restaurantId, {
        rating,
        comment: comment.trim() || undefined,
        foodId:  foodId ? Number(foodId) : undefined,
      });
      // Reset form state
      setRating(0);
      setComment('');
      setFoodId('');
      onSuccess?.();
    } catch (err) {
      // Server returns 400 with { message: "…" } for duplicates and validation errors.
      setError(
        err.response?.data?.message ??
        'Không gửi được đánh giá. Vui lòng thử lại.',
      );
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="space-y-4 rounded-card border border-line bg-surface-sunken/60 p-4 sm:p-5"
    >
      <div className="flex items-center justify-between">
        <h3 className="font-display text-h3 text-ink">Viết đánh giá</h3>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="rounded-tag text-small text-ink-muted transition-colors hover:text-ink"
          >
            Huỷ
          </button>
        )}
      </div>

      {/* ── Star picker ───────────────────────────────────────────────────── */}
      <div>
        <p className="mb-1.5 text-small font-semibold text-ink">Số sao của bạn *</p>
        <div className="flex items-center gap-3">
          <StarRating value={rating} size="lg" interactive onChange={setRating} />
          {rating > 0 && (
            <span className="text-body font-semibold text-warning-ink" aria-live="polite">
              {RATING_LABELS[rating]}
            </span>
          )}
        </div>
      </div>

      {/* ── Optional: reviewing a specific dish ───────────────────────────── */}
      {foods.length > 0 && (
        <div>
          <label htmlFor={`${fieldId}-food`} className="mb-1.5 block text-small font-semibold text-ink">
            Đánh giá một món cụ thể? <span className="font-normal text-ink-muted">(không bắt buộc)</span>
          </label>
          <select
            id={`${fieldId}-food`}
            value={foodId}
            onChange={(e) => setFoodId(e.target.value)}
            className={`h-11 ${FIELD_CLASS}`}
          >
            <option value="">Đánh giá chung cho quán</option>
            {foods.map((f) => (
              <option key={f.id} value={f.id}>{f.name}</option>
            ))}
          </select>
        </div>
      )}

      {/* ── Comment ───────────────────────────────────────────────────────── */}
      <div>
        <label htmlFor={`${fieldId}-comment`} className="mb-1.5 block text-small font-semibold text-ink">
          Trải nghiệm của bạn <span className="font-normal text-ink-muted">(không bắt buộc)</span>
        </label>
        <textarea
          id={`${fieldId}-comment`}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          placeholder="Bạn thấy quán thế nào? Có muốn giới thiệu cho bạn bè không?"
          rows={4}
          maxLength={2000}
          className={`resize-none py-2.5 ${FIELD_CLASS}`}
        />
        {comment.length > 0 && (
          <p className="mt-1 text-right text-label font-normal tabular-nums text-ink-muted">
            {comment.length} / 2000
          </p>
        )}
      </div>

      {/* ── Error ─────────────────────────────────────────────────────────── */}
      {error && (
        <p role="alert" className="rounded-field bg-danger-tint px-3 py-2 text-small text-danger-ink">{error}</p>
      )}

      {/* ── Actions ───────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" isLoading={isLoading} size="sm" disabled={rating === 0}>
          Đăng đánh giá
        </Button>
        {onCancel && (
          <Button type="button" variant="ghost" size="sm" onClick={onCancel}>
            Huỷ
          </Button>
        )}
        {rating === 0 && !isLoading && (
          <span className="text-small text-ink-muted">Chọn số sao trước nhé</span>
        )}
      </div>
    </form>
  );
}
