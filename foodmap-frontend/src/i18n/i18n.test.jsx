import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import en from './en';
import viDict from './vi';

// The shared setup switches to Vietnamese for the older suites; these tests
// load a fresh copy of the module so the first-visit default is real.
async function freshI18n() {
  vi.resetModules();
  return import('./index');
}

describe('i18n', () => {
  beforeEach(() => window.localStorage.clear());

  it('defaults to English for a first-time visitor and sets <html lang>', async () => {
    const { getLang, t } = await freshI18n();
    expect(getLang()).toBe('en');
    expect(document.documentElement.lang).toBe('en');
    expect(t('notFound.title')).toBe('Page not found');
  });

  it('restores the stored language and ignores unknown values', async () => {
    window.localStorage.setItem('foodmap.lang', 'vi');
    expect((await freshI18n()).getLang()).toBe('vi');
    window.localStorage.setItem('foodmap.lang', 'fr');
    expect((await freshI18n()).getLang()).toBe('en');
  });

  it('interpolates and uses English plurals; Vietnamese has one form', async () => {
    const { t, setLang } = await freshI18n();
    expect(t('map.inFrame', { count: 1 })).toBe('1 place in the map frame');
    expect(t('map.inFrame', { count: 3 })).toBe('3 places in the map frame');
    setLang('vi');
    expect(t('map.inFrame', { count: 3 })).toBe('3 quán trong khung bản đồ');
    expect(t('no.such.key')).toBe('no.such.key');
  });

  it('the header switcher toggles the language, re-renders the page and persists the choice', async () => {
    const { setLang } = await freshI18n();
    const { default: LanguageSwitcher } = await import('../components/layout/LanguageSwitcher');
    const { default: NotFoundPage } = await import('../pages/NotFoundPage');
    setLang('en');
    render(
      <MemoryRouter>
        <LanguageSwitcher />
        <NotFoundPage />
      </MemoryRouter>,
    );
    const enBtn = screen.getByRole('button', { name: 'English' });
    const viBtn = screen.getByRole('button', { name: 'Tiếng Việt' });
    expect(enBtn).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Page not found')).toBeInTheDocument();

    fireEvent.click(viBtn);
    expect(viBtn).toHaveAttribute('aria-pressed', 'true');
    expect(enBtn).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('Không tìm thấy trang này')).toBeInTheDocument();
    expect(window.localStorage.getItem('foodmap.lang')).toBe('vi');
    expect(document.documentElement.lang).toBe('vi');

    fireEvent.click(enBtn);
    expect(screen.getByText('Page not found')).toBeInTheDocument();
    expect(window.localStorage.getItem('foodmap.lang')).toBe('en');
  });

  it('every Vietnamese key has an English translation and vice versa', () => {
    const base = (k) => k.replace(/_(one|other)$/, '');
    const enKeys = new Set(Object.keys(en).map(base));
    const viKeys = new Set(Object.keys(viDict).map(base));
    expect([...viKeys].filter((k) => !enKeys.has(k))).toEqual([]);
    expect([...enKeys].filter((k) => !viKeys.has(k))).toEqual([]);
  });
});
