# ecommerce-gpay

Ứng dụng thương mại điện tử tích hợp G-Pay, quản lý phiên JWT, hóa đơn và tồn kho theo thời gian thực.

| Phần | Công nghệ / vị trí |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1.2, Maven — `demo/` |
| Frontend | React 19, Vite 8 — `frontend/` |
| Dữ liệu | MySQL 8.4, Redis 7; cập nhật giao diện qua SockJS/STOMP |
| CI/CD | Jenkins trên Ubuntu VMware, Docker Compose — `Jenkinsfile`, `ci/` |

## Hướng dẫn dự án

- [AGENTS.md](AGENTS.md): tổng quan và quy tắc chung.
- [Backend](demo/AGENTS.md), [frontend](frontend/AGENTS.md), [CI/CD](ci/AGENTS.md): hướng dẫn theo phạm vi, mỗi file tối đa 150 dòng.
- [Truy cập Jenkins / SSH từ PowerShell](docs/JENKINS_ACCESS.md).
- [Chiến lược kiểm thử](docs/CI_TEST_STRATEGY.md) và [xử lý lỗi CI/CD](docs/CI_FAILURE_RUNBOOK.md).

## Truy cập môi trường VMware

Đã xác minh ngày 2026-11-13: Jenkins tại <http://192.168.179.129:8080/login> phản hồi HTTP 200.

```powershell
ssh ndquang@192.168.179.129
```

Frontend mặc định theo Compose: <http://192.168.179.129:8088>.
Backend health mặc định: <http://192.168.179.129:8081/actuator/health>.
IP có thể đổi theo DHCP; xem runbook để lấy lại IP và kiểm tra dịch vụ.

## Kiểm tra cục bộ trên PowerShell

```powershell
cd frontend
npm ci
npm run lint
npm run test:ci
npm run build
cd ../demo
.\mvnw.cmd -B -Dgroups=unit test
```

Integration test cần MySQL/Redis tạm của `compose.ci.yaml`; xem lệnh trong runbook Jenkins.
Không chạy test dùng `create-drop` vào database đang triển khai.

## Phát triển và triển khai

Frontend: chạy `npm run dev` trong `frontend/` (HTTPS cổng 5173).
Backend: chạy Maven goal `spring-boot:run` trong `demo/` sau khi cấu hình MySQL, Redis,
`APP_JWT_SECRET` và G-Pay qua biến môi trường. SSL mặc định cần keystore local;
keystore và secret không nằm trong Git. Cấu hình HTTP trong Compose được đặt rõ ràng.

Production Compose dùng project `demo1`, frontend cổng 8088 và backend cổng 8081.
Pipeline kiểm tra runner, lint/test/build, integration/API và candidate smoke trước khi chờ duyệt deploy.
Sau deploy có smoke test và bước duyệt rollback khi cần; chỉ release đạt kiểm tra mới được gắn tag `stable`.
Parameter `DEPLOY_HOST` đổi URL API/CORS khi VM đổi IP; Docker daemon của Jenkins agent thực hiện deploy.
Không xóa volume production khi dọn môi trường test. Không đưa secret vào biến `VITE_*` hoặc commit `.env`.

## Chuẩn bị production VPS

Xem [PRODUCTION.md](docs/PRODUCTION.md) để dùng hostname sslip.io miễn phí, cấu hình VPS/Docker/SSH,
GHCR, HTTPS tự động, backup/restore và chạy Jenkins với `DEPLOY_TARGET=production`.
Mặc định pipeline vẫn deploy VMware. Production dùng Compose độc lập, profile prod/Flyway và G-Pay tắt cho đến khi được cấu hình.
