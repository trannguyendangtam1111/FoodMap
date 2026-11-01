import Icon from '../ui/Icon';

/**
 * ActiveDishFilter — shows a dismissible chip when a dish filter is active.
 *
 * Props
 * ─────
 * food     FoodSummaryDTO  The active dish (must have at least id and name).
 * onClear  () => void      Called when the user clicks the × button.
 */
export default function ActiveDishFilter({ food, onClear }) {
  if (!food) return null;

  return (
    <div className="flex items-center gap-2 border-b border-line bg-brand-50 px-4 py-2">
      <Icon name="utensils" size={16} strokeWidth={2} className="text-brand-700" />

      <p className="min-w-0 flex-1 truncate text-small text-ink">
        <span className="font-semibold text-brand-800">Lọc theo món: </span>
        {food.name}
      </p>

      <button
        type="button"
        onClick={onClear}
        aria-label="Bỏ lọc món"
        className="ml-1 flex-shrink-0 rounded-full p-1 text-ink-muted
                   transition-colors hover:bg-brand-100 hover:text-brand-800"
      >
        <Icon name="x" size={14} strokeWidth={2.5} />
      </button>
    </div>
  );
}
