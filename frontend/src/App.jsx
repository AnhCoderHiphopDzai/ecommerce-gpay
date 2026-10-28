import React, { useState } from 'react';
import Home from './pages/Home.jsx';
import AuthPage from './pages/AuthPage.jsx';
import AdminPage from './pages/AdminPage.jsx';
import UserBar from './components/UserBar.jsx';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import './styles/auth.css';

// Chưa đăng nhập -> trang đăng nhập/đăng ký. Đã đăng nhập -> trang chính như cũ.
// ADMIN có thêm nút "Quản trị" để chuyển sang trang quản lý sản phẩm và hóa đơn.
function Shell() {
  const { user, loading } = useAuth();
  const [view, setView] = useState('shop');

  if (loading) {
    return <p className="auth-loading" role="status">Đang kiểm tra phiên đăng nhập…</p>;
  }
  if (!user) {
    return <AuthPage />;
  }

  // Giao diện chỉ là lớp tiện lợi; quyền thật do backend kiểm tra ở mọi API /api/admin/**
  const showAdmin = view === 'admin' && user.role === 'ADMIN';

  return (
      <div>
        <UserBar view={showAdmin ? 'admin' : 'shop'} onChangeView={setView} />
        {showAdmin ? <AdminPage /> : <Home />}
      </div>
  );
}

function App() {
  return (
      <AuthProvider>
        <Shell />
      </AuthProvider>
  );
}

export default App;
