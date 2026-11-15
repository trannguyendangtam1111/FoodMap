import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import { describeAuthError } from '../utils/authErrors';
import { useI18n } from '../i18n';

export default function RegisterPage() {
  const { register } = useAuth();
  const { t } = useI18n();
  const navigate     = useNavigate();

  const [form, setForm]           = useState({ displayName: '', email: '', password: '', confirmPassword: '' });
  const [errors, setErrors]       = useState({});
  const [apiError, setApiError]   = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const set = (field) => (e) => setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const validate = () => {
    const next = {};
    if (!form.displayName.trim())  next.displayName = t('auth.err.displayNameRequired');
    if (!form.email.trim())        next.email = t('auth.err.emailRequired');
    else if (!/\S+@\S+\.\S+/.test(form.email)) next.email = t('auth.err.emailInvalid');
    if (form.password.length < 8)  next.password = t('auth.err.passwordMin');
    if (form.password !== form.confirmPassword) next.confirmPassword = t('auth.err.passwordMismatch');
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!validate()) return;
    setApiError('');
    setIsLoading(true);
    try {
      await register({
        displayName: form.displayName.trim(),
        email: form.email.trim(),
        password: form.password,
      });
      navigate('/map', { replace: true });  // the map used to live at "/"
    } catch (err) {
      const { fieldErrors, message } = describeAuthError(err, 'register');
      if (fieldErrors) setErrors(fieldErrors);
      else setApiError(message);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-cream px-4">
      <Link to="/" className="mb-8 rounded-tag font-display text-4xl font-black text-brand-600">FoodMap</Link>

      <div className="w-full max-w-sm rounded-panel border border-line bg-surface p-8 shadow-e1">
        <h1 className="mb-6 text-h2 text-ink">{t('auth.createAccount')}</h1>

        <form onSubmit={handleSubmit} noValidate className="space-y-4">
          <Input
            label={t('auth.displayName')}
            type="text"
            placeholder={t('auth.displayNamePlaceholder')}
            autoComplete="name"
            value={form.displayName}
            onChange={set('displayName')}
            error={errors.displayName}
          />
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
            placeholder={t('auth.passwordMinPlaceholder')}
            autoComplete="new-password"
            value={form.password}
            onChange={set('password')}
            error={errors.password}
          />
          <Input
            label={t('auth.confirmPassword')}
            type="password"
            placeholder={t('auth.confirmPasswordPlaceholder')}
            autoComplete="new-password"
            value={form.confirmPassword}
            onChange={set('confirmPassword')}
            error={errors.confirmPassword}
          />

          {apiError && (
            <p className="rounded-field bg-danger-tint px-3 py-2 text-sm text-danger-ink">{apiError}</p>
          )}

          <Button type="submit" isLoading={isLoading} className="w-full mt-2">
            {t('auth.createAccount')}
          </Button>
        </form>

        <p className="mt-6 text-center text-sm text-ink-muted">
          {t('auth.haveAccount')}{' '}
          <Link to="/login" className="rounded-tag font-semibold text-brand-700 hover:text-brand-800">
            {t('auth.login')}
          </Link>
        </p>
      </div>
    </div>
  );
}
