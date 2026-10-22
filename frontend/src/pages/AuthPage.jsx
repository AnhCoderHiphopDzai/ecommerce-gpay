import { useState } from 'react';
import { useAuth } from '../context/AuthContext.jsx';
import '../styles/auth.css';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_RE = /^\+?\d{9,14}$/;
// Thứ tự các ô trên form, dùng để đưa con trỏ về ô lỗi đầu tiên
const FIELD_ORDER = ['fullName', 'phone', 'email', 'password', 'confirm'];

const COPY = {
  login: {
    title: 'Chào mừng quay lại',
    note: 'Đơn hàng và vé được lưu theo tài khoản, chỉ bạn mới xem được.',
    submit: 'Đăng nhập',
    busy: 'Đang đăng nhập…',
  },
  register: {
    title: 'Tạo tài khoản',
    note: 'Dùng email này để đăng nhập ở những lần sau.',
    submit: 'Tạo tài khoản',
    busy: 'Đang tạo tài khoản…',
  },
};

const EMPTY = { fullName: '', phone: '', email: '', password: '', confirm: '' };

function validate(mode, v) {
  const errors = {};
  if (mode === 'register') {
    if (!v.fullName.trim()) errors.fullName = 'Nhập họ và tên của bạn';
    if (!PHONE_RE.test(v.phone.trim())) errors.phone = 'Nhập số điện thoại 9-14 chữ số, có thể bắt đầu bằng +';
  }
  if (!EMAIL_RE.test(v.email.trim())) errors.email = 'Nhập email hợp lệ, ví dụ ten@example.com';
  if (mode === 'register') {
    if (v.password.length < 8 || v.password.length > 72) errors.password = 'Mật khẩu cần từ 8 đến 72 ký tự';
    if (v.confirm !== v.password) errors.confirm = 'Mật khẩu nhập lại chưa khớp';
  } else if (!v.password) {
    errors.password = 'Nhập mật khẩu';
  }
  return errors;
}

function focusField(name) {
  requestAnimationFrame(() => document.getElementById(`auth-${name}`)?.focus());
}

function TextField({ name, label, error, hint, trailing, ...inputProps }) {
  const id = `auth-${name}`;
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className="auth-field">
      <label htmlFor={id}>{label}</label>
      <div className="auth-input-wrap">
        <input
          id={id}
          name={name}
          aria-invalid={error ? 'true' : undefined}
          aria-describedby={describedBy}
          className={trailing ? 'has-trailing' : undefined}
          {...inputProps}
        />
        {trailing}
      </div>
      {error && <p className="auth-message auth-message--error" id={`${id}-error`}>{error}</p>}
      {!error && hint && <p className="auth-message" id={`${id}-hint`}>{hint}</p>}
    </div>
  );
}

export default function AuthPage() {
  const { login, register, sessionExpired } = useAuth();
  const [mode, setMode] = useState('login');
  const [values, setValues] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [showPassword, setShowPassword] = useState(false);

  const copy = COPY[mode];
  const isRegister = mode === 'register';

  function setField(name) {
    return (event) => {
      setValues((prev) => ({ ...prev, [name]: event.target.value }));
      if (errors[name]) setErrors((prev) => ({ ...prev, [name]: undefined }));
    };
  }

  function switchMode(next) {
    if (next === mode) return;
    setMode(next);
    setErrors({});
    setFormError('');
    setShowPassword(false);
    setValues((prev) => ({ ...prev, password: '', confirm: '' }));
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (submitting) return;

    const found = validate(mode, values);
    setErrors(found);
    setFormError('');
    const firstInvalid = FIELD_ORDER.find((name) => found[name]);
    if (firstInvalid) {
      focusField(firstInvalid);
      return;
    }

    setSubmitting(true);
    try {
      const email = values.email.trim();
      if (isRegister) {
        await register({ fullName: values.fullName.trim(), phone: values.phone.trim(), email, password: values.password });
      } else {
        await login({ email, password: values.password });
      }
      // Thành công: AuthProvider đã có user nên App tự chuyển sang trang chính
    } catch (error) {
      const fieldErrors = error.fieldErrors || {};
      setErrors(fieldErrors);
      setFormError(Object.keys(fieldErrors).length ? '' : error.message);
      const firstServerError = FIELD_ORDER.find((name) => fieldErrors[name]);
      if (firstServerError) focusField(firstServerError);
    } finally {
      setSubmitting(false);
    }
  }

  const passwordToggle = (
    <button
      type="button"
      className="auth-toggle"
      onClick={() => setShowPassword((s) => !s)}
      aria-pressed={showPassword}
      aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
    >
      {showPassword ? 'Ẩn' : 'Hiện'}
    </button>
  );

  return (
    <main className="auth-screen">
      <div className="auth-ticket">
        <section className="auth-stub" aria-labelledby="auth-title">
          <h1 id="auth-title">{copy.title}</h1>
          <p>{copy.note}</p>
        </section>

        <div className="auth-main">
          <div className="auth-switch" role="group" aria-label="Đăng nhập hoặc đăng ký">
            <button type="button" aria-pressed={!isRegister} onClick={() => switchMode('login')}>Đăng nhập</button>
            <button type="button" aria-pressed={isRegister} onClick={() => switchMode('register')}>Đăng ký</button>
          </div>

          {sessionExpired && !isRegister && !formError && (
            <p className="auth-banner auth-banner--info" role="status">
              Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.
            </p>
          )}
          {formError && <p className="auth-banner auth-banner--error" role="alert">{formError}</p>}

          <form onSubmit={handleSubmit} noValidate>
            {isRegister && (
              <>
                <TextField name="fullName" label="Họ và tên" autoComplete="name"
                  value={values.fullName} onChange={setField('fullName')} error={errors.fullName} />
                <TextField name="phone" label="Số điện thoại" type="tel" inputMode="tel" autoComplete="tel"
                  value={values.phone} onChange={setField('phone')} error={errors.phone} />
              </>
            )}

            <TextField name="email" label="Email" type="email" autoComplete="email"
              value={values.email} onChange={setField('email')} error={errors.email} />

            <TextField name="password" label="Mật khẩu"
              type={showPassword ? 'text' : 'password'}
              autoComplete={isRegister ? 'new-password' : 'current-password'}
              value={values.password} onChange={setField('password')} error={errors.password}
              hint={isRegister ? 'Từ 8 ký tự trở lên' : undefined}
              trailing={passwordToggle} />

            {isRegister && (
              <TextField name="confirm" label="Nhập lại mật khẩu"
                type={showPassword ? 'text' : 'password'} autoComplete="new-password"
                value={values.confirm} onChange={setField('confirm')} error={errors.confirm} />
            )}

            <button type="submit" className="auth-submit" disabled={submitting} aria-busy={submitting}>
              {submitting ? copy.busy : copy.submit}
            </button>
          </form>
        </div>
      </div>
    </main>
  );
}
