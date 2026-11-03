import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AdminPage from './AdminPage.jsx';

const auth = vi.hoisted(() => ({ user: null }));
const adminApi = vi.hoisted(() => ({
  listProducts: vi.fn(),
  createProduct: vi.fn(),
  updateProduct: vi.fn(),
  deleteProduct: vi.fn(),
  listInvoices: vi.fn(),
  getInvoice: vi.fn(),
}));

vi.mock('../context/auth-context.js', () => ({ useAuth: () => auth }));
vi.mock('../api/Admin.js', () => ({ adminApi }));

describe('AdminPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    adminApi.listProducts.mockResolvedValue([]);
    adminApi.createProduct.mockResolvedValue({ productId: 10 });
  });

  it('does not expose the management UI to a non-admin account', () => {
    auth.user = { role: 'USER' };
    render(<AdminPage />);

    expect(screen.getByText('Chỉ quản trị viên mới xem được trang này.')).toBeTruthy();
    expect(adminApi.listProducts).not.toHaveBeenCalled();
  });

  it('sends normalized numeric product data through the admin API', async () => {
    auth.user = { role: 'ADMIN' };
    const user = userEvent.setup();
    render(<AdminPage />);

    expect(await screen.findByText('Chưa có sản phẩm nào. Thêm sản phẩm đầu tiên ở khung bên cạnh.')).toBeTruthy();
    await user.type(screen.getByLabelText('Tên sản phẩm'), 'Bàn phím cơ');
    await user.type(screen.getByLabelText('Đơn vị tính (cái, hộp...)'), 'cái');
    await user.type(screen.getByLabelText('Giá (VND)'), '350000');
    await user.type(screen.getByLabelText('Tồn kho'), '5');
    await user.click(screen.getByRole('button', { name: 'Thêm sản phẩm' }));

    await waitFor(() => expect(adminApi.createProduct).toHaveBeenCalledWith({
      name: 'Bàn phím cơ',
      unit: 'cái',
      currentUnitPrice: 350000,
      stockQuantity: 5,
    }));
    expect(await screen.findByText('Đã thêm sản phẩm')).toBeTruthy();
  });
});
