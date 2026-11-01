import { useCallback, useState } from 'react';

const KEY = 'foodmap:recent-searches';
const MAX = 8;

function readStorage() {
  try {
    const raw = localStorage.getItem(KEY);
    const parsed = JSON.parse(raw ?? '[]');
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function useRecentSearches() {
  const [searches, setSearches] = useState(readStorage);

  const add = useCallback((query) => {
    const term = query.trim();
    if (!term) return;
    setSearches((prev) => {
      const deduped = prev.filter((s) => s.toLowerCase() !== term.toLowerCase());
      const next = [term, ...deduped].slice(0, MAX);
      try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* quota */ }
      return next;
    });
  }, []);

  const remove = useCallback((query) => {
    setSearches((prev) => {
      const next = prev.filter((s) => s !== query);
      try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* quota */ }
      return next;
    });
  }, []);

  const clearAll = useCallback(() => {
    try { localStorage.removeItem(KEY); } catch { /* quota */ }
    setSearches([]);
  }, []);

  return { searches, add, remove, clearAll };
}
