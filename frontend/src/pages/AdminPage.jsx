import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../context/AuthContext.jsx';
import { adminApi } from '../api/admin.js';
import '../styles/admin.css';

const money = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' });
const when = (iso) => (iso ? new Date(iso).toLocaleString('vi-VN') : '-');

const STATUS_LABELS = {
  PENDING: 'Chờ thanh toán',
  PAID: 'Đã thanh toán',
  PARTIALLY_PAID: 'Thanh toán một phần',
  CANCELED_OUT_OF_STOCK: 'Hủy do hết hàng',
  EXPIRED: 'Hết hạn',
  FAILED: 'Thất bại',
};
const LINE_LABELS = { FULFILLED: 'Đã giao', OUT_OF_STOCK: 'Hết hàng' };
const EMPTY_FORM = { name: '', unit: '', currentUnitPrice: '', stockQuantity: '' };

function Badge({ status, labels = STATUS_LABELS }) {
  const key = String(status || 'unknown').toLowerCase();
  return <span className={`admin-badge admin-badge--${key}`}>{labels[status] || status || '-'}</span>;
}

function Field({ id, label, error, ...inputProps }) {
  return (
    <div className="admin-field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        {...inputProps}
      />
      {error && <p className="admin-field-error" id={`${id}-error`}>{error}</p>}
    </div>
  );
}

// ===================== Sản phẩm =====================

function ProductsPanel() {
  const [products, setProducts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [form, setForm] = useState(EMPTY_FORM);
  const [editingId, setEditingId] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setProducts(await adminApi.listProducts());
      setError('');
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  function setField(name) {
    return (event) => {
      setForm((prev) => ({ ...prev, [name]: event.target.value }));
      if (fieldErrors[name]) setFieldErrors((prev) => ({ ...prev, [name]: undefined }));
    };
  }

  function resetForm() {
    setForm(EMPTY_FORM);
    setEditingId(null);
    setFieldErrors({});
  }

  function startEdit(product) {
    setEditingId(product.productId);
    setForm({
      name: product.name ?? '',
      unit: product.unit ?? '',
      currentUnitPrice: String(product.currentUnitPrice ?? ''),
      stockQuantity: String(product.stockQuantity ?? ''),
    });
    setFieldErrors({});
    setNotice('');
    setError('');
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (saving) return;
    setSaving(true);
    setFieldErrors({});
    setError('');
    setNotice('');

    const body = {
      name: form.name.trim(),
      unit: form.unit.trim(),
      currentUnitPrice: form.currentUnitPrice === '' ? null : Number(form.currentUnitPrice),
      stockQuantity: form.stockQuantity === '' ? null : Number(form.stockQuantity),
    };
    try {
      if (editingId) {
        await adminApi.updateProduct(editingId, body);
        setNotice('Đã lưu thay đổi sản phẩm');
      } else {
        await adminApi.createProduct(body);
        setNotice('Đã thêm sản phẩm');
      }
      resetForm();
      await load();
    } catch (e) {
      const fe = e.fieldErrors || {};
      setFieldErrors(fe);
      setError(Object.keys(fe).length ? '' : e.message);
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete(product) {
    if (!window.confirm(`Xóa sản phẩm "${product.name}"?`)) return;
    setNotice('');
    setError('');
    try {
      await adminApi.deleteProduct(product.productId);
      if (editingId === product.productId) resetForm();
      setNotice('Đã xóa sản phẩm');
      await load();
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <div className="admin-split">
      <section className="admin-card" aria-labelledby="admin-products-title">
        <h2 id="admin-products-title">Danh sách sản phẩm</h2>
        {notice && <p className="admin-note admin-note--ok" role="status">{notice}</p>}
        {error && <p className="admin-note admin-note--error" role="alert">{error}</p>}

        {loading ? (
          <p className="admin-empty">Đang tải sản phẩm…</p>
        ) : products.length === 0 ? (
          <p className="admin-empty">Chưa có sản phẩm nào. Thêm sản phẩm đầu tiên ở khung bên cạnh.</p>
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Mã</th>
                  <th>Tên sản phẩm</th>
                  <th>Đơn vị</th>
                  <th className="num">Giá</th>
                  <th className="num">Tồn kho</th>
                  <th><span className="sr-only">Thao tác</span></th>
                </tr>
              </thead>
              <tbody>
                {products.map((p) => (
                  <tr key={p.productId} className={p.productId === editingId ? 'is-active' : undefined}>
                    <td>{p.productId}</td>
                    <td>{p.name}</td>
                    <td>{p.unit}</td>
                    <td className="num">{money.format(p.currentUnitPrice)}</td>
                    <td className={`num${p.stockQuantity === 0 ? ' is-zero' : ''}`}>{p.stockQuantity}</td>
                    <td className="actions">
                      <button type="button" className="admin-btn admin-btn--ghost" onClick={() => startEdit(p)}>Sửa</button>
                      <button type="button" className="admin-btn admin-btn--danger" onClick={() => handleDelete(p)}>Xóa</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="admin-card" aria-labelledby="admin-form-title">
        <h2 id="admin-form-title">{editingId ? `Sửa sản phẩm ${editingId}` : 'Thêm sản phẩm'}</h2>
        <form onSubmit={handleSubmit} noValidate>
          <Field id="admin-name" label="Tên sản phẩm" value={form.name}
            onChange={setField('name')} error={fieldErrors.name} maxLength={200} />
          <Field id="admin-unit" label="Đơn vị tính (cái, hộp...)" value={form.unit}
            onChange={setField('unit')} error={fieldErrors.unit} maxLength={50} />
          <Field id="admin-price" label="Giá (VND)" type="number" min="0" step="any" inputMode="decimal"
            value={form.currentUnitPrice} onChange={setField('currentUnitPrice')} error={fieldErrors.currentUnitPrice} />
          <Field id="admin-stock" label="Tồn kho" type="number" min="0" step="1" inputMode="numeric"
            value={form.stockQuantity} onChange={setField('stockQuantity')} error={fieldErrors.stockQuantity} />
          <div className="admin-form-actions">
            <button type="submit" className="admin-btn admin-btn--primary" disabled={saving} aria-busy={saving}>
              {saving ? 'Đang lưu…' : editingId ? 'Lưu thay đổi' : 'Thêm sản phẩm'}
            </button>
            {editingId && (
              <button type="button" className="admin-btn admin-btn--ghost" onClick={resetForm}>Hủy sửa</button>
            )}
          </div>
        </form>
      </section>
    </div>
  );
}

// ===================== Hóa đơn =====================

function InvoicesPanel() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reloadKey, setReloadKey] = useState(0);
  const [selected, setSelected] = useState(null);
  const [detailError, setDetailError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    adminApi.listInvoices({ status, page })
      .then((result) => { if (!cancelled) { setData(result); setError(''); } })
      .catch((e) => { if (!cancelled) setError(e.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [status, page, reloadKey]);

  async function openDetail(code) {
    setDetailError('');
    try {
      setSelected(await adminApi.getInvoice(code));
    } catch (e) {
      setSelected(null);
      setDetailError(e.message);
    }
  }

  function changeStatus(event) {
    setStatus(event.target.value);
    setPage(0);
    setSelected(null);
  }

  const rows = data?.content ?? [];

  return (
    <div className="admin-split">
      <section className="admin-card" aria-labelledby="admin-invoices-title">
        <div className="admin-toolbar">
          <h2 id="admin-invoices-title">Hóa đơn</h2>
          <label className="admin-inline-field">
            Trạng thái
            <select value={status} onChange={changeStatus}>
              <option value="">Tất cả</option>
              {Object.entries(STATUS_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>
          <button type="button" className="admin-btn admin-btn--ghost" onClick={() => setReloadKey((k) => k + 1)}>
            Tải lại
          </button>
        </div>

        {error && <p className="admin-note admin-note--error" role="alert">{error}</p>}
        {detailError && <p className="admin-note admin-note--error" role="alert">{detailError}</p>}

        {loading && !data ? (
          <p className="admin-empty">Đang tải hóa đơn…</p>
        ) : rows.length === 0 ? (
          <p className="admin-empty">Không có hóa đơn nào{status ? ' ở trạng thái này' : ''}.</p>
        ) : (
          <>
            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>Mã hóa đơn</th>
                    <th>Người mua</th>
                    <th>Tài khoản</th>
                    <th className="num">Tổng tiền</th>
                    <th>Trạng thái</th>
                    <th>Tạo lúc</th>
                    <th><span className="sr-only">Xem</span></th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((inv) => (
                    <tr key={inv.invoiceCode} className={selected?.invoiceCode === inv.invoiceCode ? 'is-active' : undefined}>
                      <td>{inv.invoiceCode}</td>
                      <td>{inv.buyerName}</td>
                      <td>{inv.userEmail || '-'}</td>
                      <td className="num">{money.format(inv.totalAmount)}</td>
                      <td><Badge status={inv.status} /></td>
                      <td>{when(inv.createdAt)}</td>
                      <td className="actions">
                        <button type="button" className="admin-btn admin-btn--ghost" onClick={() => openDetail(inv.invoiceCode)}>
                          Chi tiết
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="admin-pager">
              <button type="button" className="admin-btn admin-btn--ghost" disabled={page === 0 || loading}
                onClick={() => setPage((p) => p - 1)}>Trang trước</button>
              <span>Trang {(data?.page ?? 0) + 1} / {Math.max(data?.totalPages ?? 1, 1)} ({data?.totalElements ?? 0} hóa đơn)</span>
              <button type="button" className="admin-btn admin-btn--ghost"
                disabled={loading || page + 1 >= (data?.totalPages ?? 1)}
                onClick={() => setPage((p) => p + 1)}>Trang sau</button>
            </div>
          </>
        )}
      </section>

      <section className="admin-card" aria-labelledby="admin-detail-title" aria-live="polite">
        <h2 id="admin-detail-title">Chi tiết hóa đơn</h2>
        {!selected ? (
          <p className="admin-empty">Chọn “Chi tiết” ở một hóa đơn để xem các mặt hàng và thông tin thanh toán.</p>
        ) : (
          <>
            <dl className="admin-facts">
              <dt>Mã hóa đơn</dt><dd>{selected.invoiceCode}</dd>
              <dt>Trạng thái</dt><dd><Badge status={selected.status} /></dd>
              <dt>Người mua</dt><dd>{selected.buyerName} ({selected.buyerPhone})</dd>
              <dt>Tài khoản</dt><dd>{selected.userEmail || '-'}</dd>
              <dt>Tạo lúc</dt><dd>{when(selected.createdAt)}</dd>
              <dt>Mã giao dịch G-Pay</dt><dd>{selected.gpayTransId || '-'}</dd>
              <dt>Tài khoản ảo</dt><dd>{selected.virtualAccountNumber || '-'}</dd>
            </dl>
            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>Sản phẩm</th>
                    <th className="num">SL</th>
                    <th className="num">Đơn giá</th>
                    <th className="num">Thành tiền</th>
                    <th>Giao hàng</th>
                  </tr>
                </thead>
                <tbody>
                  {(selected.items || []).map((item, index) => (
                    <tr key={`${item.productId}-${index}`}>
                      <td>{item.productName}</td>
                      <td className="num">{item.quantity}</td>
                      <td className="num">{money.format(item.unitPrice)}</td>
                      <td className="num">{money.format(item.lineTotal)}</td>
                      <td>{item.status ? <Badge status={item.status} labels={LINE_LABELS} /> : '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="admin-total">Tổng cộng: <strong>{money.format(selected.totalAmount)}</strong></p>
          </>
        )}
      </section>
    </div>
  );
}

// ===================== Trang quản trị =====================

export default function AdminPage() {
  const { user } = useAuth();
  const [tab, setTab] = useState('products');

  // Chỉ là lớp tiện lợi cho giao diện: quyền thật được backend kiểm tra ở mọi API /api/admin/**
  if (user?.role !== 'ADMIN') {
    return (
      <main className="admin-shell">
        <p className="admin-note admin-note--error" role="alert">Chỉ quản trị viên mới xem được trang này.</p>
      </main>
    );
  }

  return (
    <main className="admin-shell">
      <header className="admin-head">
        <h1>Quản trị cửa hàng</h1>
        <p>
          Bạn đang đăng nhập bằng tài khoản quản trị viên. Máy chủ kiểm tra lại quyền ở mọi thao tác
          và ghi nhật ký những gì bạn thay đổi hoặc xem.
        </p>
        <div className="admin-tabs" role="group" aria-label="Khu vực quản trị">
          <button type="button" aria-pressed={tab === 'products'} onClick={() => setTab('products')}>Sản phẩm</button>
          <button type="button" aria-pressed={tab === 'invoices'} onClick={() => setTab('invoices')}>Hóa đơn</button>
        </div>
      </header>
      {tab === 'products' ? <ProductsPanel /> : <InvoicesPanel />}
    </main>
  );
}
