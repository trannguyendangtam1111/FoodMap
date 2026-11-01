import { forwardRef, useId } from 'react';

const Input = forwardRef(function Input(
  { label, error, className = '', id, ...props },
  ref,
) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const errorId = `${inputId}-error`;

  return (
    <div className="flex flex-col gap-1.5">
      {label && (
        <label htmlFor={inputId} className="text-small font-semibold text-ink">{label}</label>
      )}
      <input
        ref={ref}
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        className={`
          h-11 w-full rounded-field border bg-surface px-3.5 text-body text-ink
          placeholder:text-ink-subtle outline-none
          transition-[border-color,box-shadow] duration-fast ease-standard
          focus:ring-4
          disabled:cursor-not-allowed disabled:bg-surface-sunken
          ${error
            ? 'border-danger focus:border-danger focus:ring-danger/20'
            : 'border-line hover:border-ink-subtle focus:border-brand-600 focus:ring-brand-400/25'}
          ${className}
        `}
        {...props}
      />
      {error && <p id={errorId} className="text-small text-danger-ink">{error}</p>}
    </div>
  );
});

export default Input;
