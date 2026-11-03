import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import AuthPage from './AuthPage.jsx';

const auth = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn(),
  sessionExpired: false,
}));

vi.mock('../context/auth-context.js', () => ({
  useAuth: () => auth,
}));

describe('AuthPage', () => {
  it('blocks registration before it reaches the API when the password is shorter than 12 characters', async () => {
    const user = userEvent.setup();
    render(<AuthPage />);

    await user.click(screen.getByRole('button', { name: 'Đăng ký' }));
    await user.type(screen.getByLabelText('Họ và tên'), 'Nguyễn Văn An');
    await user.type(screen.getByLabelText('Số điện thoại'), '0912345678');
    await user.type(screen.getByLabelText('Email'), 'an@example.test');
    await user.type(screen.getByLabelText('Mật khẩu'), 'shortpass');
    await user.type(screen.getByLabelText('Nhập lại mật khẩu'), 'shortpass');
    await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));

    expect(screen.getByText('Mật khẩu cần từ 12 đến 72 ký tự')).toBeTruthy();
    expect(auth.register).not.toHaveBeenCalled();
  });

  it('blocks a Unicode password that would exceed BCrypt\'s 72-byte limit', async () => {
    const user = userEvent.setup();
    const password = 'ă'.repeat(37);
    render(<AuthPage />);

    await user.click(screen.getByRole('button', { name: 'Đăng ký' }));
    await user.type(screen.getByLabelText('Họ và tên'), 'Nguyễn Văn An');
    await user.type(screen.getByLabelText('Số điện thoại'), '0912345678');
    await user.type(screen.getByLabelText('Email'), 'an@example.test');
    await user.type(screen.getByLabelText('Mật khẩu'), password);
    await user.type(screen.getByLabelText('Nhập lại mật khẩu'), password);
    await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));

    expect(screen.getByText('Mật khẩu quá dài (tối đa 72 byte UTF-8)')).toBeTruthy();
    expect(auth.register).not.toHaveBeenCalled();
  });

  it('trims the login email before calling the authentication API', async () => {
    const user = userEvent.setup();
    auth.login.mockResolvedValueOnce(undefined);
    render(<AuthPage />);

    await user.type(screen.getByLabelText('Email'), '  user@example.test  ');
    await user.type(screen.getByLabelText('Mật khẩu'), 'correct-password');
    const submit = screen.getAllByRole('button', { name: 'Đăng nhập' })
      .find((button) => button.type === 'submit');
    await user.click(submit);

    expect(auth.login).toHaveBeenCalledWith({
      email: 'user@example.test',
      password: 'correct-password',
    });
  });
});
