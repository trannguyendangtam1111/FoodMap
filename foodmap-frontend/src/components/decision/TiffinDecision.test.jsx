import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import TiffinDecision from './TiffinDecision';
import { TIMING } from '../../hooks/useTiffinDecision';
import { DecisionApiError } from '../../api/decision.api';
import { AREA_SCOPE, BUN_BO, LAU, PHO, SUSHI, USER_SCOPE, foodDecision, noFood } from '../../test/foodDecisionFixtures';

const LATENCY = 300; // simulated backend round trip
const FULL_RUN = TIMING.press + Math.max(TIMING.shake, LATENCY) + TIMING.open;
const REROLL_RUN = Math.max(TIMING.rerollShake, LATENCY) + TIMING.open;

const advance = (ms) => act(() => vi.advanceTimersByTimeAsync(ms));

/**
 * A stand-in for the backend: answers each call with the next scripted
 * response (or rejects with it, if it is an Error) after LATENCY ms.
 * Honours the AbortSignal like axios does.
 */
function backend(...script) {
  return vi.fn(({ signal }) => new Promise((resolve, reject) => {
    const next = script.shift();
    const timer = setTimeout(() => (next instanceof Error ? reject(next) : resolve(next)), LATENCY);
    signal?.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(Object.assign(new Error('canceled'), { name: 'CanceledError', code: 'ERR_CANCELED' }));
    });
  }));
}

function Where() {
  const { pathname, search } = useLocation();
  return <p data-testid="where">{pathname + search}</p>;
}

function renderDecision(decide) {
  return render(
    <MemoryRouter>
      <Routes>
        <Route path="/" element={<TiffinDecision decide={decide} />} />
        <Route path="/mon/:foodKey" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

const mainButton = () => screen.getByRole('button', { name: /Mở cà mèn|Đang chọn|Thử lại/ });
const rerollButton = () => screen.getByRole('button', { name: /Món khác|Xem lại từ đầu/ });
const findLink = () => screen.getByRole('link', { name: /^Tìm quán/ });
const liveRegion = () => screen.getByRole('status');
const card = () => screen.getByRole('article');
// Visible copy only — the sr-only live region repeats the same words.
const visible = (text) => screen.getByText(text, { ignore: '[role=status], script, style' });
const queryVisible = (text) => screen.queryByText(text, { ignore: '[role=status], script, style' });

async function openBox() {
  fireEvent.click(mainButton());
  await advance(FULL_RUN);
}

function mockReducedMotion(reduce) {
  window.matchMedia = vi.fn((query) => ({
    matches: reduce && query.includes('prefers-reduced-motion: reduce'),
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.useRealTimers();
  delete window.matchMedia;
});

// ── Interaction ─────────────────────────────────────────────────────────────

describe('TiffinDecision — interaction', () => {
  it('renders the closed box and asks nothing on mount', () => {
    const decide = backend(foodDecision(PHO));
    const { container } = renderDecision(decide);
    expect(screen.getByRole('heading', { name: 'Ăn gì hôm nay?' })).toBeInTheDocument();
    expect(container.querySelector('.fm-tiffin')).toHaveAttribute('data-phase', 'idle');
    expect(decide).not.toHaveBeenCalled();
  });

  it('is operable by keyboard through a real button', async () => {
    vi.useRealTimers(); // user-event drives real keyboard events; no timing asserted here
    const user = userEvent.setup();
    const decide = backend(foodDecision(PHO));
    renderDecision(decide);

    const button = mainButton();
    expect(button.tagName).toBe('BUTTON');
    expect(button).toHaveAccessibleDescription('Để FoodMap chọn giúp bạn.');
    await user.tab();
    expect(button).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(decide).toHaveBeenCalledTimes(1);
  });

  it('reveals exactly the food the backend returned — it never picks one', async () => {
    const decide = backend(foodDecision(PHO), foodDecision(SUSHI, { restaurantCount: 4 }));
    renderDecision(decide);

    fireEvent.click(mainButton());
    expect(decide).toHaveBeenCalledTimes(1); // the request starts on the press
    await advance(FULL_RUN);
    expect(screen.getByRole('heading', { name: 'Phở' })).toHaveFocus();
    expect(liveRegion()).toHaveTextContent('Gợi ý: Phở. 3 quán quanh trung tâm TP.HCM.');
    expect(within(card()).getByText('3 quán quanh trung tâm TP.HCM')).toBeInTheDocument();

    fireEvent.click(rerollButton());
    await advance(REROLL_RUN);
    expect(screen.getByRole('heading', { name: 'Sushi' })).toBeInTheDocument();
    expect(rerollButton()).toHaveAccessibleName('Món khác');
  });

  it('shows no restaurant, rating, distance or price — the answer is a food', async () => {
    renderDecision(backend(foodDecision(PHO)));
    await openBox();
    expect(card().textContent).toBe('Phở3 quán quanh trung tâm TP.HCM');
    expect(card().textContent).not.toMatch(/sao|lượt|₫|Cách bạn|Xem quán/);
  });

  it('FoodArt gets the returned key, is decorative, and never names the food for screen readers', async () => {
    const { container } = renderDecision(backend(foodDecision(BUN_BO)));
    await openBox();
    const art = card().querySelector('[data-food-art]');
    expect(art).toHaveAttribute('data-food-art', 'bun-bo');
    expect(art).toHaveAttribute('aria-hidden', 'true');
    expect(card()).toHaveAccessibleName('Bún bò');          // from the text heading only
    // the box lifts the same food's glyph out of the lid
    expect(container.querySelector('.fm-tiffin [data-food-glyph]')).toHaveAttribute('data-food-glyph', 'bun-bo');
  });

  it('"Món khác" sends no exclude first, then every food already shown', async () => {
    const decide = backend(foodDecision(PHO), foodDecision(SUSHI), foodDecision(LAU));
    renderDecision(decide);

    await openBox();
    expect(decide.mock.calls[0][0].exclude).toEqual([]);

    fireEvent.click(rerollButton());
    await advance(REROLL_RUN);
    expect(decide.mock.calls[1][0].exclude).toEqual(['pho']);

    fireEvent.click(rerollButton());
    await advance(REROLL_RUN);
    expect(decide.mock.calls[2][0].exclude).toEqual(['pho', 'sushi']);
    expect(screen.getByRole('heading', { name: 'Lẩu' })).toBeInTheDocument();
  });

  it('"Xem lại từ đầu" clears the exclude list once every food has been shown', async () => {
    const decide = backend(foodDecision(PHO), noFood('ALL_SHOWN'), foodDecision(PHO));
    renderDecision(decide);

    await openBox();
    fireEvent.click(rerollButton());
    await advance(REROLL_RUN);
    expect(visible('Bạn đã xem hết các món quanh trung tâm TP.HCM.')).toBeInTheDocument();
    expect(screen.queryByRole('article')).not.toBeInTheDocument(); // no invented food
    expect(screen.queryByRole('link', { name: /^Tìm quán/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Xem lại từ đầu' }));
    expect(decide.mock.calls[2][0].exclude).toEqual([]);
    await advance(REROLL_RUN);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
  });

  it('prevents duplicate activation while busy, without dropping focus', async () => {
    const decide = backend(foodDecision(PHO), foodDecision(SUSHI));
    renderDecision(decide);
    const button = mainButton();
    button.focus();

    fireEvent.click(button);
    fireEvent.click(button);
    await advance(TIMING.press);
    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(button).toHaveFocus();
    expect(decide).toHaveBeenCalledTimes(1);

    await advance(FULL_RUN);
    const reroll = rerollButton();
    reroll.focus();
    fireEvent.click(reroll);
    fireEvent.click(reroll); // no overlapping reroll either
    expect(decide).toHaveBeenCalledTimes(2);
    expect(reroll).toHaveFocus();                 // rerolls keep focus on "Món khác"
    expect(findLink()).toHaveAttribute('aria-disabled', 'true');   // the food is about to change
    await advance(REROLL_RUN);
    expect(reroll).toHaveFocus();
  });

  it('never lets a late (stale) response overwrite a newer decision', async () => {
    // Call 1 ignores the abort and answers after the timeout; call 2 is the retry.
    const decide = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => setTimeout(() => resolve(foodDecision(SUSHI)), TIMING.timeout + 2000)))
      .mockImplementationOnce(() => Promise.resolve(foodDecision(PHO)));
    renderDecision(decide);

    fireEvent.click(mainButton());
    await advance(TIMING.press + TIMING.timeout);
    expect(mainButton()).toHaveAccessibleName('Thử lại');

    fireEvent.click(mainButton());
    await advance(FULL_RUN);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();

    await advance(3000); // the stale answer arrives now
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Sushi' })).not.toBeInTheDocument();
  });

  it('treats a cancelled request as cancelled, not as an error', async () => {
    const canceled = Object.assign(new Error('canceled'), { name: 'CanceledError', code: 'ERR_CANCELED' });
    const { container } = renderDecision(backend(canceled));

    await openBox();
    expect(container.querySelector('.fm-tiffin')).toHaveAttribute('data-phase', 'idle');
    expect(mainButton()).toHaveAccessibleName('Mở cà mèn');
    expect(liveRegion()).toHaveTextContent('');
    expect(screen.queryByText(/Thử lại|Chưa mở được/)).not.toBeInTheDocument();
  });

  it('times out instead of simmering forever, and cleans up on unmount', async () => {
    const decide = vi.fn(({ signal }) => new Promise((_, reject) => {
      signal.addEventListener('abort', () => reject(Object.assign(new Error('canceled'), { name: 'CanceledError' })));
    }));
    const { unmount } = renderDecision(decide);

    fireEvent.click(mainButton());
    await advance(TIMING.timeout - 1);
    expect(mainButton()).toHaveAttribute('aria-busy', 'true');
    await advance(1);
    expect(decide.mock.calls[0][0].signal.aborted).toBe(true);
    expect(liveRegion()).toHaveTextContent('Cà mèn mở hơi lâu. Thử lại nhé.');

    fireEvent.click(mainButton()); // retry, then leave mid-request
    await advance(TIMING.press + 100);
    unmount();
    expect(decide.mock.calls[1][0].signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('stays functional with reduced motion and reveals as soon as the answer is in', async () => {
    mockReducedMotion(true);
    const { container } = renderDecision(backend(foodDecision(PHO)));

    fireEvent.click(mainButton());
    expect(container.querySelector('.fm-tiffin')).toHaveAttribute('data-phase', 'deciding');
    await advance(LATENCY);
    expect(screen.getByRole('heading', { name: 'Phở' })).toHaveFocus();
    expect(findLink()).toBeInTheDocument();
  });
});

// ── "Tìm quán {món}" ────────────────────────────────────────────────────────

describe('TiffinDecision — "Tìm quán"', () => {
  it('navigates to /mon/{key} of the food on show — and calls nothing', async () => {
    const decide = backend(foodDecision(PHO));
    renderDecision(decide);
    await openBox();

    expect(findLink()).toHaveAccessibleName('Tìm quán phở');
    expect(findLink()).toHaveAttribute('href', '/mon/pho');
    fireEvent.click(findLink());
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/mon\/pho$/);
    expect(decide).toHaveBeenCalledTimes(1);   // navigation only, no further decision
  });

  it('carries the scope the food was decided in: the area key, or near=1 — never coordinates', async () => {
    renderDecision(backend(foodDecision(LAU, { scope: AREA_SCOPE, restaurantCount: 2, radiusMeters: 2500 }), foodDecision(SUSHI, { scope: USER_SCOPE, restaurantCount: 4, radiusMeters: 3000, radiusWidened: true })));
    await openBox();
    expect(findLink()).toHaveAttribute('href', '/mon/lau?area=binh-thanh');
    expect(within(card()).getByText('2 quán quanh Bình Thạnh')).toBeInTheDocument();

    fireEvent.click(rerollButton());
    await advance(REROLL_RUN);
    expect(findLink()).toHaveAttribute('href', '/mon/sushi?near=1');
    expect(within(card()).getByText('4 quán trong 3 km gần bạn')).toBeInTheDocument();
    expect(findLink().getAttribute('href')).not.toMatch(/lat|lng|\d+\.\d+/);
  });
});

// ── Empty and error states (Master Specification §17) ───────────────────────

describe('TiffinDecision — empty and errors', () => {
  it('200 with no food is an empty state, not an error, and "Thử lại" works', async () => {
    const decide = backend(noFood('NO_FOOD_IN_SCOPE', AREA_SCOPE), foodDecision(PHO));
    renderDecision(decide);
    await openBox();

    expect(visible('Chưa có món nào FoodMap biết quanh Bình Thạnh.')).toBeInTheDocument();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(queryVisible('Chưa mở được cà mèn. Thử lại nhé.')).not.toBeInTheDocument();

    fireEvent.click(mainButton());
    expect(mainButton()).toHaveAccessibleName('Đang chọn…');
    await advance(FULL_RUN);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
  });

  it('400 explains and offers a clean restart', async () => {
    const decide = backend(foodDecision(PHO), new DecisionApiError({ status: 400 }), foodDecision(SUSHI));
    renderDecision(decide);
    await openBox();
    fireEvent.click(rerollButton());
    await advance(REROLL_RUN);

    expect(visible('Chưa mở được cà mèn. Thử lại nhé.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Xem lại từ đầu' }));
    expect(decide.mock.calls[2][0].exclude).toEqual([]);
  });

  it('429: waits out Retry-After with "Thử lại" disabled, and never retries on its own', async () => {
    const decide = backend(new DecisionApiError({ status: 429, retryAfterSeconds: 3 }), foodDecision(PHO));
    renderDecision(decide);
    await openBox();

    expect(liveRegion()).toHaveTextContent('Bạn mở nhanh quá, đợi 3 giây nhé.');
    expect(mainButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(mainButton());
    expect(decide).toHaveBeenCalledTimes(1);

    await advance(3000);
    expect(mainButton()).not.toHaveAttribute('aria-disabled');
    expect(visible('Giờ bạn thử lại được rồi.')).toBeInTheDocument();
    await advance(60_000);
    expect(decide).toHaveBeenCalledTimes(1); // no automatic retry loop

    fireEvent.click(mainButton()); // the user decides when to retry
    await advance(FULL_RUN);
    expect(decide).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
  });

  it('429 without a readable Retry-After still gives clear advice', async () => {
    renderDecision(backend(new DecisionApiError({ status: 429 })));
    await openBox();
    expect(liveRegion()).toHaveTextContent('Bạn mở nhanh quá, đợi một chút nhé.');
    expect(mainButton()).not.toHaveAttribute('aria-disabled');
  });

  it('5xx and network failures show a retry state — never "no food", never raw errors', async () => {
    const decide = backend(
      new DecisionApiError({ status: 503, serverMessage: 'NullPointerException at FoodDecisionService' }),
      new DecisionApiError({ status: null }),
      foodDecision(PHO),
    );
    renderDecision(decide);

    await openBox();
    expect(visible('Chưa mở được cà mèn. Thử lại nhé.')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/NullPointer|Exception|503/);
    expect(queryVisible(/Chưa có món nào/)).not.toBeInTheDocument();

    fireEvent.click(mainButton());
    await advance(FULL_RUN);
    expect(visible('Chưa mở được cà mèn. Thử lại nhé.')).toBeInTheDocument();

    fireEvent.click(mainButton());
    await advance(FULL_RUN);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
  });

  it('a reroll failure keeps the current food on screen', async () => {
    renderDecision(backend(foodDecision(PHO), new DecisionApiError({ status: 503 })));
    await openBox();
    fireEvent.click(rerollButton());
    await advance(REROLL_RUN);

    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
    expect(visible('Chưa mở được cà mèn. Thử lại nhé.')).toBeInTheDocument();
    expect(rerollButton()).toHaveAccessibleName('Món khác');
    expect(findLink()).toHaveAttribute('href', '/mon/pho');
  });
});
