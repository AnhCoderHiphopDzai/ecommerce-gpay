# Frontend: React / Vite

## Cấu trúc
- Áp dụng trong `frontend/`, cùng với hướng dẫn root.
- React 19, JSX, ES modules; cấu hình build/test ở `vite.config.js`.
- `src/App.jsx`: AuthProvider và chuyển giao diện shop/admin theo state.
- `src/pages/`: `AuthPage`, `Home`, `AdminPage` và test tương ứng.
- `src/components/UserBar.jsx`: thông tin người dùng, điều hướng và thao tác phiên.
- `src/context/AuthContext.jsx`: đăng nhập, khôi phục phiên, logout và logout-all.
- `src/context/auth-context.js`: hook/context dùng chung.
- `src/api/http.js`: API base URL, token, Axios interceptor, fetch và refresh/retry.
- `src/api/Admin.js`: client quản trị; `src/styles/` và CSS theo trang chứa style hiện có.

## Quy tắc triển khai
- Theo cách tổ chức component/CSS hiện tại; không thêm router hay state library nếu chưa cần.
- Dùng lớp API chung cho request được xác thực, không tự dựng cơ chế token/refresh thứ hai.
- Không đính kèm JWT vào origin ngoài backend; giữ test chống rò token.
- Refresh cookie dùng credentials; giữ chống retry 401 vô hạn và xử lý refresh đồng thời.
- Backend là nơi quyết định quyền; ẩn nút ADMIN không thay thế authorization phía server.
- Đồng bộ thay đổi payload/response với DTO và controller trong `../demo/`.
- Giữ luồng giỏ hàng, receipt và cập nhật tồn kho/thanh toán qua SockJS/STOMP.
- Cleanup subscription/WebSocket/effect khi unmount; tránh đăng ký listener trùng.
- Giữ thông báo/validation tiếng Việt và thuộc tính accessibility để test theo vai trò/nhãn.
- Tên file import phải đúng hoa thường, ví dụ `styles/Admin.css`, để build trên Linux.

## Lệnh kiểm tra
```powershell
cd frontend
npm ci
npm run lint
npm run test:ci
npm run build
```
- Dùng lockfile hiện có; không chạy npm tại root để build frontend.
- `npm run dev` mở Vite HTTPS cổng 5173 với chứng chỉ local tự ký.
- `npm run test:watch` dành cho phát triển; CI dùng Vitest một lượt.
- Test dùng jsdom, Testing Library và `src/test/setup.js`.
- JUnit ghi vào `test-results/junit.xml`; coverage ghi vào `coverage/`.
- Coverage tối thiểu: statements 50%, lines 50%, branches 35%, functions 40%.
- Không giảm ngưỡng để che regression; thêm kiểm tra hành vi tại test gần component/client liên quan.
- Mock API/WebSocket/thanh toán trong test; không gọi dịch vụ triển khai thật.

## Cấu hình build / triển khai
- `VITE_API_BASE_URL` được nhúng lúc build; mặc định local là `https://localhost:8081`.
- Jenkins truyền `http://<DEPLOY_HOST>:<BACKEND_PORT>` qua Docker build arg.
- Đổi IP VM/API sau build đòi hỏi build lại frontend; đổi env container Nginx không đủ.
- Docker dùng Node 22 build, Nginx phục vụ `dist/` tại cổng 80; Compose publish cổng 8088 mặc định.
- `nginx.conf` có SPA fallback; hiện browser gọi backend trực tiếp, không qua proxy `/api` Nginx.
- Không đặt secret trong biến `VITE_*` vì chúng có thể xuất hiện trong bundle trình duyệt.
- Không sửa/commit file sinh trong `dist/`, `coverage/`, `test-results/`, `node_modules/`.
