import { useAuth } from '../context/AuthContext.jsx';
import '../styles/auth.css';

export default function UserBar() {
  const { user, logout } = useAuth();
  if (!user) return null;

  return (
    <header className="auth-userbar">
      <span>
        Xin chào, <strong>{user.fullName}</strong>
        {user.role === 'ADMIN' && <span className="auth-userbar-role"> (Quản trị viên)</span>}
      </span>
      <button type="button" onClick={logout}>Đăng xuất</button>
    </header>
  );
}
