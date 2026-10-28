package com.example.backend.controller;

import com.example.backend.model.Invoice;
import com.example.backend.security.AppUserDetails;
import com.example.backend.service.InvoiceService;
import com.example.backend.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import com.example.backend.utils.GpaySecurityUtil;

import jakarta.servlet.http.HttpServletRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private GpaySecurityUtil securityUtil;

    // Lấy Public Key của G-Pay từ cấu hình (đọc trực tiếp file pem trong resources)
    @Value("classpath:gpay_public.pem")
    private org.springframework.core.io.Resource gpayPublicKeyResource;

    @GetMapping("/get-url")
    public ResponseEntity<?> getPaymentUrl(@RequestParam String invoiceCode,
                                           // 👉 BỔ SUNG (Auth): giữ tham số này để frontend cũ không lỗi nhưng KHÔNG dùng nữa.
                                           // Trước đây client tự gửi số tiền nên có thể tạo đơn 10 triệu rồi gửi amount=1000.
                                           @RequestParam(required = false) Integer amount,
                                           @AuthenticationPrincipal AppUserDetails principal) {
        try {
            // Chỉ chủ hóa đơn (hoặc ADMIN) mới được tạo link thanh toán
            Invoice invoice = invoiceService.getInvoiceForUser(invoiceCode, principal);
            invoiceService.validateStockBeforePayment(invoiceCode);
            // Số tiền luôn lấy từ hóa đơn trong DB (đúng như luồng Virtual Account bên dưới)
            String billUrl = paymentService.createPaymentUrl(invoiceCode, invoice.getTotalAmount().intValue());
            return ResponseEntity.ok(billUrl);
        } catch (Exception e) {
            log.error("Lỗi khi tạo URL thanh toán G-Pay cho hóa đơn: {}", invoiceCode, e);
            return ResponseEntity.badRequest().body("Lỗi kết nối G-Pay: " + e.getMessage());
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> gpayWebhookAllInOne(HttpServletRequest request) {
        log.info("====== NHẬN THÔNG BÁO WEBHOOK TỪ G-PAY ======");
        try {
            String rawJsonBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
            log.info("Raw Body nhận được: {}", rawJsonBody);

            JsonParser springParser = JsonParserFactory.getJsonParser();
            Map<String, Object> payload = springParser.parseMap(rawJsonBody);

            String signature = (String) payload.get("signature");
            if (signature == null || signature.isBlank()) {
                log.error("Không tìm thấy chữ ký trong payload!");
                return ResponseEntity.badRequest().body("Missing signature");
            }

            // Đọc Public Key từ file (Dùng chung cho cả 2 luồng)
            String gpayPublicKey = new String(gpayPublicKeyResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // ==========================================
            // LUỒNG 1: XỬ LÝ TÀI KHOẢN ẢO (VIRTUAL ACCOUNT)
            // ==========================================
            if (payload.containsKey("action") && "CHANGE_BALANCE".equals(payload.get("action"))) {
                log.info("=> Phân loại: Webhook Tài khoản ảo (VA)");

                String gpayTransId = String.valueOf(payload.get("gpay_trans_id"));
                String bankTraceId = String.valueOf(payload.get("bank_trace_id"));
                String bankTransactionId = String.valueOf(payload.get("bank_transaction_id"));
                String accountNumber = String.valueOf(payload.get("account_number"));
                String amountStr = String.valueOf(payload.get("amount"));
                String message = String.valueOf(payload.get("message"));
                String action = String.valueOf(payload.get("action"));

                String rawDataToVerify = String.format(
                        "gpay_trans_id=%s&bank_trace_id=%s&bank_transaction_id=%s&account_number=%s&amount=%s&message=%s&action=%s",
                        gpayTransId.equals("null") ? null : gpayTransId,
                        bankTraceId.equals("null") ? null : bankTraceId,
                        bankTransactionId.equals("null") ? null : bankTransactionId,
                        accountNumber.equals("null") ? null : accountNumber,
                        amountStr.equals("null") ? null : amountStr,
                        message.equals("null") ? null : message,
                        action
                );

                boolean isValid = securityUtil.verifySignature(rawDataToVerify, signature, gpayPublicKey);
                if (!isValid) {
                    log.error("Chữ ký Webhook VA THẤT BẠI!");
                    return ResponseEntity.status(401).body("Unauthorized");
                }

                int amount = amountStr.equals("null") ? 0 : Integer.parseInt(amountStr);
                invoiceService.handleVAPaymentSuccess(accountNumber, gpayTransId, new BigDecimal(amount));
                log.info("Xử lý nạp tiền VA thành công cho tài khoản: {}", accountNumber);
                return ResponseEntity.ok("Success");
            }

            // ==========================================
            // LUỒNG 2: XỬ LÝ CỔNG THANH TOÁN (PAYMENT GATEWAY)
            // ==========================================
            else if (payload.containsKey("merchant_order_id")) {
                log.info("=> Phân loại: Webhook Cổng thanh toán trực tiếp");

                String merchantOrderId = String.valueOf(payload.get("merchant_order_id"));
                String gpayTransId = String.valueOf(payload.get("gpay_trans_id"));
                String gpayBillId = String.valueOf(payload.get("gpay_bill_id"));
                String status = String.valueOf(payload.get("status"));
                String embedData = String.valueOf(payload.get("embed_data"));
                String userPaymentMethod = String.valueOf(payload.get("user_payment_method"));

                String rawData = String.format(
                        "merchant_order_id=%s&gpay_trans_id=%s&gpay_bill_id=%s&status=%s&embed_data=%s&user_payment_method=%s",
                        merchantOrderId.equals("null") ? null : merchantOrderId,
                        gpayTransId.equals("null") ? null : gpayTransId,
                        gpayBillId.equals("null") ? null : gpayBillId,
                        status.equals("null") ? null : status,
                        embedData.equals("null") ? null : embedData,
                        userPaymentMethod.equals("null") ? null : userPaymentMethod
                );

                boolean isValid = securityUtil.verifySignature(rawData, signature, gpayPublicKey);
                if (!isValid) {
                    log.error("Chữ ký Webhook Cổng thanh toán THẤT BẠI!");
                    return ResponseEntity.status(401).body("Unauthorized");
                }

                if (embedData != null && !embedData.equals("null")) {
                    if ("ORDER_SUCCESS".equals(status) || "SUCCESS".equals(status)) {
                        invoiceService.handlePaymentSuccess(embedData, gpayTransId);
                        log.info("ĐÃ CẬP NHẬT HÓA ĐƠN {} THÀNH PAID.", embedData);
                    } else {
                        invoiceService.handlePaymentFailed(embedData);
                    }
                }
                return ResponseEntity.ok("Success");
            }

            // ==========================================
            // LUỒNG 3: DỮ LIỆU KHÔNG XÁC ĐỊNH
            // ==========================================
            else {
                log.warn("Nhận được Webhook không rõ định dạng từ G-Pay.");
                return ResponseEntity.badRequest().body("Unknown Webhook format");
            }

        } catch (Exception e) {
            log.error("Lỗi Exception khi xử lý webhook tổng: ", e);
            return ResponseEntity.internalServerError().body("Error processing webhook");
        }
    }

    @GetMapping("/get-va")
    public ResponseEntity<?> getVirtualAccount(@RequestParam String invoiceCode,
                                               @AuthenticationPrincipal AppUserDetails principal) { // 👉 BỔ SUNG (Auth)
        try {
            // Kiểm tra quyền sở hữu trước, rồi mới kiểm tra tồn kho
            Invoice invoice = invoiceService.getInvoiceForUser(invoiceCode, principal);
            invoiceService.validateStockBeforePayment(invoiceCode);

            // Gọi sang G-Pay để tạo tài khoản ảo
            Map<String, Object> vaData = paymentService.createVirtualAccount(
                    invoiceCode,
                    invoice.getTotalAmount().intValue(),
                    invoice.getBuyerName()
            );

            String vaNumber = (String) vaData.get("account_number");
            String bankCode = (String) vaData.getOrDefault("bank_code", "BIDV");

            // Lưu thông tin số tài khoản ảo vào DB để đối soát khi có Webhook trả về
            invoiceService.updateInvoiceVirtualAccount(invoiceCode, vaNumber, bankCode);

            // Trả toàn bộ data (bao gồm qr_code_image) về cho React hiển thị
            return ResponseEntity.ok(vaData);
        } catch (Exception e) {
            log.error("Lỗi khi tạo Virtual Account cho hóa đơn: {}", invoiceCode, e);
            return ResponseEntity.badRequest().body("Lỗi tạo Virtual Account: " + e.getMessage());
        }
    }



    @GetMapping("/callback")
    public RedirectView gpayCallback() {
        return new RedirectView("http://localhost:5173/");
    }
}
