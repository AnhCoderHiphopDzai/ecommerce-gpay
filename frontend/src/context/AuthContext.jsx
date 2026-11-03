import { useCallback, useEffect, useMemo, useState } from 'react';
import { apiFetch, setUnauthorizedHandler, tokenStore } from '../api/http.js';
import { AuthContext } from './auth-context.js';

async function readJson(response) {
  try { return await response.json(); } catch { return {}; }
}

// Gọi API đăng nhập/đăng ký và đổi mọi kiểu lỗi thành Error có message dễ hiểu
async function postAuth(path, payload, fallbackMessage) {
  let response;
  try {
    response = await apiFetch(path, { method: 'POST', body: JSON.stringify(payload), skipAuth: true });
  } catch {
    throw new Error('Không kết nối được máy chủ. Kiểm tra backend đã chạy chưa rồi thử lại.');
  }
  const data = await readJson(response);
  if (!response.ok) {
    const error = new Error(data.message || fallbackMessage);
    error.fieldErrors = data.errors || {}; // lỗi theo từng ô nhập, ví dụ { email: '...' }
    throw error;
  }
  return data;
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  // Nếu đang có token cũ thì phải hỏi server xem còn dùng được không, trong lúc đó chưa hiện gì
  const [loading, setLoading] = useState(() => Boolean(tokenStore.get()));
  const [sessionExpired, setSessionExpired] = useState(false);

  const clearLocalSession = useCallback(() => {
    tokenStore.clear();
    setUser(null);
  }, []);

  // Luôn xóa trạng thái ở trình duyệt, kể cả khi backend đang không truy cập được.
  // Endpoint logout chỉ nhận diện bằng refresh cookie, nên không cần access token còn hiệu lực.
  const logout = useCallback(async () => {
    try {
      await apiFetch('/api/auth/logout', { method: 'POST', skipAuth: true });
    } catch {
      /* người dùng vẫn được đăng xuất cục bộ */
    } finally {
      clearLocalSession();
    }
  }, [clearLocalSession]);

  const logoutAll = useCallback(async () => {
    try {
      await apiFetch('/api/auth/logout-all', { method: 'POST' });
    } catch {
      /* phiên cục bộ vẫn phải kết thúc */
    } finally {
      clearLocalSession();
    }
  }, [clearLocalSession]);

  // Server trả 401 ở bất kỳ request nào đang kèm token -> đăng xuất và báo phiên hết hạn
  useEffect(() => {
    setUnauthorizedHandler(() => {
      clearLocalSession();
      setSessionExpired(true);
    });
    return () => setUnauthorizedHandler(null);
  }, [clearLocalSession]);

  // Khôi phục phiên khi tải lại trang (kể cả khi quay về từ trang thanh toán G-Pay)
  useEffect(() => {
    if (!tokenStore.get()) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const response = await apiFetch('/api/auth/me');
        if (response.ok && !cancelled) setUser(await response.json());
        // 401: handler ở trên đã đăng xuất. Lỗi mạng hoặc 5xx: giữ token, hiện form đăng nhập
      } catch {
        /* backend chưa chạy: để người dùng thử lại */
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const startSession = useCallback((data) => {
    tokenStore.set(data.accessToken);
    setSessionExpired(false);
    setUser(data.user);
  }, []);

  const login = useCallback(async ({ email, password }) => {
    const data = await postAuth('/api/auth/login', { email, password },
      'Đăng nhập không thành công. Vui lòng thử lại.');
    startSession(data);
  }, [startSession]);

  const register = useCallback(async ({ fullName, phone, email, password }) => {
    const data = await postAuth('/api/auth/register', { fullName, phone, email, password },
      'Tạo tài khoản không thành công. Vui lòng thử lại.');
    startSession(data);
  }, [startSession]);

  const value = useMemo(
    () => ({ user, loading, sessionExpired, login, register, logout, logoutAll }),
    [user, loading, sessionExpired, login, register, logout, logoutAll],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
