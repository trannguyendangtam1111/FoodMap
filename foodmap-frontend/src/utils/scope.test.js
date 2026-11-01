import { describe, expect, it } from 'vitest';
import { CITY_CENTRE_SCOPE, inSentence, scopeKey, scopeLabel, scopeSearch, toScopeParams } from './scope';

const USER = { mode: 'USER', location: { lat: 10.772512, lng: 106.698049 } };
const AREA = { mode: 'AREA', areaKey: 'binh-thanh', areaLabel: 'Bình Thạnh' };

describe('scope → request params (the only mapping)', () => {
  it('USER → lat, lng; AREA → area; CITY_CENTER → nothing; never a radius', () => {
    expect(toScopeParams(USER)).toEqual({ lat: 10.772512, lng: 106.698049 });
    expect(toScopeParams(AREA)).toEqual({ area: 'binh-thanh' });
    expect(toScopeParams(CITY_CENTRE_SCOPE)).toEqual({});
    expect(toScopeParams(undefined)).toEqual({});
  });

  it('an incomplete scope sends nothing rather than half a location', () => {
    expect(toScopeParams({ mode: 'USER', location: { lat: 10.7 } })).toEqual({});
    expect(toScopeParams({ mode: 'USER', location: { lat: 95, lng: 106.7 } })).toEqual({});
    expect(toScopeParams({ mode: 'AREA' })).toEqual({});
  });
});

describe('scope → page URL', () => {
  it('carries the area key or near=1 — never coordinates', () => {
    expect(scopeSearch(AREA)).toBe('?area=binh-thanh');
    expect(scopeSearch(USER)).toBe('?near=1');
    expect(scopeSearch(CITY_CENTRE_SCOPE)).toBe('');
    expect(scopeSearch(USER)).not.toMatch(/10\.77|106\.69|lat|lng/);
  });
});

describe('scope key and labels', () => {
  it('rounds coordinates to 3 decimals for the in-memory cache key', () => {
    expect(scopeKey(USER)).toBe('USER:10.773,106.698');
    expect(scopeKey(AREA)).toBe('AREA:binh-thanh');
    expect(scopeKey(CITY_CENTRE_SCOPE)).toBe('CITY_CENTER');
  });

  it('phrases', () => {
    expect(scopeLabel(USER)).toBe('Gần bạn');
    expect(scopeLabel(AREA)).toBe('Quanh Bình Thạnh');
    expect(scopeLabel(CITY_CENTRE_SCOPE)).toBe('Quanh trung tâm TP.HCM');
    expect(inSentence('Quanh Quận 1')).toBe('quanh Quận 1');
    expect(inSentence('Gần bạn')).toBe('gần bạn');
  });
});
