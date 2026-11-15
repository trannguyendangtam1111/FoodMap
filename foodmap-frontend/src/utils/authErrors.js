/**
 * Turns a failed login/register request into localized UI copy (EN/VI).
 *
 * Server text is never shown as-is: backend messages are English and a 5xx
 * carries only a generic "An unexpected error occurred.". Every outcome maps
 * to fixed copy below; unknown cases get a safe fallback.
 *
 * Plain ESM, no React — unit-tested on its own.
 */
import { t } from '../i18n';

// Getters: each read returns the copy in the current UI language.
export const AUTH_COPY = Object.freeze({
  get serverDown() { return t('auth.err.serverDown'); },
  get emailTaken() { return t('auth.err.emailTaken'); },
  get badCredentials() { return t('auth.err.badCredentials'); },
  get registerFallback() { return t('auth.err.registerFallback'); },
  get loginFallback() { return t('auth.err.loginFallback'); },
});

// Backend Bean Validation messages (RegisterRequest / LoginRequest) → copy.
// `default` covers any other constraint on the same field (e.g. @Size(max)).
// Values are i18n keys, resolved when the error is described.
const FIELD_COPY = {
  email: {
    'Email must not be blank': 'auth.err.emailRequired',
    'Email must be a valid address': 'auth.err.emailInvalid',
    default: 'auth.err.emailInvalid',
  },
  password: {
    'Password must not be blank': 'auth.err.passwordRequired',
    'Password must be between 8 and 100 characters': 'auth.err.passwordLength',
    default: 'auth.err.passwordInvalid',
  },
  displayName: {
    'Display name must not be blank': 'auth.err.displayNameRequired',
    'Display name must be between 2 and 100 characters': 'auth.err.displayNameLength',
    default: 'auth.err.displayNameInvalid',
  },
};

function mapFieldErrors(fieldErrors) {
  if (!fieldErrors || typeof fieldErrors !== 'object') return null;
  const mapped = {};
  for (const [field, serverMessage] of Object.entries(fieldErrors)) {
    const copy = FIELD_COPY[field];
    if (copy) mapped[field] = t(copy[serverMessage] ?? copy.default);
  }
  return Object.keys(mapped).length ? mapped : null;
}

/**
 * @param {unknown} error   the rejected Axios error
 * @param {'login'|'register'} action
 * @returns {{ fieldErrors: Record<string,string>|null, message: string|null }}
 *          exactly one of the two is set
 */
export function describeAuthError(error, action) {
  const fallback = action === 'login' ? AUTH_COPY.loginFallback : AUTH_COPY.registerFallback;
  const status = error?.response?.status;

  if (!status || status >= 500) return { fieldErrors: null, message: AUTH_COPY.serverDown };

  if (status === 400) {
    const fieldErrors = mapFieldErrors(error.response.data?.fieldErrors);
    return fieldErrors ? { fieldErrors, message: null } : { fieldErrors: null, message: fallback };
  }
  if (status === 401 && action === 'login') return { fieldErrors: null, message: AUTH_COPY.badCredentials };
  if (status === 409 && action === 'register') return { fieldErrors: null, message: AUTH_COPY.emailTaken };

  return { fieldErrors: null, message: fallback };
}
