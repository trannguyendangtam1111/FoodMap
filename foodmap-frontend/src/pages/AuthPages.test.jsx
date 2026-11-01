import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import RegisterPage from './RegisterPage';
import LoginPage from './LoginPage';

const auth = { register: vi.fn(), login: vi.fn() };
vi.mock('../hooks/useAuth', () => ({ useAuth: () => auth }));

const reject = (status, data) => Promise.reject({ response: { status, data } });

function fillRegister() {
  fireEvent.change(screen.getByLabelText('Tên hiển thị'), { target: { value: 'Người Thử' } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'a@example.test' } });
  fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'password-123' } });
  fireEvent.change(screen.getByLabelText('Nhập lại mật khẩu'), { target: { value: 'password-123' } });
  fireEvent.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
}

function fillLogin() {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'a@example.test' } });
  fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'wrong-password' } });
  fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
}

beforeEach(() => {
  auth.register.mockReset();
  auth.login.mockReset();
});

describe('RegisterPage errors', () => {
  it('shows Vietnamese outage copy for a 500 and never the raw server text', async () => {
    auth.register.mockImplementation(() => reject(500, { message: 'An unexpected error occurred.' }));
    render(<MemoryRouter><RegisterPage /></MemoryRouter>);
    fillRegister();
    expect(await screen.findByText('Hệ thống đang gặp sự cố, bạn thử lại sau nhé.')).toBeInTheDocument();
    expect(screen.queryByText(/unexpected error/i)).not.toBeInTheDocument();
  });

  it('shows the duplicate-email copy for a 409', async () => {
    auth.register.mockImplementation(() => reject(409, { message: 'An account with this email already exists.' }));
    render(<MemoryRouter><RegisterPage /></MemoryRouter>);
    fillRegister();
    expect(await screen.findByText('Email này đã được đăng ký.')).toBeInTheDocument();
  });

  it('shows Vietnamese field errors for a 400', async () => {
    auth.register.mockImplementation(() => reject(400, {
      message: 'Request validation failed',
      fieldErrors: { email: 'Email must be a valid address' },
    }));
    render(<MemoryRouter><RegisterPage /></MemoryRouter>);
    fillRegister();
    expect(await screen.findByText('Email không hợp lệ.')).toBeInTheDocument();
    expect(screen.queryByText(/must be a valid/)).not.toBeInTheDocument();
  });
});

describe('LoginPage errors', () => {
  it('shows the wrong-credentials copy for a 401', async () => {
    auth.login.mockImplementation(() => reject(401, { message: 'Email or password is incorrect.' }));
    render(<MemoryRouter><LoginPage /></MemoryRouter>);
    fillLogin();
    expect(await screen.findByText('Email hoặc mật khẩu chưa đúng.')).toBeInTheDocument();
    expect(screen.queryByText(/incorrect/)).not.toBeInTheDocument();
  });

  it('shows Vietnamese outage copy for a network failure', async () => {
    auth.login.mockImplementation(() => Promise.reject(new Error('Network Error')));
    render(<MemoryRouter><LoginPage /></MemoryRouter>);
    fillLogin();
    expect(await screen.findByText('Hệ thống đang gặp sự cố, bạn thử lại sau nhé.')).toBeInTheDocument();
  });
});
