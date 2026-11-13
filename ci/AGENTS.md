# CI/CD: Jenkins / Docker Compose

## Phạm vi
- Áp dụng cho `ci/`; root yêu cầu đọc file này khi sửa `Jenkinsfile` hoặc Compose.
- Đọc `../docs/CI_TEST_STRATEGY.md`, `CI_FAILURE_RUNBOOK.md` và `JENKINS_ACCESS.md` trong `../docs/`.
- Agent Jenkins phải chạy Linux trên Ubuntu VM, có Docker Engine/Compose v2 và curl.
- Pipeline chạy Docker CLI trực tiếp; không dùng Jenkins `docker.image(...).inside`.
- Jenkins workspace bind-mount phải tồn tại tại cùng đường dẫn đối với Docker daemon.
- Build và deploy dùng cùng daemon trên VM; `DEPLOY_HOST` là địa chỉ browser truy cập, không phải đích SSH.
- Nếu Jenkins ở container, `127.0.0.1` và workspace mount cần cấu hình riêng; không giả định pipeline chạy được ngay.

## Địa chỉ và truy cập
- Đã kiểm tra ngày 2026-11-13: Jenkins `http://192.168.179.129:8080/login` trả HTTP 200.
- SSH từ Windows: `ssh ndquang@192.168.179.129`; tài khoản lấy từ lịch sử PowerShell.
- Frontend mặc định `http://192.168.179.129:8088`, backend `http://192.168.179.129:8081`.
- Windows VMnet8 là `192.168.179.1`; IP guest có thể thay đổi theo DHCP VMware.
- Không ghi mật khẩu/token vào repository; SSH và Jenkins có tài khoản đăng nhập riêng.
- Quy trình kết nối lại, tunnel và kiểm tra dịch vụ nằm trong `../docs/JENKINS_ACCESS.md`.
- Job đã xác nhận từ log người dùng: `demo1-ci-cd`, build #15 SUCCESS, commit `e122cf51a289`.
- Build #15 deploy và smoke đạt; bản sửa cờ rollback trong working tree chưa được chạy trên Jenkins.

## Trình tự pipeline phải giữ
1. Checkout và xóa báo cáo sinh từ build cũ.
2. Kiểm tra runner, shell script và cấu hình Compose.
3. Frontend lint, Vitest/coverage và build.
4. Backend unit test theo tag `unit`.
5. Backend integration/API theo tag `integration` với MySQL/Redis tạm.
6. Build hai candidate image bằng tag `build-<BUILD_NUMBER>-<SHA12>`.
7. Smoke candidate trên network CI riêng.
8. Giữ baseline `stable`, rồi chờ `Approve deployment`.
9. Deploy candidate và smoke trên cổng thật của VM.
10. Khi smoke lỗi, giữ build đỏ và yêu cầu duyệt rollback nếu có đủ cặp stable.
11. Chỉ gắn tag stable cho candidate sau khi smoke sau deploy đạt.

## Credentials và dữ liệu
- Jenkins Secret text IDs: `APP_JWT_SECRET`, `SPRING_DATASOURCE_PASSWORD`,
  `GPAY_CLIENT_SECRET`, `GPAY_PRIVATE_KEY`, `GPAY_CERTIFICATE`.
- Chỉ bind secret production ở stage deploy/rollback; không đưa secret vào Groovy interpolation.
- Validate Compose dùng giá trị giả và `config --quiet`; không in cấu hình có secret.
- CI password/JWT trong Jenkinsfile chỉ là dữ liệu test, không được dùng để deploy.
- Production project là `demo1`; volume MySQL/Redis được giữ qua deploy.
- CI project là `demo1-ci-${BUILD_NUMBER}`; không publish cổng host và phải dọn sau build.
- `down --volumes` chỉ được áp dụng cho CI project; không dùng với `demo1`.
- Không gọi G-Pay thật trong smoke hoặc test pipeline.

## Script và kiểm tra
- Dùng `#!/bin/sh`, `set -eu` khi thích hợp và cú pháp POSIX; chạy `sh -n ci/*.sh` theo từng file.
- Lưu shell/Jenkinsfile bằng LF theo `.gitattributes`; gọi script bằng `sh ci/<file>.sh`.
- `preflight.sh`: xác minh công cụ, Docker Linux và đường dẫn bind-mount trước quality gates.
- `smoke-candidate.sh`: gọi service qua tên `backend`/`frontend` trong network CI.
- `smoke-deployed.sh`: health/products/frontend, anonymous 401 và CORS ở bản deploy.
- `collect-compose-diagnostics.sh`: tìm container bằng project label, không cần nạp secret production.
- JUnit: `demo/target/surefire-reports/*.xml`, `frontend/test-results/*.xml`.
- Artifact: `artifacts/**`, `frontend/coverage/**`, `demo/target/site/jacoco/**`.
- Không archive `.env`, key hay output `docker inspect` đầy đủ có thể lộ env.
- Khi sửa Compose, validate cả hai file với biến test; thực thi integration/smoke trên Docker Linux.
- Không hạ quality gates, bỏ manual input hoặc tự phê duyệt deployment/rollback khi chưa được yêu cầu.
- Báo rõ khác biệt giữa kiểm tra local, linter Jenkins và một build thực sự thành công trên VM.
- Khởi tạo cờ release thay đổi lúc chạy trong `script`, không khai báo trong Declarative `environment`.
- Giữ nhánh rollback và điều kiện gắn stable đọc cùng trạng thái `POST_DEPLOY_SMOKE_FAILED`.
