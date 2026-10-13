package com.example.backend.service;

import com.example.backend.dao.InvoiceRepository;
import com.example.backend.dao.ProductRepository;
import com.example.backend.dao.SellerRepository;
import com.example.backend.dto.OrderRequest;
import com.example.backend.model.Invoice;
import com.example.backend.model.InvoiceDetail;
import com.example.backend.model.Product;
import com.example.backend.model.Seller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * MÔ HÌNH: KHÔNG GIỮ HÀNG LÚC TẠO ĐƠN - CHỈ TRỪ KHO KHI TIỀN VỀ (WEBHOOK), XỬ LÝ TỪNG DÒNG SẢN PHẨM.
 *  - Tạo đơn: không lock, không trừ kho.
 *  - Webhook thành công: mở transaction -> với từng sản phẩm (id tăng dần): khoá -> đủ hàng thì trừ kho,
 *    thiếu hàng thì đánh dấu dòng đó OUT_OF_STOCK -> commit (vài ms).
 *  - Trạng thái đơn: tất cả dòng đủ -> PAID; một phần -> PARTIALLY_PAID; không dòng nào -> CANCELED_OUT_OF_STOCK.
 *  - Dừng ở bước chốt DB: không xử lý hoàn tiền, dòng hết hàng chỉ được đánh dấu OUT_OF_STOCK.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    // Không còn giữ hàng nên không cần hủy sau 3 phút nữa; chỉ dọn các đơn bỏ dở.
    // Nên cho bằng thời hạn hiệu lực của bill bên G-Pay.
    private static final long INVOICE_EXPIRE_MINUTES = 30;

    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SellerRepository sellerRepository;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // =========================================
    // 1. TẠO ĐƠN (KHÔNG LOCK, KHÔNG TRỪ KHO)
    // =========================================
    @Transactional
    public Invoice createInvoice(OrderRequest request) {
        Seller seller = sellerRepository.findById(request.getSellerId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Seller"));

        Invoice invoice = new Invoice();
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        invoice.setInvoiceCode("GPAY" + datePart + (1000 + new Random().nextInt(9000)));

        invoice.setSeller(seller);
        invoice.setBuyerName(request.getBuyerName());
        invoice.setBuyerPhone(request.getBuyerPhone());
        invoice.setStatus("PENDING");

        // Gộp các item trùng productId
        Map<Long, Integer> quantityByProduct = new TreeMap<>();
        for (OrderRequest.OrderItem item : request.getItems()) {
            if (item.getQuantity() <= 0) {
                throw new RuntimeException("Số lượng không hợp lệ");
            }
            quantityByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<String> outOfStockNames = new ArrayList<>();

        for (Map.Entry<Long, Integer> entry : quantityByProduct.entrySet()) {
            int quantity = entry.getValue();
            Product product = productRepository.findById(entry.getKey())
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy sản phẩm"));

            // RÀO MỀM (không lock, không trừ kho): gom TẤT CẢ sản phẩm đang thiếu để báo một lần,
            // khách bỏ chúng khỏi giỏ rồi vẫn đặt được phần còn lại.
            if (product.getStockQuantity() < quantity) {
                outOfStockNames.add(product.getName());
                continue;
            }

            InvoiceDetail detail = new InvoiceDetail();
            detail.setInvoice(invoice);
            detail.setProduct(product);
            detail.setQuantity(quantity);
            detail.setUnitPrice(product.getCurrentUnitPrice());

            BigDecimal lineTotal = product.getCurrentUnitPrice().multiply(new BigDecimal(quantity));
            detail.setLineTotal(lineTotal);
            totalAmount = totalAmount.add(lineTotal);
            invoice.getInvoiceDetails().add(detail);
        }

        if (!outOfStockNames.isEmpty()) {
            throw new RuntimeException("Sản phẩm đã hết hàng: " + String.join(", ", outOfStockNames)
                    + ". Vui lòng bỏ khỏi giỏ hàng để tiếp tục.");
        }

        invoice.setTotalAmount(totalAmount);
        return invoiceRepository.save(invoice);
    }

    // =========================================
    // 2. KIỂM TRA TRƯỚC KHI ĐI THANH TOÁN
    // =========================================
    @Transactional(readOnly = true)
    public void validateStockBeforePayment(String invoiceCode) {
        Invoice invoice = getInvoiceByCode(invoiceCode);

        if ("EXPIRED".equals(invoice.getStatus())) {
            throw new RuntimeException("Đơn hàng đã hết hạn thanh toán. Vui lòng đặt lại!");
        }
        if ("CANCELED_OUT_OF_STOCK".equals(invoice.getStatus())) {
            throw new RuntimeException("Đơn hàng đã bị hủy do hết hàng.");
        }
        if (!"PENDING".equals(invoice.getStatus())) {
            throw new RuntimeException("Hóa đơn không ở trạng thái hợp lệ để thanh toán.");
        }

        // RÀO MỀM: kho đã chắc chắn không đủ cho dòng nào thì báo luôn, đừng để khách trả tiền rồi mới bị hoàn.
        // (Không lock - chỉ là cảnh báo sớm. Khoá thật nằm ở settlePayment.)
        List<String> outOfStockNames = new ArrayList<>();
        for (InvoiceDetail detail : invoice.getInvoiceDetails()) {
            Product product = detail.getProduct();
            if (product.getStockQuantity() < detail.getQuantity()) {
                outOfStockNames.add(product.getName());
            }
        }
        if (!outOfStockNames.isEmpty()) {
            throw new RuntimeException("Sản phẩm đã hết hàng: " + String.join(", ", outOfStockNames)
                    + ". Vui lòng bỏ khỏi giỏ hàng và đặt lại.");
        }
    }

    // =========================================
    // 3. NHẬN KẾT QUẢ THANH TOÁN -> LOCK + TRỪ KHO TỪNG DÒNG
    // =========================================
    @Transactional
    public void handleVAPaymentSuccess(String vaNumber, String gpayTransId, BigDecimal amountPaid) {
        Invoice invoice = invoiceRepository.findByVirtualAccountNumberForUpdate(vaNumber)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn"));
        settlePayment(invoice, gpayTransId);
    }

    @Transactional
    public void handlePaymentSuccess(String invoiceCode, String gpayTransId) {
        Invoice invoice = invoiceRepository.findByInvoiceCodeForUpdate(invoiceCode)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn"));
        settlePayment(invoice, gpayTransId);
    }

    /**
     * Hạch toán một khoản tiền đã về. Gọi khi ĐÃ giữ khoá dòng invoice.
     * Thứ tự khoá cố định: invoice -> product (productId tăng dần) nên không deadlock.
     */
    private void settlePayment(Invoice invoice, String gpayTransId) {
        // Đơn đã chốt: webhook gửi lại hoặc đến muộn -> bỏ qua, không trừ kho lần nữa.
        // Các trạng thái còn lại (PENDING, EXPIRED, FAILED...) đều xử lý vì tiền thật đã về.
        String current = invoice.getStatus();
        if ("PAID".equals(current) || "PARTIALLY_PAID".equals(current) || "CANCELED_OUT_OF_STOCK".equals(current)) {
            log.info("Bỏ qua webhook cho đơn {} (đã chốt: {})", invoice.getInvoiceCode(), current);
            return;
        }

        invoice.setGpayTransId(gpayTransId);

        // Gom các dòng theo product, sắp xếp id tăng dần để khoá theo thứ tự cố định.
        // Trước khi khoá chỉ dùng getProductId() (không chạm field khác), nếu không Hibernate có thể
        // đã nạp product với stock cũ và query có lock sẽ chỉ khoá chứ không nạp lại dữ liệu.
        Map<Long, List<InvoiceDetail>> detailsByProduct = new TreeMap<>();
        for (InvoiceDetail detail : invoice.getInvoiceDetails()) {
            detailsByProduct
                    .computeIfAbsent(detail.getProduct().getProductId(), id -> new ArrayList<>())
                    .add(detail);
        }

        int fulfilledCount = 0;
        int outOfStockCount = 0;

        // Mỗi sản phẩm độc lập với nhau: khoá -> đủ hàng thì trừ, thiếu thì đánh dấu dòng đó.
        for (Map.Entry<Long, List<InvoiceDetail>> entry : detailsByProduct.entrySet()) {
            List<InvoiceDetail> lines = entry.getValue();
            int needed = lines.stream().mapToInt(InvoiceDetail::getQuantity).sum();

            Product product = productRepository.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy sản phẩm"));

            if (product.getStockQuantity() >= needed) {
                product.setStockQuantity(product.getStockQuantity() - needed);
                productRepository.save(product);
                lines.forEach(d -> d.setStatus("FULFILLED"));
                fulfilledCount++;

                publishStockUpdate(product);
                if (product.getStockQuantity() == 0) {
                    publishAfterCommit("/topic/sold-out", String.valueOf(product.getProductId()));
                }
            } else {
                log.warn("Đơn {}: sản phẩm {} thiếu hàng (còn {} < cần {})",
                        invoice.getInvoiceCode(), product.getProductId(), product.getStockQuantity(), needed);
                lines.forEach(d -> d.setStatus("OUT_OF_STOCK"));
                outOfStockCount++;
            }
        }

        // Chốt trạng thái đơn theo kết quả từng dòng
        if (outOfStockCount == 0) {
            invoice.setStatus("PAID");
            publishAfterCommit("/topic/payment-success", invoice.getInvoiceCode());
        } else if (fulfilledCount == 0) {
            invoice.setStatus("CANCELED_OUT_OF_STOCK");
            publishAfterCommit("/topic/payment-out-of-stock", invoice.getInvoiceCode());
        } else {
            invoice.setStatus("PARTIALLY_PAID");
            publishAfterCommit("/topic/payment-partial", invoice.getInvoiceCode());
        }
        invoiceRepository.save(invoice);
        log.info("Đã chốt đơn {} với trạng thái {}", invoice.getInvoiceCode(), invoice.getStatus());
    }

    // =========================================
    // 4. THANH TOÁN THẤT BẠI: chỉ đổi trạng thái (chưa trừ kho nên không có gì để hoàn)
    // =========================================
    @Transactional
    public void handlePaymentFailed(String invoiceCode) {
        Invoice invoice = invoiceRepository.findByInvoiceCodeForUpdate(invoiceCode).orElse(null);
        if (invoice != null && "PENDING".equals(invoice.getStatus())) {
            invoice.setStatus("FAILED");
            invoiceRepository.save(invoice);
        }
    }

    // =========================================
    // 5. CRONJOB: DỌN ĐƠN BỎ DỞ (KHÔNG ĐỘNG TỚI KHO)
    //    TUYỆT ĐỐI không cộng kho ở đây: đơn PENDING chưa từng bị trừ kho,
    //    cộng vào sẽ làm tồn kho "ảo" và gây bán quá số lượng.
    // =========================================
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void expireAbandonedInvoices() {
        LocalDateTime limit = LocalDateTime.now().minusMinutes(INVOICE_EXPIRE_MINUTES);
        int expired = invoiceRepository.expirePendingBefore(limit);
        if (expired > 0) {
            log.info("CRONJOB: đã đánh dấu {} đơn PENDING quá hạn là EXPIRED", expired);
        }
    }

    // =========================================
    // Các hàm phụ trợ
    // =========================================
    @Transactional
    public void updateInvoiceVirtualAccount(String invoiceCode, String vaNumber, String vaBank) {
        Invoice invoice = getInvoiceByCode(invoiceCode);
        invoice.setVirtualAccountNumber(vaNumber);
        invoice.setVirtualAccountBank(vaBank);
        invoiceRepository.save(invoice);
    }

    public Invoice getInvoiceByCode(String invoiceCode) {
        return invoiceRepository.findByInvoiceCode(invoiceCode)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn: " + invoiceCode));
    }

    private void publishStockUpdate(Product product) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("productId", product.getProductId());
        payload.put("stockQuantity", product.getStockQuantity());
        publishAfterCommit("/topic/stock-updates", payload);
    }

    /** Chỉ gửi WebSocket sau khi transaction commit thành công (rollback thì không gửi). */
    private void publishAfterCommit(String destination, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messagingTemplate.convertAndSend(destination, payload);
                }
            });
        } else {
            messagingTemplate.convertAndSend(destination, payload);
        }
    }
}