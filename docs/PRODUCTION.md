# Đưa ecommerce-gpay lên Internet

## Trạng thái chuẩn bị

Đã chuẩn bị cấu hình và script trong repository. Chưa có VPS, IP public, SSH key hoặc registry credentials;
chưa có website production được tạo. Build VMware #17 là bản chạy nội bộ trước các thay đổi này.
Người dùng chọn tên miền phụ sslip.io; không cần đăng ký tài khoản hay mua domain để thử.
Không cần Kubernetes cho phương án một VPS này.

Kiến trúc: browser → Caddy HTTPS (80/443) → frontend Nginx hoặc backend (API/WebSocket).
MySQL/Redis/backend/frontend không publish cổng host. Jenkins vẫn chạy trên VMware và gửi release qua SSH.
Nếu tắt máy Windows/Jenkins, website trên VPS vẫn chạy; chỉ các build/deploy mới dừng.
Lần triển khai này có thể có thời gian gián đoạn khi thay container; chưa phải triển khai không downtime.

## 1. Có VPS Ubuntu và chọn hostname

- Chọn VPS Ubuntu LTS với IPv4 public và disk persistent; cấu hình khởi điểm để thử stack này: 2 vCPU, 4 GB RAM.
  Đây là mức khởi điểm, chưa phải kết quả đo tải. Build/test chạy ở Jenkins VM, không build trên VPS.
- Xem giá, phí IPv4, disk và backup của nhà cung cấp trước khi thuê. Repository không tự tạo tài nguyên tính phí.
- Với IP public thực tế, hostname có dạng `shop-<IPv4-thay-dấu-chấm-bằng-gạch-ngang>.sslip.io`.
  Ví dụ tài liệu: `shop-203-0-113-10.sslip.io`; `203.0.113.10` là IP minh họa, không thể dùng để deploy thật.
- Không dùng `192.168.179.129`: DNS trỏ về IP private không làm máy VMware truy cập được từ Internet.
- Kiểm tra từ PowerShell: `Resolve-DnsName <hostname-da-thay-IP>`; kết quả phải là IP public VPS.
- sslip.io chỉ cung cấp DNS theo IP; không cung cấp VPS. IP đổi thì hostname chứa IP cũng phải đổi và frontend phải build lại.
- Trỏ đúng A/AAAA; không thêm IPv6 nếu VPS chưa phục vụ IPv6. Mở TCP 80/443 trên firewall của nhà cung cấp.
  Giới hạn SSH 22 cho IP quản trị/runner nếu có thể; không mở 8080/8081/8088, 3306, 6379 hay Docker API.

Nguồn: [sslip.io / nip.io](https://sslip.io/) giải thích hostname chứa IP, TLS và giới hạn cấp chứng chỉ.
Phương án miễn phí có tên dễ nhớ hơn là [DuckDNS](https://www.duckdns.org/about.jsp), cần đăng nhập chọn subdomain.
Tên miền phụ miễn phí phụ thuộc nhà cung cấp; khi vận hành thương mại lâu dài nên chuyển sang domain sở hữu riêng.

## 2. Chuẩn bị hệ điều hành, Docker và SSH

Từ PowerShell: `ssh <tai-khoan-VPS>@<IP-public>`; xác minh fingerprint với console nhà cung cấp trước khi chấp nhận.
Trong Ubuntu, cài Docker Engine và Compose plugin theo [hướng dẫn Docker chính thức](https://docs.docker.com/engine/install/ubuntu/).
Không dùng Docker Desktop trên VPS. Kiểm tra `docker version`, `docker compose version`, `curl --version`, `flock --version`.
Script dùng `docker compose up --wait --wait-timeout`, nhiều `--env-file`; dùng Compose plugin hiện hành.

Tạo tài khoản triển khai và thư mục (chạy bằng tài khoản có sudo):

```sh
sudo adduser deploy
sudo usermod -aG docker deploy
sudo install -d -o deploy -g deploy -m 750 /opt/ecommerce-gpay
sudo install -d -o deploy -g deploy -m 700 /opt/ecommerce-gpay/shared /opt/ecommerce-gpay/backups
sudo install -d -o deploy -g deploy -m 700 /opt/ecommerce-gpay/shared/payment
sudo install -d -o deploy -g deploy -m 750 /opt/ecommerce-gpay/releases
```

Tạo SSH key riêng không có passphrase cho runner tự động (private key được lưu bằng Jenkins Credentials), đặt PUBLIC key vào `/home/deploy/.ssh/authorized_keys` trên VPS, quyền 700 cho `.ssh`, 600 cho file.
Giữ private key trong Jenkins credential `PROD_SSH_KEY`; không gửi private key/mật khẩu vào chat hoặc Git.
Đăng nhập lại để nhận quyền nhóm Docker. Quyền Docker tương đương quyền quản trị host; chỉ cấp cho tài khoản triển khai được tin cậy.
Host key phải được xác minh qua console của nhà cung cấp; sau đó lưu dòng known_hosts cho đúng IP/DNS vào Jenkins.
Không dùng `StrictHostKeyChecking=no`; script yêu cầu file known_hosts đã kiểm chứng.
Không công khai Jenkins; polling SCM hiện có không cần mở Jenkins ra Internet.

Lưu ý Docker có thể bypass quy tắc UFW cho cổng đã publish; stack production chỉ publish Caddy 80/443.
Xem [Docker và firewall](https://docs.docker.com/engine/network/packet-filtering-firewalls/).

## 3. Tạo cấu hình secrets trên VPS

Copy `deploy/production/.env.example` thành `/opt/ecommerce-gpay/shared/production.env` trên VPS.
Đặt quyền `chmod 600 /opt/ecommerce-gpay/shared/production.env`, chủ sở hữu `deploy`.
Điền `DOMAIN` (hostname sslip.io thực tế), `ACME_EMAIL` (email liên hệ chứng chỉ) và thay mọi `CHANGE_ME`.
Dùng định dạng `KEY=value` không có dấu nháy cho DOMAIN; các secret nên là chuỗi hex một dòng.
Sinh hai password khác nhau bằng `openssl rand -hex 32`, JWT secret bằng `openssl rand -hex 64`.
Chỉ chạy lệnh sinh secret trong terminal riêng và nhập vào file trên VPS; không đưa output vào log Jenkins/chat.

- `DB_ROOT_PASSWORD`: quản trị/backup MySQL; backend không dùng root.
- `DB_APP_PASSWORD`: user `shop_app`, chỉ được cấp quyền schema `shop`.
- `APP_JWT_SECRET`: secret riêng production; không dùng giá trị test/VM.
- `APP_ADMIN_EMAIL=`: để rỗng khi public lần đầu; tránh cấp quyền cho email chưa có chủ sở hữu được kiểm chứng.
- `APP_PAYMENTS_ENABLED=false`: mặc định, web/auth/cart hoạt động nhưng tạo thanh toán/webhook trả 503.
- Giữ file này ngoài mọi release. Jenkins chỉ gửi image refs và hostname, không sao chép secret từ workspace.
- MySQL chỉ tạo user/password lúc volume trống; đổi file env sau này không tự đổi password database đã tồn tại.

## 4. Chuẩn bị GHCR và Jenkins

Images sẽ được push vào `<REGISTRY_PREFIX>-backend:<IMAGE_TAG>` và `-frontend:<IMAGE_TAG>`.
Mặc định prefix là `ghcr.io/anhcoderhiphopdzai/ecommerce-gpay`; dùng tài khoản/namespace bạn có quyền.
Giữ package private nếu image chứa mã không muốn công khai. Cấu hình quyền theo [tài liệu GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

Credentials thêm vào Jenkins:

| ID | Loại | Nội dung |
| --- | --- | --- |
| `PROD_REGISTRY` | Username with password | GitHub username + token classic chỉ cấp quyền cần để push package (`write:packages`) |
| `PROD_SSH_KEY` | SSH Username with private key | Private key của tài khoản deploy |
| `PROD_SSH_KNOWN_HOSTS` | Secret file | known_hosts chứa host key đã xác minh của VPS |

Trên VPS, đăng nhập GHCR một lần bằng tài khoản `deploy`, dùng token chỉ đọc package private (`read:packages`).
Nhập token tại prompt `docker login ghcr.io -u <github-user>`; không đưa token vào command line hoặc history.
Không chạy bằng sudo nếu tài khoản Jenkins dùng là deploy, vì Docker login được lưu theo user.

Agent Jenkins cần Docker Linux, Git, curl, SSH client, tar, gzip và các quyền workspace hiện có.
Job cũ vẫn dùng `Jenkinsfile`. Sau khi đưa commit mới lên branch job theo dõi, chạy một lượt để nạp parameters mới.
Lần đầu có thể chạy `DEPLOY_TARGET=vm` để kiểm tra các cổng CI mới mà chưa triển khai production.

## 5. Chạy production build và duyệt deploy

Trong **Build with Parameters** của `demo1-ci-cd`:

| Parameter | Giá trị |
| --- | --- |
| `DEPLOY_TARGET` | `production` |
| `PROD_DOMAIN` | Hostname sslip.io; phải khớp DOMAIN trong production.env |
| `PROD_SSH_HOST` | IP public hoặc DNS SSH của VPS |
| `PROD_SSH_USER` | `deploy` |
| `REGISTRY_PREFIX` | Prefix GHCR thuộc tài khoản của bạn |
| `DEPLOY_HOST` | Chỉ dùng cho nhánh VMware; không phải đích deploy production |

Pipeline chạy lint/unit/integration, test script deploy với Docker/HTTP giả lập, validate ba Compose/Caddy,
build images và chạy candidate thực với profile `prod`, Flyway migration, user MySQL không phải root trên database CI trống.
Thanh toán candidate bị khóa để không phát sinh giao dịch ngoài. Frontend production được build với `https://<PROD_DOMAIN>`.
Sau khi mọi cổng đạt, image được push GHCR. Người vận hành duyệt **Deploy production**.

VPS nhận release tại `/opt/ecommerce-gpay/releases/build-<number>-<sha12>`:
pull images → backup DB hiện có → chờ container healthy → smoke HTTPS qua domain → trỏ `current` vào release thành công.
Caddy tự cấp/gia hạn chứng chỉ; cần DNS đúng, 80/443 thông và volume Caddy persistent.
Không cần đổi `SERVER_SSL_ENABLED=true` trong backend; TLS kết thúc tại Caddy, backend không có cổng host.
Nguồn: [Caddy Automatic HTTPS](https://caddyserver.com/docs/automatic-https).

## 6. Database và dữ liệu từ VM

Production mới dùng Flyway `V1__initial_schema.sql` và Hibernate `validate`.
Profile mặc định/VM giữ Hibernate `update` và tắt Flyway để không tự sửa dữ liệu đang chạy.
Không sửa migration đã áp dụng; thêm `V2__...sql` ở lần thay đổi schema tiếp theo.
Không tự baseline database cũ: profile production sẽ dừng nếu có bảng mà thiếu lịch sử Flyway.

Nếu cần giữ dữ liệu VM, triển khai schema mới vào VPS trước; xuất dữ liệu cần thiết và thử import/đối chiếu trên database tạm.
Xem lại dữ liệu demo, tài khoản admin, token phiên và dữ liệu thanh toán trước khi import.
Không copy nguyên volume cũ rồi bật production; không đặt `baseline-on-migrate=true` để bỏ qua kiểm chứng schema.
Hướng dẫn migration: [Spring Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html).

## 7. Backup, phục hồi và rollback

Backup trước mỗi deploy dùng `mysqldump --single-transaction`, gzip và kiểm tra gzip, lưu ở `/opt/ecommerce-gpay/backups` (quyền riêng).
Backup lỗi thì chưa đổi container ứng dụng. File backup chứa dữ liệu người dùng; không commit/archive Jenkins.
Cài lịch backup sau release thành công đầu tiên (service mặc định dùng user deploy):

```sh
sudo cp /opt/ecommerce-gpay/current/deploy/production/ecommerce-backup.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ecommerce-backup.timer
systemctl list-timers ecommerce-backup.timer
```

Lịch là 02:30 UTC = khoảng 09:30 Việt Nam, có jitter tối đa 5 phút; persistent để chạy bù.
Copy backup thường xuyên sang máy/bucket khác bằng tài khoản riêng, mã hóa nếu lưu ngoài; backup cùng disk không bảo vệ khi mất VPS.
Thiết lập retention phù hợp dung lượng và kiểm tra disk; script không tự xóa backup cũ.
Thử phục hồi trên stack riêng, không dừng hay ghi vào production:

```sh
sh /opt/ecommerce-gpay/current/deploy/production/restore-check.sh /opt/ecommerce-gpay/backups/<ten-backup>.sql.gz
```

Script dùng project `ecommerce-restore-check-*`, không khởi động edge/public ports, chờ backend validate/healthy rồi xóa đúng volume thử.
Không chạy thêm restore lớn nếu VPS thiếu RAM/disk; có thể thực hiện trên host thử riêng với cùng bundle/env.

Deploy/smoke lỗi: `current` vẫn giữ release tốt trước đó, Jenkins lưu chẩn đoán và yêu cầu duyệt rollback nếu có baseline.
Rollback chỉ khôi phục image/config release cũ và chạy smoke lại; không tự phục hồi schema/database. Domain và secrets trong shared không được snapshot; thay đổi domain/credentials cần kế hoạch rollback riêng.
Migration mới phải tương thích với image cũ; nếu không, cần phương án khôi phục dữ liệu riêng.
Lần triển khai đầu tiên chưa có baseline; xem log và sửa cấu hình thay vì mong có rollback.
Muốn trở về release cũ khác, review thư mục/image tag rồi chạy `sh <release-cu>/deploy/production/deploy.sh` từ Ubuntu.

## 8. Admin, G-Pay và kiểm tra trước khi mời người dùng

- Đăng ký tài khoản quản trị của chính bạn, rồi đặt APP_ADMIN_EMAIL đúng email đó và recreate backend một lần.
  Sau khi đã cấp quyền, xóa giá trị email, recreate lại; kiểm tra tài khoản USER không gọi được API ADMIN.
- G-Pay vẫn sandbox/disabled cho đến khi có cấu hình merchant phù hợp. Muốn bật, điền toàn bộ biến G-Pay,
  đặt public key được G-Pay xác nhận tại `/opt/ecommerce-gpay/shared/payment/public.pem` và xác nhận callback/webhook HTTPS.
- Sau kiểm thử sandbox/đối soát chữ ký, số tiền, webhook trùng và đồng thời, lấy endpoint/credentials live từ G-Pay;
  không suy đoán endpoint live và không coi test CI/mock là chứng nhận thanh toán thật. Sau đó mới đổi APP_PAYMENTS_ENABLED=true.
- Smoke tự động kiểm tra HTTPS/cert, frontend, products, anonymous 401, `/ws/info` và CORS.
  Header X-Release-ID phải khớp release để tránh nhận nhầm website khi DNS/proxy sai.
  Vẫn cần kiểm tra bằng browser: cookie Secure, login/refresh/logout, giỏ hàng, admin và cập nhật WebSocket thực.
- Dùng monitor ngoài VPS gọi `https://<DOMAIN>/actuator/health`; cấu hình nơi nhận cảnh báo do bạn chọn.
  Kiểm tra thêm dung lượng disk/backup, RAM, log rotation, cập nhật OS và chứng chỉ.
- Không mở Jenkins cho người mua hàng. Không dùng `docker compose down --volumes` với project `ecommerce-prod`.

## Lệnh chẩn đoán (Ubuntu, user deploy)

```sh
cd /opt/ecommerce-gpay/current
docker compose --env-file /opt/ecommerce-gpay/shared/production.env --env-file release.env -f compose.production.yaml -p ecommerce-prod ps
docker compose --env-file /opt/ecommerce-gpay/shared/production.env --env-file release.env -f compose.production.yaml -p ecommerce-prod logs --tail 100 edge backend
curl --fail --silent --show-error https://<DOMAIN>/actuator/health
```

Nếu deploy đầu tiên lỗi thì chưa có `current`; thay bằng đường dẫn `releases/build-...` Jenkins vừa upload.
Không in `docker compose config` đầy đủ hoặc `docker inspect` env vì có thể lộ secrets; dùng `config --quiet`.

## Kiểm chứng của đợt chuẩn bị

Xem kết quả kiểm tra trong phản hồi bàn giao. Chạy shell/mock/schema local không thay thế một pipeline Linux và smoke HTTPS thật.
Chưa có VPS/domain thực nên chứng chỉ, SSH/registry credentials, migration trên MySQL và production deploy phải được kiểm chứng khi có host.

Kết quả local ngày 2026-11-13: Maven verify (18 unit test) đạt; frontend lint, 15 test/coverage và build HTTPS mẫu đạt.
Ba YAML hợp lệ theo Compose JSON Schema chính thức; Groovy Jenkinsfile biên dịch cú pháp và shell sh -n đạt.
Test Docker/HTTP giả lập trên Git Bash đạt các nhánh lỗi/guard/restore; symlink promotion/rollback được giữ cho Linux CI
vì Windows không cấp quyền tạo symlink. Bundle đã kiểm tra danh sách file cho phép, không gom cả thư mục secrets.
Windows Application Control chặn CLI Compose tải về; chưa chạy Docker Compose/Caddy thật tại máy Windows.
Các bước validate Caddy và candidate Flyway/MySQL thật đã có trong Jenkinsfile nhưng chưa chạy ở đợt chuẩn bị này.
