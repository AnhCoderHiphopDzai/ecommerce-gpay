import React, { useState, useEffect,useRef } from 'react';
import axios from 'axios';
import SockJS from 'sockjs-client/dist/sockjs'; // Import chuẩn chống lỗi cho Vite
import { Client } from '@stomp/stompjs';
import './Home.css';
import Swal from 'sweetalert2';

const Home = () => {
    const [products, setProducts] = useState([]);
    const [cart, setCart] = useState([]);
    const [isCartOpen, setIsCartOpen] = useState(false);
    const [buyerName, setBuyerName] = useState('');
    const [buyerPhone, setBuyerPhone] = useState('');
    const [previewInvoice, setPreviewInvoice] = useState(null);
    const [vaData, setVaData] = useState(null);
    const invoiceRef = useRef(null);
    useEffect(() => {
        invoiceRef.current = previewInvoice;
    }, [previewInvoice]);

    useEffect(() => {
        // Tải danh sách sản phẩm ban đầu
        axios.get('http://localhost:8081/api/products')
            .then(response => setProducts(response.data))
            .catch(error => console.error("Lỗi tải sản phẩm:", error));

        // Khởi tạo WebSocket
        const stompClient = new Client({
            webSocketFactory: () => new SockJS('http://localhost:8081/ws'),
            onConnect: () => {
                console.log(' Đã kết nối WebSocket thành công');

                // Lắng nghe cập nhật Tồn kho (Live Stock)
                stompClient.subscribe('/topic/stock-updates', (message) => {
                    const data = JSON.parse(message.body);

                    // Cập nhật số lượng sản phẩm trên giao diện
                    setProducts(prevProducts => prevProducts.map(p =>
                        p.productId === data.productId ? { ...p, stockQuantity: data.stockQuantity } : p
                    ));

                    // Nếu sản phẩm về 0, tự động xóa nó khỏi giỏ hàng
                    if (data.stockQuantity === 0) {
                        setCart(prevCart => prevCart.filter(item => item.product.productId !== data.productId));
                    }
                });

                // Lắng nghe lệnh SOLD_OUT để Kick-out người đang mở mã QR
                stompClient.subscribe('/topic/sold-out', (message) => {
                    const soldOutProductId = parseInt(message.body);

                    // 👉 KIỂM TRA BẰNG REF (Thay vì gọi setPreviewInvoice)
                    if (invoiceRef.current) {
                        const hasSoldOutItem = invoiceRef.current.invoiceDetails.some(
                            detail => detail.product.productId === soldOutProductId
                        );

                        if (hasSoldOutItem) {
                            alert(" Rất tiếc! Một sản phẩm trong đơn của bạn vừa được khách khác thanh toán xong. Vui lòng tạo đơn mới.");
                            setVaData(null);         // Tắt mã QR
                            setPreviewInvoice(null); // Đóng hóa đơn
                        }
                    }
                });

                // Lắng nghe THANH TOÁN THÀNH CÔNG
                stompClient.subscribe('/topic/payment-success', (message) => {
                    const paidInvoiceCode = message.body;

                    // 👉 KIỂM TRA BẰNG REF (Thay vì gọi setPreviewInvoice)
                    if (invoiceRef.current && invoiceRef.current.invoiceCode === paidInvoiceCode) {

                        // Kích hoạt thông báo nổi SweetAlert2
                        Swal.fire({
                            toast: true,
                            position: 'top-end',
                            icon: 'success',
                            title: 'Thanh toán thành công!',
                            text: `Đơn hàng ${paidInvoiceCode} đang được chuẩn bị.`,
                            showConfirmButton: false,
                            timer: 4000,
                            timerProgressBar: true,
                            customClass: {
                                popup: 'colored-toast'
                            }
                        });

                        // Đóng giao diện an toàn
                        setVaData(null);
                        setPreviewInvoice(null);
                    }
                });
            },
            onStompError: (frame) => {
                console.error('❌ Lỗi WebSocket: ' + frame.headers['message']);
            }
        });

        stompClient.activate();

        // Cleanup khi component bị hủy
        return () => {
            if (stompClient) stompClient.deactivate();
        };
    }, []); // Chỉ chạy 1 lần khi render
    // ==========================================
    // 2. LOGIC GIỎ HÀNG & TẠO ĐƠN
    // ==========================================
    const addToCart = (product) => {
        setCart(prevCart => {
            const existingItem = prevCart.find(item => item.product.productId === product.productId);
            if (existingItem) {
                if (existingItem.quantity >= product.stockQuantity) {
                    alert("Đã đạt giới hạn tồn kho!");
                    return prevCart;
                }
                return prevCart.map(item =>
                    item.product.productId === product.productId
                        ? { ...item, quantity: item.quantity + 1 } : item
                );
            }
            return [...prevCart, { product, quantity: 1 }];
        });
    };

    const updateQuantity = (productId, amount) => {
        setCart(prevCart => prevCart.map(item => {
            if (item.product.productId === productId) {
                const newQty = item.quantity + amount;
                if (newQty > 0 && newQty <= item.product.stockQuantity) {
                    return { ...item, quantity: newQty };
                }
            }
            return item;
        }));
    };

    const removeFromCart = (productId) => setCart(prevCart => prevCart.filter(item => item.product.productId !== productId));

    const cartTotal = cart.reduce((total, item) => total + (item.product.currentUnitPrice * item.quantity), 0);

    const handleSubmitOrder = async (e) => {
        e.preventDefault();
        if (cart.length === 0) return alert("Giỏ hàng trống!");

        const orderPayload = {
            sellerId: 1,
            buyerName,
            buyerPhone,
            items: cart.map(item => ({ productId: item.product.productId, quantity: item.quantity }))
        };

        try {
            const response = await axios.post('http://localhost:8081/api/invoices', orderPayload);
            setPreviewInvoice(response.data);
            setCart([]);
            setIsCartOpen(false);
            setVaData(null);
        } catch (error) {
            alert("Tạo đơn thất bại: " + (error.response?.data || error.message));
        }
    };

    // ==========================================
    // 3. XỬ LÝ THANH TOÁN (CÓ BẮT LỖI JIT 3 PHÚT)
    // ==========================================
    const handleProceedToPayment = async (invoiceCode, amount) => {
        try {
            const paymentRes = await axios.get(`http://localhost:8081/api/payment/get-url`, {
                params: { invoiceCode, amount }
            });
            window.location.href = paymentRes.data;
        } catch (error) {
            // Hiển thị trực tiếp câu lỗi "Khách khác đang thanh toán..." từ Backend trả về
            alert("⚠️ Không thể thanh toán: " + (error.response?.data || error.message));
        }
    };

    const handleProceedToVirtualAccount = async (invoiceCode) => {
        try {
            const vaRes = await axios.get(`http://localhost:8081/api/payment/get-va`, {
                params: { invoiceCode }
            });
            setVaData(vaRes.data);
        } catch (error) {
            // Hiển thị trực tiếp câu lỗi "Khách khác đang thanh toán..." từ Backend trả về
            alert("⚠️ Không thể tạo mã QR: " + (error.response?.data || error.message));
        }
    };

    // ==========================================
    // GIAO DIỆN HIỂN THỊ
    // ==========================================

    // MÀN HÌNH 1: HÓA ĐƠN & THANH TOÁN
    if (previewInvoice) {
        return (
            <div className="modern-container">
                <div className="invoice-receipt">
                    <div className="receipt-header">
                        <h2>HÓA ĐƠN ĐIỆN TỬ</h2>
                        <p className="invoice-code">Mã ĐH: <strong>{previewInvoice.invoiceCode}</strong></p>
                    </div>

                    <div className="receipt-info">
                        <p> Khách hàng: <strong>{previewInvoice.buyerName}</strong></p>
                        <p> Điện thoại: <strong>{previewInvoice.buyerPhone}</strong></p>
                    </div>

                    <table className="receipt-table">
                        <thead>
                        <tr><th>Sản phẩm</th><th>SL</th><th>Thành tiền</th></tr>
                        </thead>
                        <tbody>
                        {previewInvoice.invoiceDetails?.map((detail, index) => (
                            <tr key={index}>
                                <td>{detail.product?.name}</td>
                                <td>x{detail.quantity}</td>
                                <td>{detail.lineTotal?.toLocaleString()} ₫</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                    <div className="receipt-total">
                        <h3>Tổng thanh toán: <span>{previewInvoice.totalAmount?.toLocaleString()} ₫</span></h3>
                    </div>

                    {!vaData ? (
                        <div className="receipt-actions" style={{ display: 'flex', flexDirection: 'column', gap: '10px', marginTop: '20px' }}>
                            <button onClick={() => handleProceedToPayment(previewInvoice.invoiceCode, previewInvoice.totalAmount)}
                                    style={{ padding: '12px', fontSize: '16px', fontWeight: 'bold', borderRadius: '6px', border: 'none', backgroundColor: '#131A2A', color: '#fff', cursor: 'pointer' }}>
                                Thanh toán qua G-Pay
                            </button>
                            <button onClick={() => handleProceedToVirtualAccount(previewInvoice.invoiceCode)}
                                    style={{ padding: '12px', fontSize: '16px', fontWeight: 'bold', borderRadius: '6px', border: '2px solid #000', backgroundColor: '#0052CC', color: '#fff', cursor: 'pointer' }}>
                                Chuyển khoản (Mã QR)
                            </button>
                            <button onClick={() => { setPreviewInvoice(null); setVaData(null); }}
                                    style={{ padding: '12px', fontSize: '16px', fontWeight: 'bold', borderRadius: '6px', border: '1px solid #ccc', backgroundColor: '#fff', color: '#666', cursor: 'pointer' }}>
                                Đóng
                            </button>
                        </div>
                    ) : (
                        <div className="qr-display-container" style={{ textAlign: 'center', marginTop: '20px', padding: '20px', border: '2px dashed #0052CC', borderRadius: '10px', backgroundColor: '#f9f9fb' }}>
                            <h3 style={{ color: '#0052CC' }}>Quét mã QR để thanh toán</h3>
                            <p style={{ margin: '5px 0' }}>Ngân hàng: <strong>{vaData.bank_code || 'BIDV'}</strong></p>
                            <p style={{ margin: '5px 0' }}>Số tài khoản: <strong style={{ fontSize: '1.2em' }}>{vaData.account_number}</strong></p>
                            <p style={{ margin: '5px 0' }}>Số tiền: <strong style={{ color: '#e74c3c' }}>{previewInvoice.totalAmount?.toLocaleString()} ₫</strong></p>
                            <div style={{ margin: '15px 0' }}>
                                <img src={`data:image/png;base64,${vaData.qr_code_image}`} alt="Mã VietQR" style={{ width: '250px', height: '250px', objectFit: 'contain' }} />
                            </div>
                            <button onClick={() => { setPreviewInvoice(null); setVaData(null); }} style={{ marginTop: '15px', padding: '8px 16px', cursor: 'pointer' }}>
                                Đóng hóa đơn
                            </button>
                        </div>
                    )}
                </div>
            </div>
        );
    }

    // MÀN HÌNH 2: GIỎ HÀNG
    if (isCartOpen) {
        return (
            <div className="modern-container">
                <div className="cart-page">
                    <div className="cart-header">
                        <h2>🛒 Giỏ hàng</h2>
                        <button className="btn-back" onClick={() => setIsCartOpen(false)}>Tiếp tục mua sắm</button>
                    </div>
                    {cart.length === 0 ? <div className="empty-cart">Giỏ hàng trống.</div> : (
                        <div className="cart-content">
                            <div className="cart-items">
                                {cart.map((item) => (
                                    <div key={item.product.productId} className="cart-item">
                                        <div className="item-info">
                                            <h4>{item.product.name}</h4>
                                            <p>{item.product.currentUnitPrice.toLocaleString()} ₫</p>
                                        </div>
                                        <div className="item-controls">
                                            <button onClick={() => updateQuantity(item.product.productId, -1)}>-</button>
                                            <span>{item.quantity}</span>
                                            <button onClick={() => updateQuantity(item.product.productId, 1)}>+</button>
                                            <button className="btn-remove" onClick={() => removeFromCart(item.product.productId)}>🗑️</button>
                                        </div>
                                    </div>
                                ))}
                                <h3>Tổng tiền: {cartTotal.toLocaleString()} ₫</h3>
                            </div>
                            <div className="checkout-form">
                                <h3>Thông tin nhận hàng</h3>
                                <form onSubmit={handleSubmitOrder}>
                                    <input type="text" placeholder="Họ và tên" required value={buyerName} onChange={(e) => setBuyerName(e.target.value)} />
                                    <input type="tel" placeholder="Số điện thoại" required value={buyerPhone} onChange={(e) => setBuyerPhone(e.target.value)} />
                                    <button type="submit" className="btn-checkout">Tạo đơn</button>
                                </form>
                            </div>
                        </div>
                    )}
                </div>
            </div>
        );
    }

    // MÀN HÌNH 3: TRANG CHỦ (DANH SÁCH SẢN PHẨM)
    return (
        <div className="modern-container">
            <nav className="navbar">
                <h1 className="logo">⚡ TechStore</h1>
                <div className="cart-icon" onClick={() => setIsCartOpen(true)}>
                    🛒 Giỏ hàng {cart.length > 0 && <span className="cart-badge">{cart.reduce((sum, item) => sum + item.quantity, 0)}</span>}
                </div>
            </nav>
            <div className="products-layout">
                <h2 className="section-title">Sản phẩm</h2>
                <div className="product-grid">
                    {products.map(product => (
                        <div key={product.productId} className="product-card">
                            <div className="product-image">
                                <img src={`https://placehold.co/300x200?text=${encodeURIComponent(product.name)}`} alt={product.name} />
                            </div>
                            <div className="product-details">
                                <h3>{product.name}</h3>
                                <p className="price">{product.currentUnitPrice.toLocaleString()} ₫</p>
                                <p className="stock">Tồn kho: {product.stockQuantity} {product.unit}</p>
                                <button
                                    onClick={() => addToCart(product)}
                                    disabled={product.stockQuantity === 0}
                                    className={product.stockQuantity === 0 ? 'btn-out-of-stock' : 'btn-add-cart'}
                                >
                                    {product.stockQuantity === 0 ? 'Hết hàng' : 'Thêm vào giỏ'}
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
};

export default Home;