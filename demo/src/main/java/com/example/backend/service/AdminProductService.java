package com.example.backend.service;

import com.example.backend.dao.ProductRepository;
import com.example.backend.dto.ProductRequest;
import com.example.backend.model.Product;
import com.example.backend.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Thêm/sửa/xóa sản phẩm cho ADMIN.
 *
 * Điểm cần chú ý: tồn kho "thật" khi bán hàng nằm trên Redis (chống bán quá số lượng) và MySQL được trừ theo
 * (xem InvoiceService.settlePayment). Vì vậy mọi thay đổi tồn kho của admin phải được đồng bộ sang Redis,
 * nếu không giá trị trên Redis sẽ lệch và khách vẫn thấy/mua theo số cũ.
 */
@Service
@RequiredArgsConstructor
public class AdminProductService {

    private static final Logger AUDIT = LoggerFactory.getLogger("security.audit");

    private final ProductRepository productRepository;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public Product create(ProductRequest request, AppUserDetails admin) {
        Product product = new Product();
        product.setName(request.getName().trim());
        product.setUnit(request.getUnit().trim());
        product.setCurrentUnitPrice(request.getCurrentUnitPrice());
        product.setStockQuantity(request.getStockQuantity());
        Product saved = productRepository.saveAndFlush(product);

        // Sản phẩm mới phải có key tồn kho trên Redis ngay
        Long productId = saved.getProductId();
        int stock = request.getStockQuantity();
        String key = stockKey(productId);
        redisTemplate.opsForValue().set(key, String.valueOf(stock));
        afterTransaction(() -> publishStock(productId, stock), () -> redisTemplate.delete(key));

        AUDIT.info("event=ADMIN_PRODUCT_CREATED adminId={} productId={}", admin.getId(), productId);
        return saved;
    }

    @Transactional
    public Product update(Long id, ProductRequest request, AppUserDetails admin) {
        // Khóa dòng sản phẩm: một thanh toán đang trừ kho (UPDATE trong MySQL) sẽ chờ admin sửa xong,
        // nên không có chuyện hai bên ghi đè lên nhau.
        Product product = productRepository.findByIdForUpdate(id).orElseThrow(this::notFound);

        int oldStock = product.getStockQuantity();
        int newStock = request.getStockQuantity();

        product.setName(request.getName().trim());
        product.setUnit(request.getUnit().trim());
        product.setCurrentUnitPrice(request.getCurrentUnitPrice());
        product.setStockQuantity(newStock);
        Product saved = productRepository.saveAndFlush(product);

        if (newStock != oldStock) {
            syncRedisStock(id, newStock - oldStock, newStock);
        }
        AUDIT.info("event=ADMIN_PRODUCT_UPDATED adminId={} productId={} stock={}->{}",
                admin.getId(), id, oldStock, newStock);
        return saved;
    }

    @Transactional
    public void delete(Long id, AppUserDetails admin) {
        Product product = productRepository.findByIdForUpdate(id).orElseThrow(this::notFound);

        // Sản phẩm đã nằm trong hóa đơn thì không xóa: xóa sẽ vi phạm khóa ngoại hoặc làm mất lịch sử bán hàng
        if (productRepository.countInvoiceLines(id) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sản phẩm đã nằm trong hóa đơn nên không xóa được. Hãy đặt tồn kho về 0 để ngừng bán.");
        }

        productRepository.delete(product);
        productRepository.flush();

        String key = stockKey(id);
        afterTransaction(() -> redisTemplate.delete(key), null);
        AUDIT.info("event=ADMIN_PRODUCT_DELETED adminId={} productId={}", admin.getId(), id);
    }

    /**
     * Áp CHÊNH LỆCH lên Redis chứ không ghi đè: nếu lúc này có đơn đang trừ kho thì phần trừ của họ không bị mất.
     * (Chênh lệch tính từ số đang có trong MySQL, đọc dưới khóa dòng.)
     */
    private void syncRedisStock(Long productId, int delta, int newStock) {
        String key = stockKey(productId);
        Boolean created = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(newStock));
        if (Boolean.TRUE.equals(created)) {
            afterTransaction(() -> publishStock(productId, newStock), () -> redisTemplate.delete(key));
            return;
        }
        Long current = redisTemplate.opsForValue().increment(key, delta);
        long shown = current == null ? newStock : current;
        // Giao dịch MySQL bị rollback thì trả lại phần đã cộng/trừ trên Redis
        afterTransaction(() -> publishStock(productId, shown),
                () -> redisTemplate.opsForValue().increment(key, -delta));
    }

    private void publishStock(Long productId, long stock) {
        // Khai báo kiểu Object để Java không nhầm với overload convertAndSend(payload, headers)
        Object payload = Map.of("productId", productId, "stockQuantity", stock);
        messagingTemplate.convertAndSend("/topic/stock-updates", payload);
    }

    /** Chạy onCommit sau khi MySQL commit; chạy onRollback (nếu có) khi MySQL rollback. */
    private void afterTransaction(Runnable onCommit, Runnable onRollback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                onCommit.run();
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK && onRollback != null) {
                    onRollback.run();
                }
            }
        });
    }

    private static String stockKey(Long productId) {
        return "product:" + productId + ":stock";
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm");
    }
}