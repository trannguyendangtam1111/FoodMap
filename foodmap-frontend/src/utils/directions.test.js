import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { directionsUrl, hasValidCoordinates } from './directions';
import { getLocationState, rememberLocation, resetLocationScope } from '../hooks/useLocationScope';

const PHO_HOA = { id: 1, name: 'Phở Hòa Pasteur', latitude: 10.7762, longitude: 106.6892 };
const params = (url) => new URL(url).searchParams;

afterEach(() => resetLocationScope());

describe('directionsUrl — the restaurant\'s coordinates, nothing else', () => {
  it('valid coordinates: a Google Maps directions URL to them', () => {
    expect(directionsUrl(PHO_HOA)).toBe('https://www.google.com/maps/dir/?api=1&destination=10.7762%2C106.6892');
  });

  it('uses the stored coordinates exactly (no rounding)', () => {
    const r = { latitude: 10.777030818000071, longitude: 106.69978307400004 };
    expect(params(directionsUrl(r)).get('destination')).toBe('10.777030818000071,106.69978307400004');
  });

  it('carries only api and destination: no origin, no other parameter', () => {
    expect([...params(directionsUrl(PHO_HOA)).keys()]).toEqual(['api', 'destination']);
  });

  it('never contains the user\'s location, even when this tab has one', () => {
    const user = { lat: 10.80123, lng: 106.65432 };
    rememberLocation(user);
    expect(getLocationState().location).toEqual(user);   // the store does hold it …
    const url = directionsUrl(PHO_HOA);
    expect(url).not.toContain('10.80123');                 // … and the URL never sees it
    expect(url).not.toContain('106.65432');
    expect(params(url).has('origin')).toBe(false);
  });

  it.each([
    ['missing latitude', { longitude: 106.6892 }],
    ['missing longitude', { latitude: 10.7762 }],
    ['null latitude', { latitude: null, longitude: 106.6892 }],
    ['null longitude', { latitude: 10.7762, longitude: null }],
    ['latitude above 90', { latitude: 90.0001, longitude: 106.6892 }],
    ['latitude below -90', { latitude: -91, longitude: 106.6892 }],
    ['longitude above 180', { latitude: 10.7762, longitude: 180.5 }],
    ['longitude below -180', { latitude: 10.7762, longitude: -181 }],
    ['NaN latitude', { latitude: Number.NaN, longitude: 106.6892 }],
    ['infinite longitude', { latitude: 10.7762, longitude: Number.POSITIVE_INFINITY }],
    ['latitude as text', { latitude: '10.7762', longitude: 106.6892 }],
    ['no restaurant', null],
  ])('%s → no URL', (_, restaurant) => {
    expect(hasValidCoordinates(restaurant)).toBe(false);
    expect(directionsUrl(restaurant)).toBeNull();
  });

  it('the range limits themselves are valid', () => {
    expect(directionsUrl({ latitude: 90, longitude: -180 })).toBe('https://www.google.com/maps/dir/?api=1&destination=90%2C-180');
  });

  it('static guard: the directions code reads no location source', () => {
    for (const file of ['utils/directions.js', 'components/restaurant/DirectionsLink.jsx']) {
      const source = readFileSync(resolve(__dirname, '..', file), 'utf8');
      expect(source).not.toMatch(/useLocationScope|getLocationState|navigator\.geolocation|requestUserLocation|utils\/location/);
    }
  });
});
