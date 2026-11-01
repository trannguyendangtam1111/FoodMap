import { describe, expect, it } from 'vitest';
import { AUTH_COPY, describeAuthError } from './authErrors';

const httpError = (status, data = {}) => ({ response: { status, data } });
const GENERIC_500 = 'An unexpected error occurred.';

describe('describeAuthError', () => {
  it('maps 5xx to the Vietnamese outage copy, never the server text', () => {
    for (const action of ['login', 'register']) {
      const out = describeAuthError(httpError(500, { message: GENERIC_500 }), action);
      expect(out).toEqual({ fieldErrors: null, message: AUTH_COPY.serverDown });
      expect(JSON.stringify(out)).not.toContain(GENERIC_500);
    }
    expect(describeAuthError(httpError(503), 'login').message).toBe(AUTH_COPY.serverDown);
  });

  it('maps network errors (no response) to the outage copy', () => {
    expect(describeAuthError({ message: 'Network Error' }, 'register').message).toBe(AUTH_COPY.serverDown);
    expect(describeAuthError(undefined, 'login').message).toBe(AUTH_COPY.serverDown);
  });

  it('maps register 409 to "email already registered"', () => {
    const out = describeAuthError(httpError(409, { message: 'An account with this email already exists.' }), 'register');
    expect(out.message).toBe('Email này đã được đăng ký.');
  });

  it('maps login 401 to "wrong email or password"', () => {
    const out = describeAuthError(httpError(401, { message: 'Email or password is incorrect.' }), 'login');
    expect(out.message).toBe('Email hoặc mật khẩu chưa đúng.');
  });

  it('maps known 400 field errors to Vietnamese per field', () => {
    const out = describeAuthError(httpError(400, {
      message: 'Request validation failed',
      fieldErrors: {
        email: 'Email must be a valid address',
        password: 'Password must be between 8 and 100 characters',
        displayName: 'Display name must not be blank',
      },
    }), 'register');
    expect(out).toEqual({
      fieldErrors: {
        email: 'Email không hợp lệ.',
        password: 'Mật khẩu cần từ 8 đến 100 ký tự.',
        displayName: 'Vui lòng nhập tên hiển thị.',
      },
      message: null,
    });
  });

  it('uses a per-field Vietnamese default for unknown constraint messages', () => {
    const out = describeAuthError(httpError(400, { fieldErrors: { email: 'size must be between 0 and 255' } }), 'register');
    expect(out.fieldErrors).toEqual({ email: 'Email không hợp lệ.' });
  });

  it('falls back safely for a 400 without known fields, and for other statuses', () => {
    expect(describeAuthError(httpError(400, { message: 'Bad' }), 'register').message).toBe(AUTH_COPY.registerFallback);
    expect(describeAuthError(httpError(400, { fieldErrors: { other: 'x' } }), 'login').message).toBe(AUTH_COPY.loginFallback);
    expect(describeAuthError(httpError(429, { message: 'Too many' }), 'login').message).toBe(AUTH_COPY.loginFallback);
    // 401 on register / 409 on login are not the documented cases → fallback
    expect(describeAuthError(httpError(401), 'register').message).toBe(AUTH_COPY.registerFallback);
    expect(describeAuthError(httpError(409), 'login').message).toBe(AUTH_COPY.loginFallback);
  });
});
