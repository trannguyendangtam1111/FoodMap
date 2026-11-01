import { useSyncExternalStore } from 'react';

/**
 * How "Ăn gì hôm nay?" is presented: the cà mèn ('tiffin', default) or Roll.
 * Presentation only — both run the same food decision and share one session.
 *
 * Module memory, like useLocationScope: it survives in-app navigation (back
 * from a food page keeps the mode), not a reload. Never written to storage,
 * a URL or the server.
 */
export const DECISION_MODES = Object.freeze({ TIFFIN: 'tiffin', ROLL: 'roll' });

let mode = DECISION_MODES.TIFFIN;
const listeners = new Set();

const subscribe = (listener) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};
const getSnapshot = () => mode;

export function setDecisionMode(next) {
  if (next !== DECISION_MODES.TIFFIN && next !== DECISION_MODES.ROLL) return;
  if (next === mode) return;
  mode = next;
  listeners.forEach((listener) => listener());
}

/** Tests only. */
export function resetDecisionMode() {
  mode = DECISION_MODES.TIFFIN;
  listeners.forEach((listener) => listener());
}

export default function useDecisionMode() {
  return [useSyncExternalStore(subscribe, getSnapshot, getSnapshot), setDecisionMode];
}
