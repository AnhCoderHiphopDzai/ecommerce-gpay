package com.example.demo.service;

import com.example.backend.dao.InvoiceRepository;
import com.example.backend.dao.ProductRepository;
import com.example.backend.dto.OrderRequest;
import com.example.backend.model.Invoice;
import com.example.backend.model.Product;
import com.example.backend.service.InvoiceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional // Tự động Rollback DB sau khi test xong
public class PaymentIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private EntityManager entityManager; // Công cụ can thiệp sâu vào Database

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    private Product testProduct;

    @BeforeEach
    public void setup() {
        // Tạo 1 sản phẩm có kho = 10 trong Database để test
        testProduct = new Product();
        testProduct.setName("Laptop Test Hard Lock");
        testProduct.setStockQuantity(10);
        testProduct.setUnit("Cái");
        testProduct.setCurrentUnitPrice(new BigDecimal("10000000"));
        testProduct = productRepository.save(testProduct);
    }

    @Test
    public void testHardLock_ReducesStockImmediately_And_PreventsOverselling() {
        // 1. Ông A tạo đơn mua 8 chiếc
        OrderRequest reqA = createMockRequest("User A", 8);
        Invoice invoiceA = invoiceService.createInvoice(reqA);

        // 2. KIỂM TRA: Kho thực tế trong DB lập tức bị trừ còn 2
        Product productAfterA = productRepository.findById(testProduct.getProductId()).get();
        assertEquals(2, productAfterA.getStockQuantity(), "Kho phải bị trừ cứng ngay lập tức lúc tạo đơn");

        // 3. Ông B vào mua 3 chiếc (Trong khi kho chỉ còn 2)
        OrderRequest reqB = createMockRequest("User B", 3);

        // 4. KIỂM TRA: Hệ thống chặn ông B NGAY TẠI LÚC TẠO ĐƠN (ném RuntimeException)
        Exception exception = assertThrows(RuntimeException.class, () -> {
            invoiceService.createInvoice(reqB);
        });
        assertTrue(exception.getMessage().contains("đã hết hàng"));
    }

    @Test
    public void testCronJob_RestoresStock_ForExpiredInvoices() {
        // 1. Ông A mua 8 chiếc. Kho tụt xuống còn 2.
        OrderRequest reqA = createMockRequest("User A", 8);
        Invoice invoiceA = invoiceService.createInvoice(reqA);

        // 2. Dùng chiêu "Hack thời gian": Ép ngày tạo hóa đơn A lùi về 4 phút trước
        // Bắt buộc dùng Native Query để qua mặt cơ chế tự động điền giờ của JPA
        // 2. Dùng chiêu "Hack thời gian": Ép ngày tạo hóa đơn A lùi về 4 phút trước
        // Đổi từ tham số có tên (:oldDate) sang tham số theo vị trí (?1, ?2) để Hibernate không bị lỗi
        entityManager.createNativeQuery("UPDATE invoices SET created_at = ?1 WHERE invoice_id = ?2")
                .setParameter(1, LocalDateTime.now().minusMinutes(4))
                .setParameter(2, invoiceA.getInvoiceId())
                .executeUpdate();

        // 3. Gọi trực tiếp hàm CronJob (Bình thường Spring tự gọi ngầm)
        invoiceService.autoCancelExpiredInvoices();

        // 4. KIỂM TRA 1: Đơn hàng A phải bị ép đổi trạng thái thành CANCELLED
        Invoice canceledInvoice = invoiceRepository.findById(invoiceA.getInvoiceId()).get();
        assertEquals("CANCELLED", canceledInvoice.getStatus());

        // 5. KIỂM TRA 2: Kho phải được CỘNG TRẢ LẠI thành 10
        Product restoredProduct = productRepository.findById(testProduct.getProductId()).get();
        assertEquals(10, restoredProduct.getStockQuantity(), "CronJob phải hoàn trả lại số lượng kho");

        // 6. KIỂM TRA 3: Xác nhận WebSocket đã phát loa thông báo có hàng lại
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/stock-updates"),
                any(Object.class)
        );
    }

    private OrderRequest createMockRequest(String buyerName, int quantity) {
        OrderRequest req = new OrderRequest();
        req.setSellerId(1L); // Đảm bảo trong DB đã có Seller id = 1
        req.setBuyerName(buyerName);
        req.setBuyerPhone("0999999999");

        OrderRequest.OrderItem item = new OrderRequest.OrderItem();
        item.setProductId(testProduct.getProductId());
        item.setQuantity(quantity);

        req.setItems(List.of(item));
        return req;
    }
}