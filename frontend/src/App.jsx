import React from 'react';
import Home from './pages/Home.jsx';
import AuthPage from './pages/AuthPage.jsx';
import UserBar from './components/UserBar.jsx';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import './styles/auth.css';

// Chưa đăng nhập -> trang đăng nhập/đăng ký. Đã đăng nhập -> trang chính như cũ.
function Shell() {
  const { user, loading } = useAuth();

  if (loading) {
    return <p className="auth-loading" role="status">Đang kiểm tra phiên đăng nhập…</p>;
  }
  if (!user) {
    return <AuthPage />;
  }

  return (
      <div>
        <UserBar />
        <Home />
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
