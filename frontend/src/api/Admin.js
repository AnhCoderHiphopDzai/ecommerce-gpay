import { apiFetch } from './http.js';

// Gọi API quản trị: apiFetch tự gắn access token và tự làm mới phiên khi gặp 401.
// Mọi kiểu lỗi được đổi thành Error có message dễ hiểu (kèm lỗi theo từng ô nhập nếu có).
async function request(path, options) {
  let response;
  try {
    response = await apiFetch(path, options);
  } catch {
    throw new Error('Không kết nối được máy chủ. Kiểm tra backend đã chạy chưa rồi thử lại.');
  }

  let data = null;
  try { data = await response.json(); } catch { /* 204 hoặc không có nội dung */ }

  if (!response.ok) {
    const error = new Error(data?.message || `Yêu cầu thất bại (mã ${response.status})`);
    error.status = response.status;
    error.fieldErrors = data?.errors || {};
    throw error;
  }
  return data;
}

const withBody = (method, body) => ({ method, body: JSON.stringify(body) });

export const adminApi = {
  // Xem sản phẩm dùng API công khai có sẵn
  listProducts: () => request('/api/products'),
  createProduct: (body) => request('/api/admin/products', withBody('POST', body)),
  updateProduct: (id, body) => request(`/api/admin/products/${id}`, withBody('PUT', body)),
  deleteProduct: (id) => request(`/api/admin/products/${id}`, { method: 'DELETE' }),

  listInvoices: ({ status = '', page = 0, size = 20 } = {}) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) params.set('status', status);
    return request(`/api/admin/invoices?${params}`);
  },
  getInvoice: (code) => request(`/api/admin/invoices/${encodeURIComponent(code)}`),
};