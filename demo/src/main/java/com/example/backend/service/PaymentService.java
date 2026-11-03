package com.example.backend.service;

import com.example.backend.utils.GpaySecurityUtil;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    @Value("${gpay.client-id}")
    private String clientId;

    @Value("${gpay.client-secret}")
    private String clientSecret;

    @Value("${gpay.api.base-url}")
    private String baseUrl;

    @Value("${gpay.merchant-code}")
    private String merchantCode;

    @Value("${gpay.merchant-id}")
    private String merchantId;

    @Value("${gpay.certificate}")
    private String certificate;

    @Value("${gpay.private-key}")
    private String privateKey;

    @Value("${gpay.webhook-url}")
    private String webhookUrl;

    @Value("${gpay.callback-url}")
    private String callbackUrl;

    private final GpaySecurityUtil securityUtil;
    private final RestClient restClient;

    private String dynamicAccessToken = null;
    private long tokenExpiryTime = 0;

    public PaymentService(GpaySecurityUtil securityUtil) {
        this.securityUtil = securityUtil;

        CloseableHttpClient httpClient = HttpClients.custom()
                .disableAutomaticRetries()
                .build();

        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    public String createPaymentUrl(String invoiceCode, int amount) throws Exception {

        String url = baseUrl + "/payments/gateway/init-order";
        String requestId = UUID.randomUUID().toString();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String customerId = "GUEST";
        String customerName = "Guest";
        String description = "Payment for order " + invoiceCode;
        String title = "Thanh toan don hang " + invoiceCode;
        String embedData = invoiceCode;

        // TẠO JSON STRING CỐ ĐỊNH, CHUẨN THỨ TỰ VÀ KHÔNG KHOẢNG TRẮNG
        String jsonBody = "{"
                + "\"amount\":" + amount + ","
                + "\"callback_url\":\"" + escapeJson(callbackUrl) + "\","
                + "\"customer_id\":\"" + escapeJson(customerId) + "\","
                + "\"customer_name\":\"" + escapeJson(customerName) + "\","
                + "\"description\":\"" + escapeJson(description) + "\","
                + "\"embed_data\":\"" + escapeJson(embedData) + "\","
                + "\"payment_type\":\"IMMEDIATE\","
                + "\"request_id\":\"" + escapeJson(requestId) + "\","
                + "\"title\":\"" + escapeJson(title) + "\","
                + "\"webhook_url\":\"" + escapeJson(webhookUrl) + "\""
                + "}";

        // Không log JSON body: có thể chứa tên khách hàng, mã hóa đơn và URL callback nội bộ.
        log.info("G-Pay init-order requestId={} invoiceCode={}", requestId, invoiceCode);

        String signature = securityUtil.generateSignature(
                timestamp,
                requestId,
                jsonBody,
                privateKey
        );

        log.debug("Đã tạo chữ ký G-Pay cho requestId={}", requestId);

        try {
            byte[] rawBodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("signature", signature)
                    .header("x-certificate", certificate)
                    .header("x-requests-id", requestId)
                    .header("x-timestamp", timestamp)
                    .body(rawBodyBytes) // ĐÂY LÀ ĐIỂM QUAN TRỌNG NHẤT
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new IllegalStateException("G-Pay trả về response null");
            }

            Object dataObject = response.get("data");

            if (!(dataObject instanceof Map<?, ?>)) {
                throw new IllegalStateException("G-Pay response không có data hợp lệ: " + response);
            }

            Map<?, ?> data = (Map<?, ?>) dataObject;
            Object billUrlObject = data.get("bill_url");

            if (!(billUrlObject instanceof String) || ((String) billUrlObject).isBlank()) {
                throw new IllegalStateException("G-Pay không trả về bill_url. Response: " + response);
            }

            String billUrl = (String) billUrlObject;
            log.info("G-Pay init-order thành công requestId={}", requestId);

            return billUrl;


        } catch (Exception e) {
            log.warn("G-Pay init-order thất bại requestId={}: {}", requestId, e.getMessage());
            throw e;
        }
    }
    /**
     * Hàm tự động lấy Access Token từ G-Pay và cache lại
     */
    private String getAccessToken() {
        // Kiểm tra token trong cache còn hạn không
        if (dynamicAccessToken != null && System.currentTimeMillis() < (tokenExpiryTime - 60000)) {
            return dynamicAccessToken;
        }

        log.info("Đang lấy access token G-Pay mới");

        // 1. Đường dẫn chuẩn theo code mẫu
        String authUrl = baseUrl + "/auth/token";

        // 2. Định dạng JSON với client_id và client_secret
        Map<String, String> requestBody = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri(authUrl)
                    .contentType(MediaType.APPLICATION_JSON) // Khớp code mẫu
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("data")) {
                throw new IllegalStateException("Lỗi: G-Pay trả về response không hợp lệ - " + response);
            }

            // 3. Trích xuất token từ object "data" theo code mẫu
            Map<String, Object> data = (Map<String, Object>) response.get("data");

            if (!data.containsKey("access_token")) {
                throw new IllegalStateException("Không tìm thấy access_token trong response data");
            }

            this.dynamicAccessToken = (String) data.get("access_token");

            // Xử lý thời gian hết hạn (nếu G-Pay không trả về expires_in, mặc định cache 1 tiếng)
            int expiresIn = 3600;
            if (data.containsKey("expires_in")) {
                expiresIn = Integer.parseInt(data.get("expires_in").toString());
            }
            this.tokenExpiryTime = System.currentTimeMillis() + (expiresIn * 1000L);

            log.info("Đã lấy access token G-Pay thành công");
            return this.dynamicAccessToken;

        } catch (RestClientResponseException ex) {
            log.warn("G-Pay từ chối yêu cầu access token, HTTP {}", ex.getStatusCode());
            throw new RuntimeException("G-Pay từ chối yêu cầu access token", ex);
        } catch (Exception e) {
            log.warn("Không thể lấy access token G-Pay: {}", e.getMessage());
            throw new RuntimeException("Không thể lấy Gpay Access Token tự động", e);
        }
    }

    public Map<String, Object> createVirtualAccount(String invoiceCode, int amount, String buyerName) throws Exception {
        String url = baseUrl + "/collection/va/create";
        String requestId = UUID.randomUUID().toString();
        String timestamp = String.valueOf(System.currentTimeMillis());

        // map_id dùng mã hóa đơn để định danh, account_type = "O" (dùng một lần)
        String mapId = invoiceCode;
        String mapType = "MHD"; // Mã hợp đồng / Mã đơn hàng
        String bankCode = "BIDV"; // Có thể đổi theo yêu cầu

        // TẠO JSON STRING CỐ ĐỊNH, CHUẨN THỨ TỰ VÀ KHÔNG KHOẢNG TRẮNG
        String jsonBody = "{"
                + "\"account_name\":\"" + escapeJson(buyerName) + "\","
                + "\"account_type\":\"O\","
                + "\"bank_code\":\"" + bankCode + "\","
                + "\"equal_amount\":" + amount + ","
                + "\"map_id\":\"" + escapeJson(mapId) + "\","
                + "\"map_type\":\"" + mapType + "\""
                + "}";

        log.info("Tạo virtual account G-Pay requestId={} invoiceCode={}", requestId, invoiceCode);

        String signature = securityUtil.generateSignature(timestamp, requestId, jsonBody, privateKey);

        try {
            byte[] rawBodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("signature", signature)
                    .header("x-certificate", certificate)
                    .header("x-requests-id", requestId)
                    .header("x-timestamp", timestamp)
                    .body(rawBodyBytes)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("data")) {
                throw new IllegalStateException("Lỗi tạo VA: " + response);
            }

            return (Map<String, Object>) response.get("data");

        } catch (Exception e) {
            log.warn("Tạo virtual account G-Pay thất bại requestId={}: {}", requestId, e.getMessage());
            throw e;
        }
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
