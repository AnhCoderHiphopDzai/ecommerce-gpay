import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import UserBar from './UserBar.jsx';

const auth = vi.hoisted(() => ({
  user: null,
  logout: vi.fn(),
  logoutAll: vi.fn(),
}));

vi.mock('../context/auth-context.js', () => ({
  useAuth: () => auth,
}));

describe('UserBar', () => {
  beforeEach(() => {
    auth.user = { fullName: 'Quang', role: 'USER' };
    auth.logout.mockReset();
    auth.logoutAll.mockReset();
  });

  it('does not render without a signed-in user', () => {
    auth.user = null;
    const { container } = render(<UserBar />);
    expect(container.innerHTML).toBe('');
  });

  it('runs logout-all when the user chooses to end every session', async () => {
    const user = userEvent.setup();
    render(<UserBar />);

    await user.click(screen.getByRole('button', { name: 'Đăng xuất mọi thiết bị' }));

    expect(auth.logoutAll).toHaveBeenCalledOnce();
  });

  it('only exposes the administration switch to an administrator', () => {
    const onChangeView = vi.fn();
    render(<UserBar onChangeView={onChangeView} />);
    expect(screen.queryByRole('button', { name: 'Quản trị' })).toBeNull();

    auth.user = { fullName: 'Quang', role: 'ADMIN' };
    render(<UserBar onChangeView={onChangeView} />);
    expect(screen.getByRole('button', { name: 'Quản trị' })).toBeTruthy();
  });
});
