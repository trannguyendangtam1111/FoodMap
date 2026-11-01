import { useCallback } from 'react';
import { decideFood } from '../api/decision.api';
import { requestUserLocation } from '../utils/location';
import { MODES, toScopeParams } from '../utils/scope';
import { getLocationState, rememberLocation } from './useLocationScope';

/**
 * This tab's cà mèn session, in memory only: it survives in-app navigation
 * ("Tìm quán phở" → back) but not a reload, so nothing is persisted. Holds
 * the last revealed food and the food keys already shown (the `exclude`).
 */
const memory = { result: null, seen: [] };

/** Session handle for useTiffinDecision (`session` option). */
const session = {
  load: () => (memory.result ? { result: memory.result, seen: memory.seen } : null),
  save: ({ result, seen }) => {
    memory.result = result;
    memory.seen = seen;
  },
};

/** Tests only: start from a fresh session. */
export function resetFoodDecisionMemory() {
  memory.result = null;
  memory.seen = [];
}

/** The id the cà mèn excludes on rerolls: the food key (null → nothing to reveal). */
export const foodResultId = (response) => response?.food?.key ?? null;

/** The server's food exclusion limit (FoodDecisionRequest.MAX_EXCLUDED_FOODS). */
export const MAX_EXCLUDED_FOODS = 30;

/**
 * useFoodDecisionSource — wires the cà mèn to GET /api/v1/decisions/food.
 *
 *   prepare  resolves the scope from the shared location store (the same one
 *            "Thèm gì?" uses). Only on the "Mở cà mèn" press, and only while
 *            the user hasn't chosen a scope and the tab has no location yet,
 *            it asks the browser for the location — the explicit action the
 *            Master Specification allows. Denied / unavailable / slow → the
 *            city centre. Rerolls never ask.
 *   decide   one GET /decisions/food with that scope (lat+lng, area, or
 *            nothing) and the food keys already shown as `exclude`. The
 *            server draws the food; nothing here chooses or ranks.
 *   session  the in-memory session above.
 */
export default function useFoodDecisionSource({ request = decideFood, locate = requestUserLocation } = {}) {
  const prepare = useCallback(({ kind, signal }) => {
    const { scope, location, chosen } = getLocationState();
    const mayAsk = kind === 'open' && !chosen && !location && scope.mode === MODES.CITY_CENTER;
    if (!mayAsk) return { scope };
    return locate({ signal }).then((granted) => {
      if (!signal?.aborted && granted) rememberLocation(granted);   // → USER while nothing was chosen
      return { scope: getLocationState().scope };
    });
  }, [locate]);

  const decide = useCallback(({ scope, exclude, signal }) => request({
    params: toScopeParams(scope),
    exclude,
    signal,
  }), [request]);

  return { prepare, decide, session };
}
