import { useId } from 'react';

/**
 * ScopeBar — where to look: "Gần tôi", "Trung tâm", "Khu vực".
 *
 * A native radio group (arrow keys, one tab stop, checked state announced).
 * Choosing "Gần tôi" is the explicit action that may ask for the browser
 * location; choosing "Khu vực" opens the area list. The caller owns all
 * state; this component only renders and reports the choice.
 *
 * `options` lets another page reuse the control (the map's "Quán" / "Món").
 */
export const SCOPE_OPTIONS = [
  { value: 'near', label: 'Gần tôi' },
  { value: 'centre', label: 'Trung tâm' },
  { value: 'area', label: 'Khu vực' },
];

export default function ScopeBar({ value, onChange, busy = false, legend = 'Tìm quanh đâu?', options = SCOPE_OPTIONS }) {
  const name = useId();
  return (
    <fieldset className="min-w-0" aria-busy={busy || undefined}>
      <legend className="sr-only">{legend}</legend>
      <div className="inline-flex max-w-full flex-wrap gap-2">
        {options.map((option) => (
          <label key={option.value} className="relative cursor-pointer">
            <input
              type="radio"
              name={name}
              value={option.value}
              checked={value === option.value}
              onChange={() => onChange(option.value)}
              className="peer sr-only"
            />
            <span
              className="inline-flex min-h-11 items-center rounded-full border-2 border-line-strong/70 bg-surface px-4
                         text-small font-semibold text-ink transition-colors duration-fast
                         hover:bg-surface-sunken
                         peer-checked:border-ink peer-checked:bg-brand-400 peer-checked:shadow-sticker-soft
                         peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2
                         peer-focus-visible:outline-brand-700"
            >
              {option.label}
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
