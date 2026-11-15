import { useSyncExternalStore } from 'react';
import en from './en';
import vi from './vi';

/**
 * Lightweight UI localization (EN/VI), no dependencies.
 *
 * - English is the default for first-time visitors.
 * - The choice is remembered in localStorage and reflected on <html lang>.
 * - `t(key, params)` looks the key up in the active dictionary, falling back
 *   to English, then to the key itself. `{name}` placeholders are filled from
 *   `params`; when `params.count` is a number, English uses `key_one` /
 *   `key_other` variants if they exist.
 * - Components call `useI18n()` so they re-render when the language changes.
 *   Plain modules (utils, API error mapping) may import `t` directly: it
 *   always reads the current language.
 *
 * Server data (restaurant names, food labels, addresses, reviews, Goong data)
 * is never translated.
 */

export const LANGUAGES = ['en', 'vi'];
export const DEFAULT_LANG = 'en';
const STORAGE_KEY = 'foodmap.lang';
const DICTIONARIES = { en, vi };

function readStoredLang() {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return LANGUAGES.includes(stored) ? stored : null;
  } catch {
    return null;
  }
}

let currentLang = (typeof window !== 'undefined' && readStoredLang()) || DEFAULT_LANG;
const listeners = new Set();

function syncDocumentLang() {
  if (typeof document !== 'undefined') document.documentElement.lang = currentLang;
}
syncDocumentLang();

export function getLang() {
  return currentLang;
}

export function setLang(lang) {
  if (!LANGUAGES.includes(lang) || lang === currentLang) return;
  currentLang = lang;
  try {
    window.localStorage.setItem(STORAGE_KEY, lang);
  } catch {
    // Storage unavailable (private mode): the choice lasts for this page only.
  }
  syncDocumentLang();
  listeners.forEach((listener) => listener());
}

function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** BCP 47 locale for number/date formatting in the active language. */
export function getLocale() {
  return currentLang === 'vi' ? 'vi-VN' : 'en-US';
}

function lookup(lang, key, count) {
  const dict = DICTIONARIES[lang];
  if (typeof count === 'number' && lang === 'en') {
    const plural = dict[`${key}_${count === 1 ? 'one' : 'other'}`];
    if (plural != null) return plural;
  }
  return dict[key];
}

export function t(key, params) {
  const count = params?.count;
  const message = lookup(currentLang, key, count) ?? lookup(DEFAULT_LANG, key, count);
  if (message == null) return key;
  if (!params) return message;
  return message.replace(/\{(\w+)\}/g, (match, name) => (name in params ? String(params[name]) : match));
}

/** React binding: re-renders the caller when the language changes. */
export function useI18n() {
  const lang = useSyncExternalStore(subscribe, getLang, getLang);
  return { lang, setLang, t, locale: lang === 'vi' ? 'vi-VN' : 'en-US' };
}
