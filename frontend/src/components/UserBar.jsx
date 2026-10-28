import { useAuth } from '../context/AuthContext.jsx';
import '../styles/auth.css';

// view: 'shop' | 'admin'. Nút chuyển sang trang quản trị chỉ hiện với ADMIN (chỉ là tiện lợi cho giao diện:
// quyền thật được backend kiểm tra ở mọi API /api/admin/**).
export default function UserBar({ view = 'shop', onChangeView }) {
  const { user, logout, logoutAll } = useAuth();
  if (!user) return null;

  const isAdmin = user.role === 'ADMIN';

  return (
    <header className="auth-userbar">
      <span>
        Xin chào, <strong>{user.fullName}</strong>
        {isAdmin && <span className="auth-userbar-role"> (Quản trị viên)</span>}
      </span>
      {isAdmin && onChangeView && (
        <button type="button" onClick={() => onChangeView(view === 'admin' ? 'shop' : 'admin')}>
          {view === 'admin' ? 'Về cửa hàng' : 'Quản trị'}
        </button>
      )}
      <button type="button" onClick={logout}>Đăng xuất</button>
      <button type="button" className="auth-userbar-link" onClick={logoutAll}>Đăng xuất mọi thiết bị</button>
    </header>
  );
}
