import { useId, useState } from 'react';
import TiffinDecision from './TiffinDecision';
import RollDecision from './RollDecision';
import useDecisionMode, { DECISION_MODES } from '../../hooks/useDecisionMode';

const OPTIONS = [
  { value: DECISION_MODES.TIFFIN, label: 'Cà mèn' },
  { value: DECISION_MODES.ROLL, label: 'Roll' },
];

/**
 * DecisionPanel — "Ăn gì hôm nay?" with a choice of presentation:
 * the cà mèn (default) or Roll.
 *
 * Exactly ONE presentation is mounted (keyed by mode, so switching remounts).
 * Both run the same decision with the same source (`decide`, `prepare`,
 * `session` from useFoodDecisionSource): the session memory is shared, so the
 * presentation mounted after a switch restores the current food and keeps
 * excluding every food already shown. The switch is a native radio group,
 * disabled while a decision is running.
 */
export default function DecisionPanel({ decide, prepare, session }) {
  const [mode, setMode] = useDecisionMode();
  const [busy, setBusy] = useState(false);
  const name = useId();
  const Presentation = mode === DECISION_MODES.ROLL ? RollDecision : TiffinDecision;

  return (
    <div>
      <fieldset disabled={busy} className="mb-3 min-w-0">
        <legend className="float-left mr-3 mt-3 text-small font-semibold text-ink-muted">Cách chọn món</legend>
        <div className="inline-flex flex-wrap gap-2">
          {OPTIONS.map((option) => (
            <label key={option.value} className="relative cursor-pointer has-[:disabled]:cursor-not-allowed">
              <input
                type="radio"
                name={name}
                value={option.value}
                checked={mode === option.value}
                // on each input too: React's own guard reads the input's attribute, not the fieldset's
                disabled={busy}
                onChange={() => { if (!busy) setMode(option.value); }}
                className="peer sr-only"
              />
              <span
                className="inline-flex min-h-11 items-center rounded-full border-2 border-line-strong/70 bg-surface px-4
                           text-small font-semibold text-ink transition-colors duration-fast
                           hover:bg-surface-sunken
                           peer-checked:border-ink peer-checked:bg-brand-400 peer-checked:shadow-sticker-soft
                           peer-disabled:opacity-60
                           peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2
                           peer-focus-visible:outline-brand-700"
              >
                {option.label}
              </span>
            </label>
          ))}
        </div>
      </fieldset>

      <Presentation key={mode} decide={decide} prepare={prepare} session={session} onBusyChange={setBusy} />
    </div>
  );
}
