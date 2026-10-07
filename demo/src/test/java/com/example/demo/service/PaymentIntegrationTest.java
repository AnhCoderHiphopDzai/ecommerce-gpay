package com.example.demo.service;

import com.example.backend.dao.InvoiceRepository;
import com.example.backend.dao.ProductRepository;
import com.example.backend.dto.OrderRequest;
import com.example.backend.model.Invoice;
import com.example.backend.model.Product;
import com.example.backend.service.InvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional // Tự động Rollback DB sau khi test xong, không lo rác dữ liệu
public class PaymentIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    // "Làm giả" WebSocket để test xem Backend có phát lệnh gửi đi không
    // mà không cần phải kết nối Frontend thật
    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    private Product testProduct;

    @BeforeEach
    public void setup() {
        // Tạo 1 sản phẩm có số lượng = 1 trong Database để test
        testProduct = new Product();
        testProduct.setName("Laptop Test");
        testProduct.setStockQuantity(1); // CHỈ CÓ 1 CHIẾC
        testProduct.setUnit("Cái");
        testProduct.setCurrentUnitPrice(new BigDecimal("10000000"));
        testProduct = productRepository.save(testProduct);
    }

    @Test
    public void testJustInTimeLock_ShouldThrowExceptionForSecondUser() {
        // 1. Ông A vào hệ thống, tạo đơn (Số lượng 1)
        OrderRequest reqA = createMockRequest("User A");
        Invoice invoiceA = invoiceService.createInvoice(reqA);

        // 2. Ông A lập tức bấm nút "Thanh toán QR".
        // Vì lúc này DB chưa có ai khác mua, ông A qua trót lọt (Không bị ném lỗi).
        assertDoesNotThrow(() -> {
            invoiceService.validateStockBeforePayment(invoiceA.getInvoiceCode());
        });

        // 3. Trong lúc ông A đang quét mã QR (chưa thanh toán xong),
        // Ông B vào sau và cũng tạo đơn mua đúng sản phẩm đó
        OrderRequest reqB = createMockRequest("User B");
        Invoice invoiceB = invoiceService.createInvoice(reqB);

        // 4. Ông B bấm nút "Thanh toán QR".
        // KẾT QUẢ: Hệ thống quét thấy hóa đơn của ông A vẫn đang PENDING (chưa quá 3 phút),
        // nên bắt buộc phải ném ra Exception chặn ông B lại!
        Exception exception = assertThrows(RuntimeException.class, () -> {
            invoiceService.validateStockBeforePayment(invoiceB.getInvoiceCode());
        });

        assertTrue(exception.getMessage().contains("đang được khách khác thanh toán"));
    }

    @Test
    public void testWebSocketIsTriggered_OnPaymentSuccess() {
        // 1. Ông A tạo đơn
        OrderRequest req = createMockRequest("User A");
        Invoice invoice = invoiceService.createInvoice(req);

        // 2. Webhook G-Pay gọi về báo thanh toán thành công
        invoiceService.handlePaymentSuccess(invoice.getInvoiceCode(), "TRANS_123");

        // 3. KIỂM TRA DATABASE: Số lượng sản phẩm phải bị trừ về 0
        Product updatedProduct = productRepository.findById(testProduct.getProductId()).get();
        assertEquals(0, updatedProduct.getStockQuantity(), "Tồn kho phải bị trừ về 0");

        // 4. KIỂM TRA WEBSOCKET: Xác nhận Backend ĐÃ bắn thông báo realtime (Live Stock)
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/stock-updates"),
                any(Object.class)
        );

        // 5. KIỂM TRA WEBSOCKET: Xác nhận Backend ĐÃ bắn lệnh đuổi khách (Kick-out) do hàng về 0
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/sold-out"),
                eq(String.valueOf(testProduct.getProductId()))
        );
    }
    @Test
    public void testJustInTimeLock_FirstComeFirstServe_AvoidsDeadlock() {
        // 0. Cập nhật tồn kho của sản phẩm test lên 10 chiếc cho kịch bản này
        testProduct.setStockQuantity(10);
        productRepository.save(testProduct);

        // 1. Ông A vào hệ thống trước, tạo đơn mua 8 chiếc. (Được cấp ID = 1)
        OrderRequest reqA = createMockRequest("User A", 8);
        Invoice invoiceA = invoiceService.createInvoice(reqA);

        // 2. Ông B vào hệ thống sau vài mili-giây, tạo đơn mua 3 chiếc. (Được cấp ID = 2)
        OrderRequest reqB = createMockRequest("User B", 3);
        Invoice invoiceB = invoiceService.createInvoice(reqB);

        // Xác nhận ID của B chắc chắn lớn hơn A (tạo sau)
        assertTrue(invoiceB.getInvoiceId() > invoiceA.getInvoiceId(), "Hóa đơn B phải được tạo sau A");

        // 3. KỊCH BẢN ĐẶC BIỆT: Ông B lanh chanh bấm nút "Thanh toán QR" TRƯỚC ông A
        // KẾT QUẢ: Hệ thống quét thấy có ông A (ID nhỏ hơn) đang PENDING 8 chiếc.
        // Kho ảo với B lúc này chỉ còn 10 - 8 = 2. Ông B đòi mua 3 -> CHẮC CHẮN BỊ CHẶN!
        Exception exception = assertThrows(RuntimeException.class, () -> {
            invoiceService.validateStockBeforePayment(invoiceB.getInvoiceCode());
        });
        assertTrue(exception.getMessage().contains("đang được khách khác thanh toán"));

        // 4. Ông A thong thả bấm nút "Thanh toán QR" SAU
        // KẾT QUẢ: Hệ thống quét những người đến trước A (ID < ID của A) -> KHÔNG CÓ AI.
        // Ông B không được tính vì B đến sau A (ID của B > ID của A).
        // Kho ảo với A lúc này vẫn là 10 - 0 = 10. Ông A đòi mua 8 -> ĐI QUA TRÓT LỌT!
        assertDoesNotThrow(() -> {
            invoiceService.validateStockBeforePayment(invoiceA.getInvoiceCode());
        });
    }

    // Hàm phụ trợ để tạo Request rác nhanh chóng
    // Hàm phụ trợ 1: Mặc định mua 1 chiếc (giữ nguyên cho các test cũ chạy được)
    private OrderRequest createMockRequest(String buyerName) {
        return createMockRequest(buyerName, 1);
    }

    // Hàm phụ trợ 2: Cho phép truyền số lượng tùy ý
    private OrderRequest createMockRequest(String buyerName, int quantity) {
        OrderRequest req = new OrderRequest();
        req.setSellerId(1L); // Đảm bảo trong DB đã có Seller id = 1
        req.setBuyerName(buyerName);
        req.setBuyerPhone("0999999999");

        OrderRequest.OrderItem item = new OrderRequest.OrderItem();
        item.setProductId(testProduct.getProductId());
        item.setQuantity(quantity); // Số lượng mua

        req.setItems(List.of(item));
        return req;
    }
}