import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import { describeAuthError } from '../utils/authErrors';
import { useI18n } from '../i18n';

export default function LoginPage() {
  const { login } = useAuth();
  const { t } = useI18n();
  const navigate  = useNavigate();
  const location  = useLocation();
  // Default destination after login is the map (it used to live at "/")
  const from      = location.state?.from?.pathname ?? '/map';

  const [form, setForm]           = useState({ email: '', password: '' });
  const [errors, setErrors]       = useState({});
  const [apiError, setApiError]   = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const set = (field) => (e) => setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const validate = () => {
    const next = {};
    if (!form.email.trim()) next.email = t('auth.err.emailRequired');
    if (!form.password)     next.password = t('auth.err.passwordRequired');
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!validate()) return;
    setApiError('');
    setIsLoading(true);
    try {
      await login(form.email.trim(), form.password);
      navigate(from, { replace: true });
    } catch (err) {
      const { fieldErrors, message } = describeAuthError(err, 'login');
      if (fieldErrors) setErrors(fieldErrors);
      else setApiError(message);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-cream px-4">
      {/* Logo */}
      <Link to="/" className="mb-8 rounded-tag font-display text-4xl font-black text-brand-600">FoodMap</Link>

      <div className="w-full max-w-sm rounded-panel border border-line bg-surface p-8 shadow-e1">
        <h1 className="mb-6 text-h2 text-ink">{t('auth.welcomeBack')}</h1>

        <form onSubmit={handleSubmit} noValidate className="space-y-4">
          <Input
            label={t('auth.email')}
            type="email"
            placeholder={t('auth.emailPlaceholder')}
            autoComplete="email"
            value={form.email}
            onChange={set('email')}
            error={errors.email}
          />
          <Input
            label={t('auth.password')}
            type="password"
            placeholder="••••••••"
            autoComplete="current-password"
            value={form.password}
            onChange={set('password')}
            error={errors.password}
          />

          {apiError && (
            <p className="rounded-field bg-danger-tint px-3 py-2 text-sm text-danger-ink">{apiError}</p>
          )}

          <Button type="submit" isLoading={isLoading} className="w-full mt-2">
            {t('auth.login')}
          </Button>
        </form>

        <p className="mt-6 text-center text-sm text-ink-muted">
          {t('auth.noAccount')}{' '}
          <Link to="/register" className="rounded-tag font-semibold text-brand-700 hover:text-brand-800">
            {t('auth.register')}
          </Link>
        </p>
      </div>
    </div>
  );
}
