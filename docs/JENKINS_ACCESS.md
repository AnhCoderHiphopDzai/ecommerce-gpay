# Truy cập và vận hành Jenkins trên VMware Ubuntu

## Địa chỉ đã xác minh

Ngày 2026-11-13, truy cập từ Windows tới `http://192.168.179.129:8080/login`
trả HTTP 200, header Jenkins 2.580.2. SSH cổng 22 phản hồi nhưng phiên tự động
chưa có khóa được chấp nhận. Lịch sử PowerShell có tài khoản `ndquang` trên IP này.
Đây là thông tin lần kiểm tra này; IP guest có thể đổi khi DHCP cấp lại.

| Thành phần | Địa chỉ mặc định |
| --- | --- |
| Ubuntu SSH | `ndquang@192.168.179.129:22` |
| Jenkins | `http://192.168.179.129:8080` |
| Frontend theo Compose | `http://192.168.179.129:8088` |
| Backend health theo Compose | `http://192.168.179.129:8081/actuator/health` |
| Windows VMware NAT VMnet8 | `192.168.179.1` |

## Kết nối từ PowerShell Windows

Mở VMware, bật VM **Ubuntu Server**, chờ hệ điều hành khởi động. Trong PowerShell:

```powershell
Test-NetConnection 192.168.179.129 -Port 22
Test-NetConnection 192.168.179.129 -Port 8080
ssh ndquang@192.168.179.129
```

Nhập mật khẩu Ubuntu trực tiếp khi SSH hỏi; ký tự không hiện khi gõ là bình thường.
Tài khoản/mật khẩu Jenkins là thông tin riêng, không nhất thiết giống Ubuntu.
Lần đầu SSH hỏi host fingerprint, đối chiếu với VM trước khi chấp nhận.
Nếu dùng key có sẵn: `ssh -i "$env:USERPROFILE\.ssh\<ten-key>" ndquang@192.168.179.129`.

Mở `http://192.168.179.129:8080` trong trình duyệt và đăng nhập Jenkins.
Nếu cổng Jenkins chỉ bind localhost trên VM, giữ một cửa sổ PowerShell chạy tunnel:

```powershell
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:18080:127.0.0.1:8080 ndquang@192.168.179.129
```

Khi đó mở `http://127.0.0.1:18080`; `Ctrl+C` đóng tunnel.

## Nếu không kết nối được

Trong console Ubuntu của VMware, chạy `hostname -I` hoặc `ip -br a` để lấy IP hiện tại.
Network Adapter của VM cần Connected và Connect at power on; cấu hình hiện tại dùng subnet VMnet8.
Nếu IP đổi, dùng IP mới cho SSH/Jenkins và parameter `DEPLOY_HOST` khi build.
Frontend nhúng URL API lúc build nên cần build lại khi đổi IP.

Các lệnh dưới đây chạy **trong Ubuntu sau SSH hoặc console VMware**, không chạy trực tiếp trong PowerShell:

```sh
hostname -I
systemctl is-active ssh
systemctl is-active jenkins
sudo ss -ltnp | grep -E ':(22|8080|8081|8088)\b'
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
docker compose version
```

- SSH timeout: kiểm tra VM/IP/network trước; `Permission denied` nghĩa là đã tới SSH nhưng sai thông tin xác thực.
- Nếu service SSH chưa có, cài `openssh-server` từ Ubuntu repository rồi `sudo systemctl enable --now ssh`.
- Jenkins cài dạng service: xem `sudo systemctl status jenkins --no-pager` và `sudo journalctl -u jenkins -n 100 --no-pager`.
- Nếu service đã cài nhưng đang dừng, khởi động bằng `sudo systemctl start jenkins`.
- Nếu Jenkins chạy trong Docker, dùng tên container thực tế từ `docker ps -a`, sau đó `docker logs --tail 100 <ten-container>`.
- Không cài thêm Jenkins hoặc tạo Jenkins home mới chỉ vì chưa tìm thấy service; kiểm tra container trước.
- Không xóa host key cũ khi chưa xác minh VM có thực sự đổi key.

## Cấu hình job và chạy tiếp CI/CD

1. Trên dashboard, mở job **demo1-ci-cd**: `http://192.168.179.129:8080/job/demo1-ci-cd/`.
   Git repository của dự án là `AnhCoderHiphopDzai/ecommerce-gpay`.
2. Trong **Configure**, kiểm tra Pipeline from SCM, Git URL, branch đang dùng và Script Path `Jenkinsfile`.
3. Jenkins agent cần Linux trên VM deploy, Docker CLI, Compose v2, Git, curl và quyền dùng daemon.
   Pipeline bind-mount workspace và kiểm tra post-deploy ở `127.0.0.1`; agent/daemon phải cùng host deploy.
4. Trong Credentials, kiểm tra các Secret text IDs sau, không in giá trị vào console:
   `APP_JWT_SECRET`, `SPRING_DATASOURCE_PASSWORD`, `GPAY_CLIENT_SECRET`, `GPAY_PRIVATE_KEY`, `GPAY_CERTIFICATE`.
   Password database phải khớp MySQL volume đang dùng; đổi env không đổi password của database đã khởi tạo.
5. Đưa thay đổi đã review vào branch job theo dõi. File trên Windows chưa commit/push sẽ không tự xuất hiện trong Jenkins.
6. Chọn **Build with Parameters**, đặt `DEPLOY_HOST=192.168.179.129` hoặc IP mới.
   Nếu job chưa cập nhật parameter lần đầu, chạy **Build Now** để Jenkins đọc Jenkinsfile mới.
7. Xem **Console Output**, stage lỗi, **Test Result** và artifact. Các quality gate phải đạt trước khi deploy.
8. Tại **Approve deployment**, kiểm tra Git SHA/image tag rồi người vận hành bấm **Deploy**.
9. Chỉ xem release thành công khi post-deploy smoke đạt và stage **Mark release stable** hoàn tất.
   Nếu smoke lỗi, xem runbook và phê duyệt rollback khi có cặp image stable hợp lệ.

Pipeline hiện poll SCM mỗi hai phút; chưa cần webhook public để nhận commit mới.
`DEPLOY_HOST` chỉ cấu hình URL API/CORS; pipeline deploy bằng Docker daemon của agent, không SSH sang IP đó.
Log người dùng cung cấp xác nhận job `demo1-ci-cd`, build #15 SUCCESS trên commit `e122cf51a289`.
Candidate smoke, deploy, post-deploy smoke và gắn hai image stable hoàn tất lúc 10:15 ngày 2026-11-13.
Kiểm tra HTTP từ Windows sau đó xác nhận frontend 200, backend health UP và anonymous `/api/auth/me` 401.
Chạy lại `ci/smoke-deployed.sh` từ Windows qua Git Bash với URL VM cũng đạt, gồm products API và CORS.
Branch của job chưa được xác minh; các thay đổi working tree mới chưa nằm trong build #15.

## Chạy riêng integration test trên Ubuntu

Trong thư mục checkout của repository, với Docker Engine đang hoạt động:

```sh
export CI_PROJECT="demo1-ci-manual-$(date +%s)"
export CI_DB_PASSWORD=ci-test-only-password
export CI_JWT_SECRET=ci-test-jwt-secret-not-for-deployment-1234567890
export HOST_UID="$(id -u)" HOST_GID="$(id -g)"
(
  set -eu
  cleanup() {
    result=$?
    trap - EXIT
    docker compose -f compose.ci.yaml -p "$CI_PROJECT" down --volumes --remove-orphans || true
    exit "$result"
  }
  trap cleanup EXIT
  docker compose -f compose.ci.yaml -p "$CI_PROJECT" up -d db redis
  docker compose -f compose.ci.yaml -p "$CI_PROJECT" run --rm integration-test
)
```

Chỉ dùng project `demo1-ci-*` cho lệnh xóa volume. Không thay bằng project production `demo1`.
JUnit/coverage được ghi vào `demo/target/`. Lệnh này không build candidate hoặc deploy.

## Tài liệu liên quan

- [Chiến lược kiểm thử](CI_TEST_STRATEGY.md) và [xử lý lỗi/rollback](CI_FAILURE_RUNBOOK.md).
- [Jenkins Pipeline syntax](https://www.jenkins.io/doc/book/pipeline/syntax/) và
  [Using a Jenkinsfile](https://www.jenkins.io/doc/book/pipeline/jenkinsfile/).
- [Microsoft OpenSSH](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_install_firstuse).
