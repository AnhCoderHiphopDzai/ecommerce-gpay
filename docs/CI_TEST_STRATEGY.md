# Chiến lược kiểm thử CI cho demo1

Mục tiêu của pipeline là phát hiện lỗi ở cổng rẻ và sớm nhất; không deploy một image chỉ vì nó build được.
Mỗi thay đổi phải đi qua các cổng bên dưới theo thứ tự trong `Jenkinsfile`.

## Ma trận kiểm thử

| Cổng | Môi trường | Kiểm tra chính | Khi lỗi |
| --- | --- | --- | --- |
| Frontend quality | Node container tạm | `npm ci`, oxlint, Vitest, coverage, Vite build | Dừng trước khi build image |
| Backend unit | JVM/Maven container tạm | JWT, password policy, refresh-token policy, rate limit, G-Pay RSA, webhook | Dừng trước khi khởi động database |
| Backend integration/API | MySQL + Redis Compose tạm | đăng ký/đăng nhập/refresh/logout, CORS, quyền USER/ADMIN, CRUD sản phẩm, giỏ hàng, quyền sở hữu hóa đơn, chống overselling | Xóa volume tạm, lưu log/JUnit |
| Candidate smoke | Toàn bộ Compose tạm | actuator health, frontend, API công khai, API cần đăng nhập, CORS | Không hiện nút deploy |
| Post-deploy smoke | Container vừa triển khai | Các kiểm tra HTTP giống candidate ở cổng thật của VM | Đánh dấu lỗi và yêu cầu xác nhận rollback |

## Các test hiện có

### Backend unit (`@Tag("unit")`)

- `PasswordPolicyTest`: độ dài, mật khẩu phổ biến/lặp lại và thông tin cá nhân.
- `RefreshTokenPolicyTest`: token hết hạn, bị thu hồi, grace period và phát hiện reuse.
- `JwtServiceTest`: chữ ký JWT, claim phiên và secret quá yếu.
- `AuthRateLimiterTest` + `AuthControllerTest`: đếm/tháo rate limit và HTTP 429.
- `GpaySecurityUtilTest`: ký/xác thực RSA, payload bị sửa và chữ ký không hợp lệ.
- `PaymentControllerTest`: webhook không hợp lệ không được đổi hóa đơn; callback dùng URL frontend cấu hình.

### Backend integration/API (`@Tag("integration")`)

- `ApiContractIntegrationTest`: health, security headers, public/protected endpoint, auth session, validation mật khẩu, CORS, phân quyền admin, đồng bộ Redis, tạo/xem hóa đơn và 404 thay vì lộ dữ liệu.
- Các test `Invoice*IntegrationTest`: webhook đồng thời, đơn trùng, nhiều sản phẩm và bất biến tồn kho MySQL/Redis.

Các test integration luôn dùng profile `test`, database `shop_ci`, Redis và volume do Compose project riêng tạo. Chúng không dùng volume `demo1_mysql-data`/`demo1_redis-data` đang chạy production.

### Frontend

- `http.test.js`: header JWT, không rò token sang origin khác, refresh và retry 401.
- `AuthContext.test.jsx`, `AuthPage.test.jsx`, `UserBar.test.jsx`: đăng nhập, phiên, logout-all và validation form.
- `Home.test.jsx`: tải hàng, giỏ hàng, tạo hóa đơn và receipt.
- `AdminPage.test.jsx`: cấm USER vào trang quản trị và tạo sản phẩm bằng dữ liệu đã chuẩn hóa.

Vitest đặt baseline quality gate: statement/line 50%, branch 35%, function 40%. Mức này được đo từ suite hiện tại; khi bổ sung test cần tăng dần, không hạ ngưỡng để che regression.

## Lệnh chạy cục bộ

```powershell
cd frontend
npm ci
npm run lint
npm run test:ci
npm run build

cd ../demo
./mvnw.cmd -Dgroups=unit test
```

Integration/API test cần Docker Engine vì cần MySQL và Redis. Jenkins chạy chúng bằng `compose.ci.yaml`; không chạy trực tiếp với database thật.

## Điều không chạy tự động

- Pipeline không gọi G-Pay sandbox thật. Gọi dịch vụ thanh toán bên ngoài làm test không xác định và có thể phát sinh giao dịch; chữ ký và webhook được kiểm thử bằng key sinh tạm/mocking.
- Không rollback MySQL tự động. Image frontend/backend có tag `stable` để rollback thủ công có phê duyệt; schema/data phải có migration và kế hoạch khôi phục riêng.

Xem [CI_FAILURE_RUNBOOK.md](CI_FAILURE_RUNBOOK.md) khi một cổng thất bại hoặc smoke sau deploy đỏ.
