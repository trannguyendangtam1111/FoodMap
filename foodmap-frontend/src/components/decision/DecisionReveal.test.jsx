import { describe, expect, it } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import DecisionReveal, { reasonLabel } from './DecisionReveal';
import { setLang } from '../../i18n';
import { BANH_MI, BUN_DAU, NYONYA, cityCentreDecision, userDecision } from '../../test/decisionFixtures';

// The restaurant card (V1). The homepage cà mèn now reveals a FOOD (Part 7);
// this card stays for the food page's restaurant suggestion, so its honesty
// rules keep their tests — moved here unchanged from TiffinDecision.test.

const renderCard = (decision) => render(<MemoryRouter><DecisionReveal decision={decision} /></MemoryRouter>);
const card = () => screen.getByRole('article');

describe('DecisionReveal — backend result', () => {
  it('fallbackLevel 0 with the user location: distance from the user, no fallback note', () => {
    renderCard(userDecision(BANH_MI, { distanceMeters: 630 }));
    const c = within(card());
    expect(c.getByText('630 m')).toBeInTheDocument();
    expect(c.getByText('Cách bạn', { exact: false })).toBeInTheDocument(); // sr-only prefix
    expect(c.getByLabelText('Đánh giá 4,7 trên 5, 3 lượt')).toBeInTheDocument();
    expect(c.queryByText(/chưa chia sẻ vị trí|tìm rộng hơn/)).not.toBeInTheDocument();
    expect(c.getByRole('link', { name: /Xem quán/ })).toHaveAttribute('href', '/restaurants/4');
  });

  it('CITY_CENTER never claims "near you" and shows no distance', () => {
    // Even a stray distanceMeters must not become a claim about the user.
    renderCard(cityCentreDecision(NYONYA, { distanceMeters: 500 }));
    const c = within(card());
    expect(card().textContent).not.toMatch(/Cách bạn|gần bạn|500 m/i);
    expect(c.getByText('Quanh trung tâm TP.HCM')).toBeInTheDocument(); // backend AREA reason
    expect(c.getByText('Bạn chưa chia sẻ vị trí, nên FoodMap gợi ý theo khu vực.')).toBeInTheDocument();
  });

  it('shows "dưới 10 m" for distances under 10 metres', () => {
    renderCard(userDecision(BUN_DAU, { distanceMeters: 4, reasons: [{ code: 'DISTANCE', label: 'Cách bạn dưới 10 m' }] }));
    expect(within(card()).getByText('dưới 10 m')).toBeInTheDocument();
    expect(card().textContent).not.toMatch(/(?<!dưới )\b10 m/);
  });

  it('renders backend reason labels verbatim, without repeating what the facts line shows', () => {
    const reasons = [
      { code: 'DISTANCE', label: 'Cách bạn 630 m' },
      { code: 'MOOD_MENU', label: 'Có món nước: Bún bò Huế đặc biệt' },
      { code: 'RATING', label: 'Được đánh giá 4,7 sao (3 lượt)' },
    ];
    renderCard(userDecision(BANH_MI, { reasons, mood: 'mon-nuoc', moodMatched: true }));
    const list = within(card()).getByRole('list', { name: 'Lý do gợi ý' });
    expect(within(list).getAllByRole('listitem').map((li) => li.textContent))
      .toEqual(['Có món nước: Bún bò Huế đặc biệt']); // distance + rating are in the facts line
  });

  it('lets long reason chips wrap instead of overflowing', () => {
    const long = 'Có món nước: Bún bò Huế đặc biệt giò heo chả cua huyết bò tái nạm gầu';
    renderCard(userDecision(BANH_MI, { reasons: [{ code: 'MOOD_MENU', label: long }] }));
    const chip = within(card()).getByText(long).closest('span.inline-flex');
    expect(chip).toHaveClass('whitespace-normal', 'max-w-full');
    expect(chip).not.toHaveClass('whitespace-nowrap');
  });

  it('explains a widened radius (fallbackLevel 2) honestly', () => {
    renderCard(userDecision(BANH_MI, { distanceMeters: 2400, fallbackLevel: 2, radiusMeters: 3000 }));
    expect(within(card()).getByText('Gần bạn ít quán, nên FoodMap đã tìm rộng hơn, trong khoảng 3,0 km.')).toBeInTheDocument();
    expect(within(card()).getByText('2,4 km')).toBeInTheDocument();
  });

  it('never renders fake values for missing optional data', () => {
    renderCard(cityCentreDecision({ id: 82, name: BUN_DAU.name, avgRating: 0, reviewCount: 0 }, { reasons: [] }));
    expect(card().textContent).not.toMatch(/0,0|★|₫|sao|lượt|Cách bạn|\d\s?k?m(?!\p{L})|Đang mở|Đã đóng|Chưa có đánh giá/u);
    expect(within(card()).queryByRole('list', { name: 'Lý do gợi ý' })).not.toBeInTheDocument();
  });
});

// ── EN / VI: reason chips follow the UI language ─────────────────────────────
// Vietnamese shows the server's labels verbatim (above); English rebuilds them
// from the stable reason codes and the decision's structured fields.

describe('DecisionReveal — reason chips in English', () => {
  const chipTexts = () => within(within(card()).getByRole('list', { name: /Why this suggestion|Lý do gợi ý/ }))
    .getAllByRole('listitem').map((li) => li.textContent);

  it('food page suggestion: AREA and FOOD_NAME in English, switching live back to the server text', () => {
    act(() => setLang('en'));
    renderCard(cityCentreDecision(NYONYA, {
      food: { key: 'bun-bo', label: 'Bún bò' },
      reasons: [{ code: 'AREA', label: 'Quanh trung tâm TP.HCM' }, { code: 'FOOD_NAME', label: 'Tên quán cho thấy có bún bò' }],
    }));
    expect(chipTexts()).toEqual(['Around central HCMC', 'Name suggests Bún bò']);
    act(() => setLang('vi'));
    expect(chipTexts()).toEqual(['Quanh trung tâm TP.HCM', 'Tên quán cho thấy có bún bò']);
  });

  it('AREA names the chosen area; FOOD_MENU keeps the menu dish name as written', () => {
    act(() => setLang('en'));
    renderCard(cityCentreDecision(NYONYA, {
      locationSource: 'AREA', area: { key: 'quan-1', label: 'Quận 1' },
      reasons: [{ code: 'AREA', label: 'Quanh Quận 1' }, { code: 'FOOD_MENU', label: 'Thực đơn có Bánh Mì Thịt Nướng' }],
    }));
    expect(chipTexts()).toEqual(['Around Quận 1', 'Menu has Bánh Mì Thịt Nướng']);
  });

  it('keeps the server label when the code is unknown or the data to rebuild it is missing', () => {
    act(() => setLang('en'));
    renderCard(cityCentreDecision(NYONYA, {
      reasons: [
        { code: 'MOOD_MENU', label: 'Có món nước: Bún bò Huế' },
        { code: 'FOOD_NAME', label: 'Tên quán cho thấy có phở' },        // no `food` on the decision
        { code: 'FOOD_MENU', label: 'Món lạ' },                         // not the server's template
      ],
    }));
    expect(chipTexts()).toEqual(['Có món nước: Bún bò Huế', 'Tên quán cho thấy có phở', 'Món lạ']);
  });

  it('DISTANCE and RATING are rebuilt from the measured distance and the restaurant\'s own rating', () => {
    act(() => setLang('en'));
    const decision = { restaurant: { avgRating: 4.7, reviewCount: 3 }, distanceMeters: 630 };
    expect(reasonLabel({ code: 'DISTANCE', label: 'Cách bạn 630 m' }, decision)).toBe('630 m from you');
    expect(reasonLabel({ code: 'RATING', label: 'Được đánh giá 4,7 sao (3 lượt)' }, decision)).toBe('Rated 4.7 stars (3 reviews)');
    expect(reasonLabel({ code: 'RATING', label: 'x' }, { restaurant: { avgRating: 5, reviewCount: 1 } })).toBe('Rated 5.0 stars (1 review)');
    act(() => setLang('vi'));
    expect(reasonLabel({ code: 'DISTANCE', label: 'Cách bạn 630 m' }, decision)).toBe('Cách bạn 630 m');
  });
});

describe('DecisionReveal — FOOD_NAME names the food in the UI language', () => {
  it('a generic food reads "Coffee" in English; VI keeps the server\'s chip', () => {
    act(() => setLang('en'));
    renderCard(cityCentreDecision(NYONYA, {
      food: { key: 'ca-phe', label: 'Cà phê' },
      reasons: [{ code: 'FOOD_NAME', label: 'Tên quán cho thấy có cà phê' }],
    }));
    const chips = () => within(within(card()).getByRole('list', { name: /Why this suggestion|Lý do gợi ý/ })).getAllByRole('listitem').map((li) => li.textContent);
    expect(chips()).toEqual(['Name suggests Coffee']);
    act(() => setLang('vi'));
    expect(chips()).toEqual(['Tên quán cho thấy có cà phê']);
  });
});
