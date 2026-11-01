import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import apiClient from '../../api/client';
import HeroSection from '../home/HeroSection';
import { TIMING } from '../../hooks/useTiffinDecision';
import { resetFoodDecisionMemory } from '../../hooks/useFoodDecisionSource';
import { resetLocationScope } from '../../hooks/useLocationScope';
import { resetAvailableFoodsCache } from '../../hooks/useAvailableFoods';
import { resetDecisionMode, setDecisionMode } from '../../hooks/useDecisionMode';
import { ROLL_TIMING, withFood } from './RollDecision';
import { cityFoods, userFoods } from '../../test/foodFixtures';
import { LAU, PHO, SUSHI, foodDecision } from '../../test/foodDecisionFixtures';

// Roll R3 through the real panel, hook, source and reel; only HTTP and the
// browser location are faked. Time is fake: the reel's clock (Date.now) and
// its timers move with vi.advanceTimersByTimeAsync.
vi.mock('../../api/client', () => ({ default: { get: vi.fn() } }));

const PIZZA = { key: 'pizza', label: 'Pizza', kind: 'MEAL', parentCategory: 'pizza', tone: 'crust', artKey: 'pizza' };
// One act per ~frame, as a browser renders while time passes (a single large
// act would defer every render to its end).
const advance = async (ms) => {
  for (let left = ms; left > 0; left -= 16) {
    // eslint-disable-next-line no-await-in-loop
    await act(() => vi.advanceTimersByTimeAsync(Math.min(16, left)));
  }
};
let decisions;

beforeEach(() => {
  vi.useFakeTimers();
  decisions = [];
  apiClient.get.mockReset();
  apiClient.get.mockImplementation(async (url) => {
    if (url === '/decisions/food') {
      const next = decisions.shift();
      return { data: typeof next === 'function' ? await next() : next };
    }
    if (url === '/decisions/foods/available') return { data: cityFoods() };   // MEAL: bun, pho, pizza, sushi
    throw new Error(`unexpected request ${url}`);
  });
  vi.stubGlobal('navigator', {
    ...navigator,
    geolocation: { getCurrentPosition: vi.fn((ok, fail) => fail({ code: 1 })), watchPosition: vi.fn() },
    permissions: { query: vi.fn(async () => ({ state: 'prompt' })) },
  });
  resetFoodDecisionMemory();
  resetLocationScope();
  resetAvailableFoodsCache();
  resetDecisionMode();
  setDecisionMode('roll');
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const later = (ms, body) => () => new Promise((resolve) => setTimeout(() => resolve(body), ms));
const renderRoll = () => render(
  <MemoryRouter><Routes><Route path="/" element={<HeroSection />} /></Routes></MemoryRouter>,
);
const reel = () => document.querySelector('[data-roll-strip]');
const state = () => reel().dataset.reelState;
const centred = () => reel().dataset.centredKey;
const run = () => fireEvent.click(screen.getByRole('button', { name: 'Chạy Roll' }));
const heading = (name) => screen.queryByRole('heading', { name });
const FIRST = ROLL_TIMING.press + ROLL_TIMING.shake + ROLL_TIMING.open;   // 2070

describe('Roll — the reel rolls and lands on the server\'s food', () => {
  it('idle: still, between two tiles (the frame holds no food)', async () => {
    renderRoll();
    await advance(10);
    expect(state()).toBe('idle');
    expect(centred()).toBe('');
  });

  it('starts rolling with the decision and lands on the server\'s food — an early answer waits for the shortest roll', async () => {
    decisions.push(foodDecision(PIZZA));                 // answers at once
    renderRoll();
    await advance(10);
    run();
    await advance(ROLL_TIMING.press + 20);
    expect(state()).toBe('rolling');
    await advance(1000);
    expect(heading('Pizza')).toBeNull();                 // not before the approved roll
    expect(['rolling', 'landing']).toContain(state());
    await advance(FIRST - ROLL_TIMING.press - 20 - 1000 - 30);
    expect(heading('Pizza')).toBeNull();
    await advance(60);
    expect(state()).toBe('landed');
    expect(centred()).toBe('pizza');                     // the server's tile, in the frame
    expect(heading('Pizza')).toBeInTheDocument();
  });

  it('a late answer: keeps rolling until it arrives, then lands — never shows the result before landing', async () => {
    decisions.push(later(3000, foodDecision(SUSHI)));
    renderRoll();
    await advance(10);
    run();
    await advance(2900);
    expect(state()).toBe('rolling');                     // still waiting, steady
    expect(heading('Sushi')).toBeNull();
    await advance(100 + 700);                            // answer in; landing (and maybe a bit more rolling)
    expect(heading('Sushi')).toBeNull();
    await advance(2000);
    expect(centred()).toBe('sushi');
    expect(heading('Sushi')).toBeInTheDocument();
  });

  it('the panel stays locked until the reel has landed', async () => {
    decisions.push(later(3000, foodDecision(SUSHI)));
    renderRoll();
    await advance(10);
    run();
    for (const at of [500, 2500, 3200, 3700]) {
      await advance(at - (at === 500 ? 0 : [500, 2500, 3200, 3700][[500, 2500, 3200, 3700].indexOf(at) - 1]));
      if (!heading('Sushi')) expect(screen.getByRole('radio', { name: 'Cà mèn' })).toBeDisabled();
    }
    await advance(2000);
    expect(screen.getByRole('radio', { name: 'Cà mèn' })).toBeEnabled();
  });
});

describe('Roll — the hand-over from reel to result', () => {
  it('rolling → landing → landed, and the landed food stays in the frame (no flash back to idle)', async () => {
    decisions.push(foodDecision(PHO), foodDecision(SUSHI));
    renderRoll();
    await advance(10);
    const states = [];
    const observer = new MutationObserver(() => states.push(`${reel().dataset.reelState}:${reel().dataset.centredKey ?? ''}`));
    observer.observe(reel(), { attributes: true, attributeFilter: ['data-reel-state', 'data-centred-key'] });
    run();
    await advance(FIRST + 50);
    fireEvent.click(screen.getByRole('button', { name: 'Chạy lại' }));
    await advance(ROLL_TIMING.rerollShake + ROLL_TIMING.rerollOpen + 50);
    observer.disconnect();
    const fromRun = states.slice(states.indexOf('rolling:'));   // (idle rewrites before the press are not changes)
    const compact = fromRun.filter((x, i) => x !== fromRun[i - 1]);
    expect(compact).toEqual(['rolling:', 'landing:', 'landed:pho', 'rolling:', 'landing:', 'landed:sushi']);
  });

  // R5: at 320 px the hint went from two lines (rolling) to one (landed), so the
  // reel jumped up 22 px the moment it landed (375 px: on starting). jsdom has
  // no layout, so this pins the two-line reservation through every phase.
  it('the hint above the reel keeps two lines on narrow screens, so the reel never moves', async () => {
    decisions.push(foodDecision(PHO));
    renderRoll();
    await advance(10);
    const hint = document.getElementById('decision-hint');
    const reserved = () => {
      const now = document.getElementById('decision-hint');
      return now === hint && ['min-h-[2lh]', 'min-[430px]:min-h-0'].every((c) => now.classList.contains(c));
    };
    expect(reserved()).toBe(true);
    run();
    await advance(ROLL_TIMING.press + 50);
    expect(state()).toBe('rolling');
    expect(reserved()).toBe(true);
    await advance(FIRST);
    expect(heading('Phở')).toBeInTheDocument();
    expect(reserved()).toBe(true);
  });
});

describe('Roll — location granted on the press', () => {
  // Final audit P1: the press asks for the location; once granted, the scope
  // becomes USER and its food list is still loading when the request starts.
  // The reel used to roll the run with whatever list it held at that moment:
  // empty plates (no motion at all — seen live) or the previous scope's foods.
  it('waits for the new scope\'s foods, rolls those (not the old scope\'s, never an empty strip) and lands on the server\'s food', async () => {
    vi.stubGlobal('navigator', {
      ...navigator,
      geolocation: { getCurrentPosition: vi.fn((ok) => ok({ coords: { latitude: 10.7731, longitude: 106.703 } })), watchPosition: vi.fn() },
      permissions: { query: vi.fn(async () => ({ state: 'prompt' })) },
    });
    const base = apiClient.get.getMockImplementation();
    apiClient.get.mockImplementation((url, config) => (url === '/decisions/foods/available' && config?.params?.lat != null
      ? new Promise((resolve) => { setTimeout(() => resolve({ data: userFoods() }), 300); })   // MEAL: pho, sushi
      : base(url, config)));
    decisions.push(foodDecision(SUSHI));                 // answers at once, before the USER foods
    renderRoll();
    await advance(10);
    const rolledWith = [];
    const emptyStrip = [];
    const observer = new MutationObserver(() => {
      if (reel().dataset.reelState === 'rolling') rolledWith.push(reel().dataset.foods);
      if (!reel().dataset.foods) emptyStrip.push(reel().dataset.reelState);
    });
    observer.observe(reel(), { attributes: true, attributeFilter: ['data-reel-state', 'data-foods'] });
    run();
    await advance(FIRST + 600);
    observer.disconnect();
    expect(apiClient.get).toHaveBeenCalledWith('/decisions/food', expect.objectContaining({
      params: expect.objectContaining({ lat: 10.7731, lng: 106.703 }),
    }));
    expect(rolledWith.length).toBeGreaterThan(0);
    expect(new Set(rolledWith)).toEqual(new Set(['pho sushi']));   // the USER scope's MEAL foods
    expect(emptyStrip).toEqual([]);
    expect(state()).toBe('landed');
    expect(centred()).toBe('sushi');
    expect(heading('Sushi')).toBeInTheDocument();
  });
});

describe('Roll — a food the reel did not list', () => {
  it('withFood: appended once at the end, never twice, never reordered', () => {
    const foods = [{ key: 'bun' }, { key: 'pho' }];
    expect(withFood(foods, { key: 'lau' }).map((f) => f.key)).toEqual(['bun', 'pho', 'lau']);
    expect(withFood(foods, { key: 'pho' })).toBe(foods);
    expect(withFood(foods, null)).toBe(foods);
  });

  it('the server\'s food is added to the reel and becomes the landed tile', async () => {
    decisions.push(foodDecision(LAU));                   // lẩu is not available in the cached list
    renderRoll();
    await advance(10);
    expect(reel().dataset.foods).toBe('bun pho pizza sushi');
    run();
    await advance(FIRST + 10);
    expect(reel().dataset.foods).toBe('bun pho pizza sushi lau');
    expect(centred()).toBe('lau');
    expect(heading('Lẩu')).toBeInTheDocument();
  });
});

describe('Roll again — "Chạy lại"', () => {
  it('excludes every food shown, runs on the faster timing, and the server still decides', async () => {
    decisions.push(foodDecision(PHO), foodDecision(SUSHI));
    renderRoll();
    await advance(10);
    run();
    await advance(FIRST + 10);
    expect(heading('Phở')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Chạy lại' }));
    const again = ROLL_TIMING.rerollShake + ROLL_TIMING.rerollOpen;      // 1220: no press step on a reroll
    expect(again).toBe(150 + 450 + 500 + 120);
    await advance(again - 40);
    expect(heading('Sushi')).toBeNull();
    await advance(60);
    expect(heading('Sushi')).toBeInTheDocument();
    expect(centred()).toBe('sushi');
    const params = apiClient.get.mock.calls.filter(([u]) => u === '/decisions/food').map(([, c]) => c.params);
    expect(params).toEqual([{}, { exclude: 'pho' }]);
    expect(reel().dataset.foods.split(' ')).not.toContain('pho');        // the shown food left the reel
  });

  it('the cà mèn\'s timing is untouched', () => {
    expect(TIMING).toEqual({ press: 140, shake: 720, rerollShake: 420, open: 400, timeout: 5000 });
    expect(TIMING).not.toHaveProperty('rerollOpen');
  });
});

describe('Roll — reduced motion', () => {
  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
  });

  it('no reel motion; the result appears as soon as the server answers', async () => {
    decisions.push(later(300, foodDecision(PHO)));
    renderRoll();
    await advance(10);
    expect(state()).toBe('static');
    const transform = reel().querySelector('.will-change-transform').style.transform;
    run();
    await advance(200);
    expect(state()).toBe('static');
    expect(heading('Phở')).toBeNull();
    await advance(110);                                  // the answer: 300 ms
    expect(heading('Phở')).toBeInTheDocument();
    expect(screen.getByRole('article', { name: 'Phở' })).toBeInTheDocument();
    expect(state()).toBe('static');
    expect(centred()).toBe('pho');
    expect(transform).toBeTruthy();                      // positioned, never animated
  });
});

describe('Roll — accessibility', () => {
  it('the reel is decorative and unfocusable; the food is text, focused on first reveal and announced', async () => {
    decisions.push(foodDecision(PIZZA));
    renderRoll();
    await advance(10);
    expect(reel()).toHaveAttribute('aria-hidden', 'true');
    expect(reel().querySelectorAll('a, button, input, [tabindex]')).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Chạy Roll' }).tagName).toBe('BUTTON');
    run();
    await advance(FIRST + 10);
    expect(screen.getByRole('heading', { name: 'Pizza' })).toHaveFocus();
    expect(screen.getByRole('status')).toHaveTextContent('Gợi ý: Pizza.');
    expect(screen.getByRole('link', { name: 'Tìm quán pizza' })).toHaveAttribute('href', '/mon/pizza');
  });
});

describe('Roll — cancellation', () => {
  it('leaving mid-roll stops everything: no timers left, nothing revealed later', async () => {
    decisions.push(later(800, foodDecision(PHO)));
    const view = renderRoll();
    await advance(10);
    run();
    await advance(600);
    expect(state()).toBe('rolling');
    view.unmount();
    await advance(5000);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('an answer that comes after its run timed out can never replace the newer decision', async () => {
    decisions.push(later(6500, foodDecision(SUSHI)), foodDecision(PHO));
    renderRoll();
    await advance(10);
    run();
    await advance(ROLL_TIMING.press + ROLL_TIMING.timeout + 50);
    expect(screen.getAllByText('Roll chạy hơi lâu. Thử lại nhé.').length).toBeGreaterThan(0);
    await advance(400);                                  // coming to rest (≈300 ms)
    expect(['rest', 'idle']).toContain(state());         // came to rest between tiles: no food in the frame
    expect(centred()).toBe('');

    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await advance(FIRST + 2000);                         // the stale answer (6.5 s) arrives meanwhile
    expect(heading('Phở')).toBeInTheDocument();
    expect(heading('Sushi')).toBeNull();
    expect(centred()).toBe('pho');
  });
});
