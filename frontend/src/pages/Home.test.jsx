import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Home from './Home.jsx';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
const stomp = vi.hoisted(() => ({ options: null }));

vi.mock('axios', () => ({ default: api }));
vi.mock('../api/http.js', () => ({ API_BASE_URL: 'http://api.test' }));
vi.mock('../context/auth-context.js', () => ({
  useAuth: () => ({ user: { fullName: 'Quang Nguyen', phone: '0912345678', role: 'USER' } }),
}));
vi.mock('sockjs-client/dist/sockjs', () => ({ default: vi.fn(() => ({})) }));
vi.mock('@stomp/stompjs', () => ({
  Client: class {
    constructor(options) {
      stomp.options = options;
    }

    activate() {}

    deactivate() {}

    subscribe() {}
  },
}));
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }));

describe('Home', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.get.mockResolvedValue({
      data: [{ productId: 7, name: 'Laptop CI', unit: 'cái', currentUnitPrice: 25000, stockQuantity: 3 }],
    });
    api.post.mockResolvedValue({
      data: {
        invoiceCode: 'CI-ORDER-1',
        buyerName: 'Quang Nguyen',
        buyerPhone: '0912345678',
        totalAmount: 25000,
        invoiceDetails: [{ product: { name: 'Laptop CI' }, quantity: 1, lineTotal: 25000 }],
      },
    });
    vi.spyOn(window, 'alert').mockImplementation(() => {});
  });

  it('loads products, builds an order from the cart, then shows the returned receipt', async () => {
    const user = userEvent.setup();
    render(<Home />);

    expect(await screen.findByText('Laptop CI')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
    await user.click(screen.getByText(/Giỏ hàng/));
    await user.click(screen.getByRole('button', { name: 'Tạo đơn' }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('http://api.test/api/invoices', {
      sellerId: 1,
      buyerName: 'Quang Nguyen',
      buyerPhone: '0912345678',
      items: [{ productId: 7, quantity: 1 }],
    }));

    expect(await screen.findByText('HÓA ĐƠN ĐIỆN TỬ')).toBeTruthy();
    expect(screen.getByText('CI-ORDER-1')).toBeTruthy();
  });
});
