import { useState } from 'react';
import StarRating from '../ui/StarRating';
import Badge from '../ui/Badge';
import Icon from '../ui/Icon';
import { formatRelativeTime } from '../../utils/format';

/**
 * ReviewItem — renders a single review card.
 *
 * Props
 * ─────
 * review       ReviewDTO from the backend
 * isOwn        true when the current user is the author of this review
 * onDelete     (reviewId) => void — called after the user confirms deletion
 */
export default function ReviewItem({ review, isOwn = false, onDelete }) {
  const { id, user, rating, comment, food, createdAt } = review;
  const [confirming, setConfirming] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);

  const handleDeleteClick = () => setConfirming(true);
  const handleCancel      = () => setConfirming(false);

  const handleConfirm = async () => {
    setIsDeleting(true);
    try {
      await onDelete?.(id);
    } finally {
      setIsDeleting(false);
      setConfirming(false);
    }
  };

  return (
    <article className="flex gap-3 border-b border-line py-4 last:border-0">
      {/* ── Avatar ────────────────────────────────────────────────────────── */}
      <div className="flex-shrink-0">
        {user.avatarUrl ? (
          <img
            src={user.avatarUrl}
            alt={user.displayName}
            className="h-9 w-9 rounded-full object-cover"
          />
        ) : (
          <div className="flex h-9 w-9 select-none items-center justify-center rounded-full
                          bg-brand-100 font-display text-body font-black text-brand-800">
            {user.displayName?.[0]?.toUpperCase() ?? '?'}
          </div>
        )}
      </div>

      {/* ── Body ──────────────────────────────────────────────────────────── */}
      <div className="min-w-0 flex-1">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="flex flex-wrap items-center gap-2 text-body font-semibold text-ink">
              {user.displayName}
              {isOwn && <Badge variant="brand">Bạn</Badge>}
            </p>
            {food && (
              <p className="mt-0.5 text-small text-brand-700">về món {food.name}</p>
            )}
          </div>

          <div className="flex flex-shrink-0 items-center gap-1">
            <span className="text-small text-ink-muted">{formatRelativeTime(createdAt)}</span>

            {/* Delete button — only shown to the author */}
            {isOwn && !confirming && (
              <button
                type="button"
                onClick={handleDeleteClick}
                className="rounded-full p-1.5 text-ink-subtle transition-colors duration-fast
                           hover:bg-danger-tint hover:text-danger-ink"
                aria-label="Xoá đánh giá"
              >
                <Icon name="trash" size={16} strokeWidth={2} />
              </button>
            )}
          </div>
        </div>

        <StarRating value={rating} size="sm" className="mt-1.5" />

        {comment && (
          <p className="mt-2 text-body leading-relaxed text-ink">{comment}</p>
        )}

        {/* ── Delete confirmation inline ─────────────────────────────────── */}
        {confirming && (
          <div className="mt-3 flex flex-wrap items-center gap-3 rounded-field bg-danger-tint px-3 py-2">
            <p className="text-small text-danger-ink">Xoá đánh giá này?</p>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={isDeleting}
              className="rounded-tag text-small font-semibold text-danger-ink underline-offset-2
                         hover:underline disabled:opacity-50"
            >
              {isDeleting ? 'Đang xoá…' : 'Xoá'}
            </button>
            <button
              type="button"
              onClick={handleCancel}
              disabled={isDeleting}
              className="rounded-tag text-small text-ink-muted hover:text-ink"
            >
              Huỷ
            </button>
          </div>
        )}
      </div>
    </article>
  );
}
