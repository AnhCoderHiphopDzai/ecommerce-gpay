package com.example.backend.service;

import com.example.backend.dao.InvoiceRepository;
import com.example.backend.dao.ProductRepository;
import com.example.backend.dao.SellerRepository;
import com.example.backend.dao.UserRepository;
import com.example.backend.dto.OrderRequest;
import com.example.backend.model.Invoice;
import com.example.backend.model.InvoiceDetail;
import com.example.backend.model.Product;
import com.example.backend.model.Seller;
import com.example.backend.security.AppUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final long INVOICE_EXPIRE_MINUTES = 30;

    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SellerRepository sellerRepository;
    // 👉 BỔ SUNG (Auth)
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    //  NÂNG CẤP LUA SCRIPT:
    // Nếu đủ hàng: trừ kho và trả về chính xgit ác SỐ TỒN KHO CÒN LẠI (>= 0).
    // Nếu thiếu hàng: trả về -1.
    private static final String LUA_SCRIPT_DECR_STOCK =
            "local stock = tonumber(redis.call('get', KEYS[1])) " +
                    "local amount = tonumber(ARGV[1]) " +
                    "if stock and stock >= amount then " +
                    "    return redis.call('decrby', KEYS[1], amount) " +
                    "else " +
                    "    return -1 " +
                    "end";

    private int getStockFromRedis(Long productId) {
        String key = "product:" + productId + ":stock";
        String stockStr = redisTemplate.opsForValue().get(key);
        if (stockStr == null) {
            // Fallback: Nếu key chưa có trên Redis thì đọc từ MySQL và nạp lên bằng setIfAbsent
            return productRepository.findById(productId)
                    .map(p -> {
                        redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(p.getStockQuantity()));
                        return p.getStockQuantity();
                    })
                    .orElse(0);
        }
        return Integer.parseInt(stockStr);
    }

    // =========================================
    // 1. TẠO ĐƠN (KHÔNG LOCK, RÀO MỀM TRÊN REDIS)
    // =========================================
    @Transactional
    public Invoice createInvoice(OrderRequest request, AppUserDetails principal) {
        Seller seller = sellerRepository.findById(request.getSellerId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Seller"));

        Invoice invoice = new Invoice();
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        invoice.setInvoiceCode("GPAY" + datePart + (1000 + new Random().nextInt(9000)));

        invoice.setSeller(seller);
        // 👉 BỔ SUNG (Auth): gắn hóa đơn với tài khoản đang đăng nhập
        invoice.setUser(userRepository.getReferenceById(principal.getId()));
        // Form không nhập tên/SĐT thì lấy từ hồ sơ tài khoản
        invoice.setBuyerName(StringUtils.hasText(request.getBuyerName())
                ? request.getBuyerName().trim() : principal.getFullName());
        invoice.setBuyerPhone(StringUtils.hasText(request.getBuyerPhone())
                ? request.getBuyerPhone().trim() : principal.getPhone());
        invoice.setStatus("PENDING");

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

            int currentStock = getStockFromRedis(product.getProductId());
            if (currentStock < quantity) {
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

        List<String> outOfStockNames = new ArrayList<>();
        for (InvoiceDetail detail : invoice.getInvoiceDetails()) {
            Product product = detail.getProduct();
            int currentStock = getStockFromRedis(product.getProductId());
            if (currentStock < detail.getQuantity()) {
                outOfStockNames.add(product.getName());
            }
        }
        if (!outOfStockNames.isEmpty()) {
            throw new RuntimeException("Sản phẩm đã hết hàng: " + String.join(", ", outOfStockNames)
                    + ". Vui lòng bỏ khỏi giỏ hàng và đặt lại.");
        }
    }

    // =========================================
    // 3. NHẬN KẾT QUẢ THANH TOÁN -> TRỪ KHO AN TOÀN
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

    private void settlePayment(Invoice invoice, String gpayTransId) {
        String current = invoice.getStatus();
        if ("PAID".equals(current) || "PARTIALLY_PAID".equals(current) || "CANCELED_OUT_OF_STOCK".equals(current)) {
            log.info("Bỏ qua webhook cho đơn {} (đã chốt: {})", invoice.getInvoiceCode(), current);
            return;
        }

        invoice.setGpayTransId(gpayTransId);

        Map<Long, List<InvoiceDetail>> detailsByProduct = new TreeMap<>();
        for (InvoiceDetail detail : invoice.getInvoiceDetails()) {
            detailsByProduct
                    .computeIfAbsent(detail.getProduct().getProductId(), id -> new ArrayList<>())
                    .add(detail);
        }

        int fulfilledCount = 0;
        int outOfStockCount = 0;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_SCRIPT_DECR_STOCK, Long.class);

        for (Map.Entry<Long, List<InvoiceDetail>> entry : detailsByProduct.entrySet()) {
            Long productId = entry.getKey();
            List<InvoiceDetail> lines = entry.getValue();
            int needed = lines.stream().mapToInt(InvoiceDetail::getQuantity).sum();

            // Đảm bảo key đã có trên Redis trước khi chạy Lua
            getStockFromRedis(productId);

            String redisKey = "product:" + productId + ":stock";
            Long remainingStock = redisTemplate.execute(
                    script,
                    Collections.singletonList(redisKey),
                    String.valueOf(needed)
            );

            // remainingStock >= 0 nghĩa là trừ kho Redis thành công
            if (remainingStock != null && remainingStock >= 0) {
                //  CHỐNG LỖ HỔNG 1: Đăng ký hoàn lại kho Redis nếu MySQL Transaction bị Rollback
                registerRedisRollbackCompensation(redisKey, needed, invoice.getInvoiceCode());

                //  CHỐNG LỖ HỔNG 2: Trừ kho tương đối trong MySQL, không ghi đè bằng save(product)
                int updatedRows = productRepository.decreaseStockAtomic(productId, needed);
                if (updatedRows == 0) {
                    // Nếu DB vì lý do nào đó không đủ hàng dù Redis báo đủ -> ném lỗi để Rollback cả MySQL và hoàn kho Redis
                    throw new IllegalStateException("Lệch dữ liệu tồn kho giữa Redis và MySQL tại productId=" + productId);
                }

                lines.forEach(d -> d.setStatus("FULFILLED"));
                fulfilledCount++;

                publishStockUpdate(productId, remainingStock.intValue());
                if (remainingStock == 0) {
                    publishAfterCommit("/topic/sold-out", String.valueOf(productId));
                }
            } else {
                int currentStock = getStockFromRedis(productId);
                log.warn("Đơn {}: sản phẩm {} thiếu hàng (còn {} < cần {})",
                        invoice.getInvoiceCode(), productId, currentStock, needed);
                lines.forEach(d -> d.setStatus("OUT_OF_STOCK"));
                outOfStockCount++;
            }
        }

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

    /**
     * Bẫy sự kiện Rollback của MySQL để hoàn lại số lượng đã trừ trên Redis.
     */
    private void registerRedisRollbackCompensation(String redisKey, int deductedAmount, String invoiceCode) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        redisTemplate.opsForValue().increment(redisKey, deductedAmount);
                        log.error("ROLLBACK COMPENSATED: MySQL rollback ở đơn {}, đã cộng trả lại {} vào Redis key {}",
                                invoiceCode, deductedAmount, redisKey);
                    }
                }
            });
        }
    }

    // =========================================
    // 4. THANH TOÁN THẤT BẠI
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
    // 5. CRONJOB: DỌN ĐƠN BỎ DỞ
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

    /**
     * 👉 BỔ SUNG (Auth): lấy hóa đơn cho người đang đăng nhập.
     * Chỉ chủ hóa đơn hoặc ADMIN mới xem được.
     * Không phải chủ thì báo "không tìm thấy" (giống mã không tồn tại) để không lộ mã nào có thật.
     */
    @Transactional(readOnly = true)
    public Invoice getInvoiceForUser(String invoiceCode, AppUserDetails principal) {
        Invoice invoice = getInvoiceByCode(invoiceCode);
        boolean isOwner = invoice.getUser() != null
                && Objects.equals(invoice.getUser().getUserId(), principal.getId());
        if (!isOwner && !principal.isAdmin()) {
            throw new RuntimeException("Không tìm thấy hóa đơn: " + invoiceCode);
        }
        return invoice;
    }

    public Invoice getInvoiceByCode(String invoiceCode) {
        return invoiceRepository.findByInvoiceCode(invoiceCode)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn: " + invoiceCode));
    }

    private void publishStockUpdate(Long productId, int newStockQuantity) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("productId", productId);
        payload.put("stockQuantity", newStockQuantity);
        publishAfterCommit("/topic/stock-updates", payload);
    }

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