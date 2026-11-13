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

Sau khi đưa bản sửa lên branch của job, chạy một build mới và kiểm tra baseline hiện có stable.
Không cố tình làm hỏng smoke ở môi trường đang triển khai để thử rollback; kiểm chứng nhánh lỗi trên môi trường thử riêng.
Build #15 chưa kiểm chứng bản sửa cờ trạng thái hoặc các cải tiến runner mới trong working tree.
