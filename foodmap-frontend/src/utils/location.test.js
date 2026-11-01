import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { requestUserLocation } from './location';
import { formatDistance } from './ranking';

function stubGeo({ permission = 'prompt', respond } = {}) {
  const getCurrentPosition = vi.fn((ok, fail) => respond?.(ok, fail));
  vi.stubGlobal('navigator', {
    ...navigator,
    geolocation: { getCurrentPosition },
    permissions: permission ? { query: vi.fn(async () => ({ state: permission })) } : undefined,
  });
  return getCurrentPosition;
}

const allow = (lat = 10.7801, lng = 106.6995) => (ok) => ok({ coords: { latitude: lat, longitude: lng } });
const deny = () => (_, fail) => fail({ code: 1, message: 'User denied Geolocation' });

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('requestUserLocation', () => {
  it('resolves the position when allowed', async () => {
    stubGeo({ respond: allow() });
    await expect(requestUserLocation()).resolves.toEqual({ lat: 10.7801, lng: 106.6995 });
  });

  it('resolves null when the user denies', async () => {
    stubGeo({ respond: deny() });
    await expect(requestUserLocation()).resolves.toBeNull();
  });

  it('does not ask at all when permission is already denied', async () => {
    const ask = stubGeo({ permission: 'denied', respond: allow() });
    await expect(requestUserLocation()).resolves.toBeNull();
    expect(ask).not.toHaveBeenCalled();
  });

  it('gives up (null) when the prompt is left unanswered', async () => {
    stubGeo({ respond: () => {} }); // never answers
    const promise = requestUserLocation({ promptTimeoutMs: 15_000 });
    await vi.advanceTimersByTimeAsync(15_000);
    await expect(promise).resolves.toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('resolves null without geolocation support, and on abort', async () => {
    vi.stubGlobal('navigator', { ...navigator, geolocation: undefined });
    await expect(requestUserLocation()).resolves.toBeNull();

    stubGeo({ respond: () => {} });
    const controller = new AbortController();
    const promise = requestUserLocation({ signal: controller.signal });
    await vi.advanceTimersByTimeAsync(0);
    controller.abort();
    await expect(promise).resolves.toBeNull();
  });
});

describe('formatDistance (Vietnamese)', () => {
  it('never rounds under 10 m up to "10 m"', () => {
    expect(formatDistance(0)).toBe('dưới 10 m');
    expect(formatDistance(0.004)).toBe('dưới 10 m');
    expect(formatDistance(0.0099)).toBe('dưới 10 m');
  });

  it('rounds metres to 10 m and switches to km at 1 000 m', () => {
    expect(formatDistance(0.01)).toBe('10 m');
    expect(formatDistance(0.634)).toBe('630 m');
    expect(formatDistance(0.996)).toBe('1,0 km');
    expect(formatDistance(1.234)).toBe('1,2 km');
    expect(formatDistance(null)).toBeNull();
  });
});
