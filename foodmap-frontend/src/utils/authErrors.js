/**
 * Turns a failed login/register request into Vietnamese UI copy.
 *
 * Server text is never shown as-is: backend messages are English and a 5xx
 * carries only a generic "An unexpected error occurred.". Every outcome maps
 * to fixed copy below; unknown cases get a safe fallback.
 *
 * Plain ESM, no React — unit-tested on its own.
 */

export const AUTH_COPY = Object.freeze({
  serverDown: 'Hệ thống đang gặp sự cố, bạn thử lại sau nhé.',
  emailTaken: 'Email này đã được đăng ký.',
  badCredentials: 'Email hoặc mật khẩu chưa đúng.',
  registerFallback: 'Đăng ký chưa thành công. Bạn kiểm tra lại thông tin nhé.',
  loginFallback: 'Đăng nhập chưa thành công. Bạn kiểm tra lại thông tin nhé.',
});

// Backend Bean Validation messages (RegisterRequest / LoginRequest) → copy.
// `default` covers any other constraint on the same field (e.g. @Size(max)).
const FIELD_COPY = {
  email: {
    'Email must not be blank': 'Vui lòng nhập email.',
    'Email must be a valid address': 'Email không hợp lệ.',
    default: 'Email không hợp lệ.',
  },
  password: {
    'Password must not be blank': 'Vui lòng nhập mật khẩu.',
    'Password must be between 8 and 100 characters': 'Mật khẩu cần từ 8 đến 100 ký tự.',
    default: 'Mật khẩu không hợp lệ.',
  },
  displayName: {
    'Display name must not be blank': 'Vui lòng nhập tên hiển thị.',
    'Display name must be between 2 and 100 characters': 'Tên hiển thị cần từ 2 đến 100 ký tự.',
    default: 'Tên hiển thị không hợp lệ.',
  },
};

function mapFieldErrors(fieldErrors) {
  if (!fieldErrors || typeof fieldErrors !== 'object') return null;
  const mapped = {};
  for (const [field, serverMessage] of Object.entries(fieldErrors)) {
    const copy = FIELD_COPY[field];
    if (copy) mapped[field] = copy[serverMessage] ?? copy.default;
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
