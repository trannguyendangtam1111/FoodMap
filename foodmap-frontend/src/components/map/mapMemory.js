/**
 * What the /map page remembers while the tab is open, so Back from a
 * restaurant returns to the same map: the camera, the mode, the sort, the
 * selected restaurant and the last viewport answer.
 *
 * Memory only — never localStorage, sessionStorage or a URL: a map centre
 * can reveal where someone is. A reload starts fresh. The selected food is
 * not kept here; it lives in the URL (?food=).
 */
let memory = null;

export function rememberMap(patch) {
  memory = { ...(memory ?? {}), ...patch };
}

/** The remembered state, or null. */
export function recallMap() {
  return memory;
}

/** Tests only. */
export function resetMapMemory() {
  memory = null;
}
