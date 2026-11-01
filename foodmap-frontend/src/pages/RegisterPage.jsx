import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import { describeAuthError } from '../utils/authErrors';

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate     = useNavigate();

  const [form, setForm]           = useState({ displayName: '', email: '', password: '', confirmPassword: '' });
  const [errors, setErrors]       = useState({});
  const [apiError, setApiError]   = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const set = (field) => (e) => setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const validate = () => {
    const next = {};
    if (!form.displayName.trim())  next.displayName = 'Vui lòng nhập tên hiển thị.';
    if (!form.email.trim())        next.email = 'Vui lòng nhập email.';
    else if (!/\S+@\S+\.\S+/.test(form.email)) next.email = 'Email không hợp lệ.';
    if (form.password.length < 8)  next.password = 'Mật khẩu cần ít nhất 8 ký tự.';
    if (form.password !== form.confirmPassword) next.confirmPassword = 'Mật khẩu nhập lại không khớp.';
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
        <h1 className="mb-6 text-h2 text-ink">Tạo tài khoản</h1>

        <form onSubmit={handleSubmit} noValidate className="space-y-4">
          <Input
            label="Tên hiển thị"
            type="text"
            placeholder="Nguyễn Văn A"
            autoComplete="name"
            value={form.displayName}
            onChange={set('displayName')}
            error={errors.displayName}
          />
          <Input
            label="Email"
            type="email"
            placeholder="ten@email.com"
            autoComplete="email"
            value={form.email}
            onChange={set('email')}
            error={errors.email}
          />
          <Input
            label="Mật khẩu"
            type="password"
            placeholder="Tối thiểu 8 ký tự"
            autoComplete="new-password"
            value={form.password}
            onChange={set('password')}
            error={errors.password}
          />
          <Input
            label="Nhập lại mật khẩu"
            type="password"
            placeholder="Nhập lại mật khẩu của bạn"
            autoComplete="new-password"
            value={form.confirmPassword}
            onChange={set('confirmPassword')}
            error={errors.confirmPassword}
          />

          {apiError && (
            <p className="rounded-field bg-danger-tint px-3 py-2 text-sm text-danger-ink">{apiError}</p>
          )}

          <Button type="submit" isLoading={isLoading} className="w-full mt-2">
            Tạo tài khoản
          </Button>
        </form>

        <p className="mt-6 text-center text-sm text-ink-muted">
          Đã có tài khoản?{' '}
          <Link to="/login" className="rounded-tag font-semibold text-brand-700 hover:text-brand-800">
            Đăng nhập
          </Link>
        </p>
      </div>
    </div>
  );
}
