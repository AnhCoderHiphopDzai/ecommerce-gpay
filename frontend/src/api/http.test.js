import { afterEach, describe, expect, it, vi } from 'vitest';
import { API_BASE_URL, apiFetch, setUnauthorizedHandler, tokenStore } from './http.js';

describe('apiFetch', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    tokenStore.clear();
    setUnauthorizedHandler(null);
  });

  it('sends a bearer token and JSON content type only to the configured API origin', async () => {
    tokenStore.set('current-token');
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await apiFetch('/api/products', { method: 'POST', body: '{}' });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/api/products`);
    expect(options.headers.get('Authorization')).toBe('Bearer current-token');
    expect(options.headers.get('Content-Type')).toBe('application/json');
    expect(options.credentials).toBe('include');
  });

  it('never forwards a bearer token to a different origin', async () => {
    tokenStore.set('current-token');
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await apiFetch('https://example.test/collect');

    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers.has('Authorization')).toBe(false);
  });

  it('refreshes once then retries a rejected request with the new access token', async () => {
    tokenStore.set('expired-token');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('', { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ accessToken: 'fresh-token' }), { status: 200 }))
      .mockResolvedValueOnce(new Response('[]', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const response = await apiFetch('/api/products');

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[1][0]).toBe(`${API_BASE_URL}/api/auth/refresh`);
    expect(fetchMock.mock.calls[2][1].headers.get('Authorization')).toBe('Bearer fresh-token');
  });

  it('notifies the application when refresh fails', async () => {
    tokenStore.set('expired-token');
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('', { status: 401 }))
      .mockResolvedValueOnce(new Response('', { status: 401 }));
    vi.stubGlobal('fetch', fetchMock);

    const response = await apiFetch('/api/products');

    expect(response.status).toBe(401);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });
});
