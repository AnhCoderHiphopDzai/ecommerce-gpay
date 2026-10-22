// Lớp gọi API dùng chung: tự gắn JWT vào header Authorization.
//
// - Code dùng axios (như Home.jsx): KHÔNG cần sửa gì. Bên dưới có interceptor tự gắn token
//   cho mọi request gửi tới backend của mình, và tự đăng xuất khi server trả 401.
// - Code dùng fetch: dùng apiFetch, cách dùng giống hệt fetch (trả về Response gốc):
//     fetch('http://localhost:8081/api/invoices', {...})  ->  apiFetch('/api/invoices', {...})

import axios from 'axios';

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081').replace(/\/+$/, '');
const API_ORIGIN = new URL(API_BASE_URL).origin;
const TOKEN_KEY = 'auth_token';

// Token nằm trong localStorage để vẫn còn sau khi người dùng sang trang G-Pay rồi quay lại.
// Đánh đổi: script độc (XSS) đọc được localStorage, nên tuyệt đối không dùng dangerouslySetInnerHTML với dữ liệu không tin cậy.
export const tokenStore = {
  get() {
    try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
  },
  set(token) {
    try { localStorage.setItem(TOKEN_KEY, token); } catch { /* chế độ ẩn danh có thể chặn storage */ }
  },
  clear() {
    try { localStorage.removeItem(TOKEN_KEY); } catch { /* bỏ qua */ }
  },
};

let onUnauthorized = null;
/** AuthProvider đăng ký hàm này để tự đăng xuất khi server trả 401 (token hết hạn/bị từ chối). */
export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler;
}

function toUrl(input) {
  return /^https?:\/\//i.test(input) ? input : `${API_BASE_URL}${input.startsWith('/') ? '' : '/'}${input}`;
}

export async function apiFetch(input, options = {}) {
  const { skipAuth = false, ...fetchOptions } = options;
  const url = toUrl(input);
  const headers = new Headers(fetchOptions.headers || {});

  // Chỉ gắn token khi gọi đúng backend của mình, không bao giờ gửi token sang domain khác
  const token = tokenStore.get();
  const attachToken = Boolean(token) && !skipAuth && new URL(url).origin === API_ORIGIN;
  if (attachToken) headers.set('Authorization', `Bearer ${token}`);

  if (typeof fetchOptions.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(url, { ...fetchOptions, headers });

  if (response.status === 401 && attachToken && onUnauthorized) onUnauthorized();
  return response;
}

// ===== axios: tự gắn token cho request gửi tới backend, không gắn cho domain khác =====
function isApiRequest(config) {
  try {
    return new URL(config.url, config.baseURL || window.location.origin).origin === API_ORIGIN;
  } catch {
    return false;
  }
}

axios.interceptors.request.use((config) => {
  const token = tokenStore.get();
  if (token && isApiRequest(config)) {
    config.headers = config.headers || {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

axios.interceptors.response.use(
  (response) => response,
  (error) => {
    // 401 khi đang có token nghĩa là token hết hạn hoặc bị từ chối -> đưa người dùng về trang đăng nhập
    if (error.response?.status === 401 && tokenStore.get() && error.config
        && isApiRequest(error.config) && onUnauthorized) {
      onUnauthorized();
    }
    return Promise.reject(error);
  },
);
