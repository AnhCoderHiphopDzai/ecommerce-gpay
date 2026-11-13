# Backend: Spring Boot / Java

## Cấu trúc
- Áp dụng trong `demo/`, cùng với hướng dẫn root.
- Java 21; Spring Boot và phiên bản dependency được quản lý trong `pom.xml`.
- Entry point: `src/main/java/com/example/DemoApplication.java` quét package `com.example`.
- Mã nghiệp vụ ở `com.example.backend`: `controller`, `dto`, `service`, `dao`, `model`.
- `security/`: JWT, phiên đăng nhập, mật khẩu, rate limit và security headers.
- `config/`: Spring Security/CORS, WebSocket, scheduler và khởi tạo dữ liệu Redis/admin.
- Test nằm ở cả `com.example.backend` và `com.example.demo`; không bỏ sót package thứ hai.
- Cấu hình runtime: `src/main/resources/application.properties`.
- Cấu hình test: `src/test/resources/application-test.properties`.

## Hợp đồng nghiệp vụ
- Controller nhận/kiểm tra request; nghiệp vụ và transaction thuộc service.
- Dùng DTO cho hợp đồng API; tránh lộ password hash, refresh token hoặc thông tin nhạy cảm.
- `/api/products` GET và `/actuator/health` công khai; `/api/admin/**` chỉ ADMIN.
- Giữ `@EnableMethodSecurity`, JWT/session validation và phân biệt 401 với 403.
- Truy cập hóa đơn phải kiểm tra chủ sở hữu hoặc ADMIN; người không có quyền nhận 404.
- Số tiền thanh toán lấy từ hóa đơn trong database, không tin amount do client gửi.
- Webhook G-Pay phải xác thực chữ ký trước khi cập nhật hóa đơn/tồn kho.
- Không log raw webhook hoặc key, token, dữ liệu tài khoản thanh toán.

## Tồn kho và đồng thời
- Đọc kỹ `InvoiceService`, `ProductRepository`, `InvoiceRepository` trước khi đổi luồng thanh toán.
- Giữ lock hóa đơn, xử lý webhook trùng và trạng thái kết thúc của đơn.
- Trừ tồn kho Redis bằng Lua và MySQL bằng phép cập nhật atomic; không thay bằng read-save đơn giản.
- Giữ bù tồn kho Redis khi transaction MySQL rollback.
- Gộp dòng theo sản phẩm và xử lý theo thứ tự ID để giảm deadlock.
- WebSocket thông báo sau commit; không phát sự kiện từ transaction chưa hoàn tất.
- Không flush Redis hay reset schema ngoài dịch vụ test tạm.

## Kiểm thử
```powershell
cd demo
.\mvnw.cmd -B -Dgroups=unit test
```
- Trên Ubuntu: `sh mvnw -B -Dgroups=unit test`.
- Unit test dùng `@Tag("unit")`, không cần MySQL/Redis và không gọi G-Pay thật.
- Integration test dùng `@Tag("integration")`, `@ActiveProfiles("test")` và dịch vụ CI tạm.
- Không chạy `mvn test` toàn bộ vào database đang triển khai; profile test dùng `create-drop`.
- Chuẩn integration nằm trong `compose.ci.yaml`; xem `../docs/JENKINS_ACCESS.md` để chạy riêng.
- Khi sửa auth/API, xem `ApiContractIntegrationTest` và các test controller/security.
- Khi sửa tồn kho, chạy cả `InvoiceServiceOversellingIntegrationTest` và `MultiProductOversellingIntegrationTest`.
- JUnit: `target/surefire-reports/`; coverage: `target/site/jacoco/`.
- JaCoCo gộp coverage của hai lần Maven unit/integration; không `clean` xen giữa hai cổng CI.
- Khóa phiên bản plugin build được thêm ngoài quản lý của Spring Boot parent.

## Runtime / Docker
- Backend lắng nghe cổng 8081; Docker healthcheck gọi `/actuator/health`.
- `Dockerfile` dùng Maven/JDK 21 build và JRE 21 runtime; CI chạy test trước khi build image.
- Runtime cần MySQL, Redis, JWT secret và cấu hình G-Pay từ môi trường.
- SSL mặc định bật nhưng keystore không có trong Git; local cần tự cấp keystore hoặc tắt SSL rõ ràng.
- Compose tắt SSL cho HTTP nội bộ VM; CORS/frontend URL phải khớp địa chỉ frontend.
- Profile prod: Flyway tạo schema mới, Hibernate validate; profile VM/default vẫn update và Flyway tắt.
- Không sửa migration đã áp dụng; thêm phiên bản mới. Không tự baseline database cũ.
- Profile prod mặc định tắt payments; unit test phải bảo đảm không gọi G-Pay/cập nhật hóa đơn khi tắt.
- Public key webhook có thể cấp qua gpay.public-key-location; production đọc file ngoài image.
- Rollback image không phục hồi schema/dữ liệu; xem `../docs/PRODUCTION.md`.
- `application-test.properties` là test resource, không được đóng gói trong image runtime.
