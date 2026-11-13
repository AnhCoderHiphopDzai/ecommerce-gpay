# Quy trình xử lý lỗi CI/CD

`Jenkinsfile` là cổng bắt buộc trước khi deploy. Một thay đổi chỉ được đưa tới bước phê duyệt khi toàn bộ các cổng trước đó đạt.

## Thứ tự các cổng

Trước các cổng bên dưới, **Runner preflight** kiểm tra Linux agent, Docker daemon/Compose,
curl/Git, cú pháp shell và việc Docker đọc đúng workspace bind-mount.
Lỗi ở bước này cần sửa runner; xem [JENKINS_ACCESS.md](JENKINS_ACCESS.md) để kết nối VM.

1. **Validate configuration** kiểm tra cú pháp Compose và biến bắt buộc.
2. **Frontend quality** chạy cài dependency khóa phiên bản, lint, test component/API client và build production.
3. **Backend unit tests** kiểm tra chính sách mật khẩu, refresh token, rate limit, JWT, chữ ký G-Pay và xử lý webhook.
4. **Backend integration and API tests** dùng MySQL/Redis tạm riêng để kiểm tra database, Redis, JWT/session, CORS, phân quyền và API contract.
5. **Build immutable candidate images** tạo image có tag theo Jenkins build và Git SHA.
6. **Candidate smoke tests** khởi động toàn stack tạm, kiểm tra health backend, frontend, API công khai, API bảo vệ và CORS.
7. **Approve deployment** yêu cầu người được cấp quyền xác nhận.
8. **Post-deploy smoke tests** kiểm tra bản vừa chạy trên cổng thật của VM.

Nếu một cổng từ 1 đến 6 thất bại, Jenkins không hiển thị nút deploy. Test database và Redis được xóa sau build; chúng không chạm vào volume production.

## Khi Jenkins báo lỗi

1. Xem stage đỏ trước tiên. Đừng bấm chạy lại liên tục.
2. Mở `Console Output`, kết quả JUnit và artifact `artifacts/` của build. Artifact có `compose-ps.txt` và `compose.log` cho stack gặp lỗi.
3. Phân loại lỗi:

   | Nhóm | Dấu hiệu | Xử lý |
   | --- | --- | --- |
   | Mã nguồn hoặc test | assertion, compile, lint, test đỏ lặp lại | tái hiện, sửa bằng commit mới, chạy lại các cổng liên quan |
   | Dependency/build | download image/package thất bại, checksum, registry lỗi | kiểm tra version lock, registry và mạng; retry một lần khi có bằng chứng lỗi tạm thời |
   | Database/Redis | health check không đạt, connection refused, schema lỗi | xem compose log; sửa config/migration hoặc service trước khi chạy lại |
   | Runner/Docker | hết disk, permission Docker, daemon mất kết nối | sửa Jenkins node/VM; không thay đổi mã nguồn để che lỗi hạ tầng |
   | Candidate/deploy | candidate test đạt nhưng smoke sau deploy lỗi | xem log deploy, xác nhận rollback về `stable` nếu có |

4. Chỉ retry một lần khi log cho thấy lỗi tạm thời như timeout tải image, DNS hoặc Docker daemon bị gián đoạn. Lỗi assertion, lint hay compile cần một commit sửa lỗi.
5. Khi đã sửa, tạo commit mới. Jenkins chạy lại toàn bộ cổng để tránh bỏ sót tác động dây chuyền.

## Khi lỗi sau deploy

Pipeline giữ build ở trạng thái thất bại và lưu log. Nó hiện nút **Rollback** để người vận hành xác nhận phục hồi image `stable` gần nhất. Rollback chỉ đổi backend/frontend; không rollback MySQL volume vì schema/data có thể đã thay đổi và phải có kế hoạch migration riêng.

Sau rollback, Jenkins chạy smoke test lần nữa. Nếu rollback không có image `stable` (ví dụ lần triển khai đầu tiên), giữ log, dừng release và khôi phục thủ công từ bản image đã biết tốt.

## Sau khi khắc phục

- Ghi ngắn gọn nguyên nhân, commit sửa và test đã bổ sung trong pull request hoặc issue.
- Nếu lỗi lọt qua CI, thêm test hoặc smoke check vào đúng cổng phát hiện sớm nhất.
- Không bỏ qua cổng CI để deploy, trừ khi có người chịu trách nhiệm phê duyệt ngoại lệ và nêu rõ biện pháp giảm rủi ro.

## Kết quả build #15 và lỗi cờ rollback

Log job `demo1-ci-cd` ngày 2026-11-13 xác nhận build #15, commit `e122cf51a289`,
kết thúc SUCCESS: candidate smoke đạt, người vận hành duyệt Deploy, post-deploy smoke đạt
và hai image `build-15-e122cf51a289` được gắn `stable`. Không cần rollback bản deploy này.
Thông báo `[Checks API] No suitable checks publisher found` không làm build thất bại;
JUnit và artifact vẫn được ghi. Frontend hiện `health: starting` ngay sau khi khởi động
không mâu thuẫn với HTTP smoke đã đạt; sau đó kiểm tra từ Windows trả HTTP 200.

Log baseline có `printf true` nhưng lại báo không có stable. Jenkinsfile cũ đặt
`HAS_STABLE_RELEASE` và `POST_DEPLOY_SMOKE_FAILED` trong Declarative `environment`.
Scope `withEnv` của Declarative che giá trị được gán lại bằng `env.*` trong `script`.
Do đó lỗi này cũng có thể bỏ qua bước duyệt rollback và gắn stable cho candidate có smoke thất bại.
Bản sửa khởi tạo hai cờ trong `script` đầu Checkout, rồi cập nhật chúng trong các stage sau.
Đối chiếu cơ chế scope trong [ModelInterpreter của Jenkins](https://github.com/jenkinsci/pipeline-model-definition-plugin/blob/master/pipeline-model-definition/src/main/resources/org/jenkinsci/plugins/pipeline/modeldefinition/ModelInterpreter.groovy)
và [EnvActionImpl](https://github.com/jenkinsci/workflow-cps-plugin/blob/master/plugin/src/main/java/org/jenkinsci/plugins/workflow/cps/EnvActionImpl.java).

Build #17 đã xác nhận pipeline nhận diện stable đúng sau bản sửa.
Không cố tình làm hỏng smoke ở môi trường đang triển khai để thử rollback; kiểm chứng nhánh lỗi trên môi trường thử riêng.
### Xác nhận lại với build #17

Log người dùng cung cấp ngày 2026-11-13 xác nhận build #17, commit `3e453cab3fb4`, SUCCESS lúc 14:22.
Sau `printf true`, pipeline đã báo `A stable release is available for a manually approved rollback.`
Việc cập nhật/đọc cờ baseline đã hoạt động đúng trên Jenkins.
Deploy được phê duyệt; post-deploy smoke đạt; hai image `build-17-3e453cab3fb4` được gắn `stable`.
Hook kết thúc cũng thu thập chẩn đoán CI và chạy dọn project `demo1-ci-17`.
Các stage rollback bị bỏ qua đúng vì smoke đạt. Nhánh cập nhật cờ smoke lỗi và rollback thực tế chưa được kiểm thử.


## Build #18: Maven tải JAR Hibernate bị ngắt

Ngày 2026-11-13, stage integration dừng lúc 15:51 khi tải
`org.hibernate.orm:hibernate-core:7.4.13.Final`: server khai báo 15.334.914 bytes nhưng chỉ nhận 4.358.099 bytes.
Thông báo `Premature end of Content-Length delimited message body` thể hiện stream tải bị cắt; log này chưa phải lỗi assertion/Java.
Build dừng trước build candidate/deploy; cleanup MySQL/Redis CI sau lỗi là hành vi đúng.
Có thể chạy lại một lần khi gặp đúng lỗi tải tạm thời này; nếu tiếp tục lỗi, kiểm tra kết nối Internet/proxy của Ubuntu VM.

Bản sửa giữ cache Maven giữa unit/integration và giữa các build tại `.cache/maven` trong workspace Jenkins,
mount vào `/maven-repository` cho user runner. Checkout tạo thư mục cache; các lần cleanup Compose chỉ xóa dữ liệu CI.
Không đặt cache vào `/tmp` của container tạm vì mỗi invocation sẽ phải tải lại toàn bộ.
Helper `ci/maven-with-download-retry.sh` stream log qua tee, giữ exit code Maven và chỉ thử thêm một lần sau 5 giây
nếu exit code là 1 và dòng lỗi chỉ rõ artifact transfer bị cắt Content-Length; lần thử lại dùng `-U` để kiểm tra lại resolution.
Lỗi test/compile/checksum, thiếu artifact hoặc interrupt không được tự thử lại. Lần thử thứ hai vẫn lỗi thì build dừng.

Maven 3.9 mặc định dùng native HTTP Resolver; cấu hình retry Wagon đơn thuần không giải quyết được tình huống này.
Xem [Maven Resolver transport](https://maven.apache.org/guides/mini/guide-resolver-transport.html) và
[giới hạn retry response body của Wagon](https://maven.apache.org/wagon/wagon-providers/wagon-http/).
Không đổi Hibernate/Spring Boot, mirror hay tắt kiểm tra TLS để né lỗi tải.
Cache này chỉ dành cho Maven test container; bước build Docker image vẫn có môi trường Maven riêng.
