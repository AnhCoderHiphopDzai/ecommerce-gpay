import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AuthProvider } from './AuthContext.jsx';
import { useAuth } from './auth-context.js';

const api = vi.hoisted(() => {
  let token = null;
  const tokenStore = {
    get: vi.fn(() => token),
    set: vi.fn((next) => { token = next; }),
    clear: vi.fn(() => { token = null; }),
  };
  return {
    apiFetch: vi.fn(),
    setUnauthorizedHandler: vi.fn(),
    tokenStore,
    reset() {
      token = null;
      this.apiFetch.mockReset();
      this.setUnauthorizedHandler.mockReset();
      tokenStore.get.mockClear();
      tokenStore.set.mockClear();
      tokenStore.clear.mockClear();
    },
  };
});

vi.mock('../api/http.js', () => api);

function Probe() {
  const { user, login, logoutAll } = useAuth();
  return (
    <>
      <p>{user?.email || 'anonymous'}</p>
      <button type="button" onClick={() => login({ email: 'user@example.test', password: 'password' })}>Login</button>
      <button type="button" onClick={logoutAll}>Logout all</button>
    </>
  );
}

const authenticated = {
  accessToken: 'new-access-token',
  user: { id: 7, email: 'user@example.test', fullName: 'Test User', role: 'USER' },
};

describe('AuthProvider', () => {
  beforeEach(() => api.reset());

  it('persists the access token and user after a successful login', async () => {
    const user = userEvent.setup();
    api.apiFetch.mockResolvedValueOnce(new Response(JSON.stringify(authenticated), { status: 200 }));
    render(<AuthProvider><Probe /></AuthProvider>);

    await user.click(screen.getByRole('button', { name: 'Login' }));

    await waitFor(() => expect(screen.getByText('user@example.test')).toBeTruthy());
    expect(api.apiFetch).toHaveBeenCalledWith('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: 'user@example.test', password: 'password' }),
      skipAuth: true,
    });
    expect(api.tokenStore.set).toHaveBeenCalledWith('new-access-token');
  });

  it('calls the logout-all endpoint and clears the browser session even when it succeeds', async () => {
    const user = userEvent.setup();
    api.apiFetch
      .mockResolvedValueOnce(new Response(JSON.stringify(authenticated), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    render(<AuthProvider><Probe /></AuthProvider>);

    await user.click(screen.getByRole('button', { name: 'Login' }));
    await screen.findByText('user@example.test');
    await user.click(screen.getByRole('button', { name: 'Logout all' }));

    await waitFor(() => expect(screen.getByText('anonymous')).toBeTruthy());
    expect(api.apiFetch).toHaveBeenLastCalledWith('/api/auth/logout-all', { method: 'POST' });
    expect(api.tokenStore.clear).toHaveBeenCalled();
  });
});
