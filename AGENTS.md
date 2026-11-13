# Hướng dẫn làm việc với ecommerce-gpay

## Phạm vi và cách chia hướng dẫn
- File này áp dụng cho toàn repository; hướng dẫn trong thư mục con bổ sung cho phần đó.
- Giữ mỗi `AGENTS.md` tối đa 150 dòng. Khi dài hơn, chuyển chi tiết xuống thư mục liên quan.
- Backend: đọc `demo/AGENTS.md` trước khi sửa Java, Maven hoặc Dockerfile backend.
- Frontend: đọc `frontend/AGENTS.md` trước khi sửa React, Vite hoặc Dockerfile frontend.
- CI/CD: đọc `ci/AGENTS.md` khi sửa `Jenkinsfile`, hai file Compose hoặc script CI.
- Tài liệu vận hành chi tiết nằm trong `docs/`; không chép toàn bộ runbook vào hướng dẫn agent.

## Bản đồ dự án
- `demo/`: Spring Boot 4.1.2, Java 21, Maven Wrapper, Spring Security/JPA/Redis/WebSocket.
- `frontend/`: React 19, Vite 8, Axios, SockJS/STOMP, Vitest và oxlint.
- `compose.yaml`: MySQL 8.4, Redis 7, backend và frontend của bản triển khai.
- `compose.ci.yaml`: dịch vụ test và candidate độc lập, không publish cổng ra host.
- `Jenkinsfile`: pipeline Linux, build/test bằng Docker trên Ubuntu VM.
- `ci/`: kiểm tra môi trường runner, smoke test và thu thập log.
- `docs/CI_TEST_STRATEGY.md`: ma trận test và cổng chất lượng.
- `docs/CI_FAILURE_RUNBOOK.md`: chẩn đoán build/deploy lỗi và rollback.
- `docs/JENKINS_ACCESS.md`: truy cập VMware Ubuntu, SSH từ PowerShell và Jenkins.
- `package.json` ở root không có script build; chạy npm trong `frontend/`.

## Luồng nghiệp vụ cần giữ
- Đăng ký/đăng nhập, JWT access token, refresh cookie và quản lý phiên.
- USER mua hàng; ADMIN quản lý sản phẩm/hóa đơn; quyền thật được kiểm tra ở backend.
- MySQL lưu nghiệp vụ; Redis hỗ trợ tồn kho; WebSocket đẩy cập nhật sau giao dịch.
- G-Pay nhận yêu cầu thanh toán và gọi webhook có kiểm tra chữ ký RSA.
- Khi thay đổi API, đối chiếu cả DTO/controller và client React cùng test liên quan.
- Giữ kiểm tra chủ sở hữu hóa đơn, tính idempotent webhook và chống overselling.

## Quy ước thay đổi
- Đọc mã và test hiện tại trước khi sửa; không đổi kiến trúc hoặc dependency ngoài phạm vi.
- Lưu mã/tài liệu bằng UTF-8; giữ đúng hoa thường đường dẫn vì Jenkins chạy Linux.
- Script `ci/*.sh` dùng POSIX shell và LF, không phụ thuộc Bash.
- Không sửa file sinh tự động trong `target/`, `dist/`, `coverage/`, `node_modules/`.
- Giữ thay đổi cục bộ của người dùng, kể cả `.vscode/`; không tự commit/push nếu chưa được yêu cầu.
- Cập nhật tài liệu liên quan khi đổi lệnh chạy, biến môi trường hoặc luồng deploy.

## Bảo mật và dữ liệu
- Không đọc/in giá trị `.env`, private key, Jenkins credential hoặc token để đưa vào log/tài liệu.
- Không commit secret; dùng Jenkins Secret text cho các ID được liệt kê trong `ci/AGENTS.md`.
- Không dùng database `shop` hay volume của project `demo1` để chạy integration test.
- Không chạy `down --volumes` với Compose production; chỉ xóa volume của project CI tạm.
- Test thanh toán phải mock dịch vụ ngoài, không tạo giao dịch G-Pay thật.
- Không nới authentication/CORS hoặc hạ coverage để làm CI xanh.

## Kiểm tra trước khi bàn giao
- Frontend: tại `frontend/`, chạy `npm ci`, `npm run lint`, `npm run test:ci`, `npm run build`.
- Backend unit: tại `demo/`, chạy `.\mvnw.cmd -B -Dgroups=unit test` trên PowerShell.
- Trên Linux dùng `sh mvnw -B -Dgroups=unit test`; integration cần MySQL/Redis CI riêng.
- Đổi CI: kiểm tra shell/Compose và chạy pipeline trên Ubuntu; xem thêm `ci/AGENTS.md`.
- Chạy kiểm tra phù hợp phần sửa; nêu rõ kiểm tra nào chưa chạy và nguyên nhân thực tế.
- Trước kết thúc, xem `git diff --check`, diff và trạng thái Git; báo kết quả và phần còn chờ.
