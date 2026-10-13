package com.example.demo.service;

import com.example.backend.dao.InvoiceRepository;
import com.example.backend.dao.ProductRepository;
import com.example.backend.dao.SellerRepository;
import com.example.backend.model.Invoice;
import com.example.backend.model.InvoiceDetail;
import com.example.backend.model.Product;
import com.example.backend.model.Seller;
import com.example.backend.service.InvoiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test KHÔNG đặt @Transactional ở lớp này: các thread phải commit thật vào DB thì mới có tranh chấp thật.
 * Đặt file tại: src/test/java/com/example/backend/service/
 * Chạy trên DB giống production (MySQL/PostgreSQL), nên dùng schema riêng để thử.
 * Mỗi test tự tạo dữ liệu (seller, product, invoice) và tự xoá sau khi chạy xong.
 */
@SpringBootTest
class InvoiceStockConcurrencyTest {

    @Autowired private InvoiceService invoiceService;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private PlatformTransactionManager txManager;

    private Seller seller;
    private final List<Long> invoiceIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Seller s = new Seller();
        s.setName("Test seller");
        s.setPhone("0900000000");
        seller = sellerRepository.save(s);
    }

    @AfterEach
    void cleanUp() {
        invoiceIds.forEach(invoiceRepository::deleteById);
        productIds.forEach(productRepository::deleteById);
        sellerRepository.deleteById(seller.getSellerId());
    }

    // ---------------------------------------------------------------
    // 1. 50 người mua 1 cái, kho chỉ có 10 -> đúng 10 đơn thành công
    // ---------------------------------------------------------------
    @Test
    void fiftyBuyersForTenItems_exactlyTenSucceed() throws Exception {
        Product p = newProduct(10);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            codes.add(newPendingInvoice(p, 1));
        }

        List<Runnable> tasks = new ArrayList<>();
        for (String code : codes) {
            tasks.add(() -> pay(code));
        }
        runConcurrently(tasks);

        assertEquals(0, stockOf(p), "Kho phải về 0, không được âm");
        assertEquals(10, countStatus(codes, "PAID"));
        assertEquals(40, countStatus(codes, "CANCELED_OUT_OF_STOCK"));
    }

    // ---------------------------------------------------------------
    // 2. Kịch bản 5 màn hình, 3 người mua 2 / 3 / 1, thanh toán đồng thời.
    //    Thứ tự thread không cố định nên kết quả cụ thể có thể khác nhau,
    //    chỉ kiểm tra các bất biến luôn phải đúng.
    // ---------------------------------------------------------------
    @Test
    void threeBuyers_concurrent_invariantsHold() throws Exception {
        Product p = newProduct(5);
        String c1 = newPendingInvoice(p, 2);
        String c2 = newPendingInvoice(p, 3);
        String c3 = newPendingInvoice(p, 1);

        runConcurrently(List.<Runnable>of(() -> pay(c1), () -> pay(c2), () -> pay(c3)));

        int sold = 0;
        if ("PAID".equals(statusOf(c1))) sold += 2;
        if ("PAID".equals(statusOf(c2))) sold += 3;
        if ("PAID".equals(statusOf(c3))) sold += 1;

        int stock = stockOf(p);
        assertTrue(stock >= 0, "Kho không được âm, hiện tại: " + stock);
        assertEquals(5 - sold, stock, "Kho phải bằng 5 trừ tổng số lượng các đơn đã PAID");
        for (String c : List.of(c1, c2, c3)) {
            String st = statusOf(c);
            assertTrue("PAID".equals(st) || "CANCELED_OUT_OF_STOCK".equals(st), "Trạng thái lạ: " + st);
        }
    }

    // ---------------------------------------------------------------
    // 2b. Đúng thứ tự trong ví dụ của bạn: người 2 -> người 1 -> người 3
    // ---------------------------------------------------------------
    @Test
    void threeBuyers_sequentialOrder_matchesScenario() {
        Product p = newProduct(5);
        String c1 = newPendingInvoice(p, 2);
        String c2 = newPendingInvoice(p, 3);
        String c3 = newPendingInvoice(p, 1);

        pay(c2);
        pay(c1);
        pay(c3);

        assertEquals("PAID", statusOf(c2));
        assertEquals("PAID", statusOf(c1));
        assertEquals("CANCELED_OUT_OF_STOCK", statusOf(c3));
        assertEquals(0, stockOf(p));
    }

    // ---------------------------------------------------------------
    // 3. Webhook gửi trùng 10 lần cho cùng một đơn -> chỉ trừ kho 1 lần
    // ---------------------------------------------------------------
    @Test
    void duplicateWebhook_deductsOnlyOnce() throws Exception {
        Product p = newProduct(5);
        String code = newPendingInvoice(p, 2);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> pay(code));
        }
        runConcurrently(tasks);

        assertEquals(3, stockOf(p));
        assertEquals("PAID", statusOf(code));
    }

    // ---------------------------------------------------------------
    // 4. Giỏ 2 sản phẩm, 1 sản phẩm hết hàng -> PARTIALLY_PAID, giao phần còn hàng
    // ---------------------------------------------------------------
    @Test
    void oneProductOutOfStock_partialFulfillment() {
        Product a = newProduct(5);
        Product b = newProduct(0);
        Map<Product, Integer> items = new LinkedHashMap<>();
        items.put(a, 2);
        items.put(b, 1);
        String code = newPendingInvoice(items);

        pay(code);

        assertEquals("PARTIALLY_PAID", statusOf(code));
        assertEquals(3, stockOf(a), "Sản phẩm còn hàng phải được trừ kho");
        assertEquals(0, stockOf(b), "Sản phẩm hết hàng không được âm");

        // Đọc trạng thái từng dòng (lazy collection nên phải đọc trong transaction)
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Invoice inv = invoiceRepository.findByInvoiceCode(code).orElseThrow();
            Map<Long, String> lineStatus = new HashMap<>();
            for (InvoiceDetail d : inv.getInvoiceDetails()) {
                lineStatus.put(d.getProduct().getProductId(), d.getStatus());
            }
            assertEquals("FULFILLED", lineStatus.get(a.getProductId()));
            assertEquals("OUT_OF_STOCK", lineStatus.get(b.getProductId()));
        });
    }

    // ---------------------------------------------------------------
    // 5. Nhiều sản phẩm, thứ tự dòng khác nhau giữa các đơn -> không deadlock
    //    (20 đơn, mỗi đơn mua 1 A + 1 B, kho mỗi loại 10)
    // ---------------------------------------------------------------
    @Test
    void multiProduct_differentLineOrder_noDeadlock() throws Exception {
        Product a = newProduct(10);
        Product b = newProduct(10);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<Product, Integer> items = new LinkedHashMap<>();
            if (i % 2 == 0) {
                items.put(a, 1);
                items.put(b, 1);
            } else {
                items.put(b, 1);
                items.put(a, 1);
            }
            codes.add(newPendingInvoice(items));
        }

        List<Runnable> tasks = new ArrayList<>();
        for (String code : codes) {
            tasks.add(() -> pay(code));
        }
        runConcurrently(tasks); // deadlock sẽ làm future ném exception hoặc timeout

        assertEquals(0, stockOf(a));
        assertEquals(0, stockOf(b));
        assertEquals(10, countStatus(codes, "PAID"));
        assertEquals(10, countStatus(codes, "CANCELED_OUT_OF_STOCK"));
    }

    // =================================================================
    // Hàm hỗ trợ
    // =================================================================

    private void pay(String invoiceCode) {
        invoiceService.handlePaymentSuccess(invoiceCode, "TX-" + invoiceCode);
    }

    private Product newProduct(int stock) {
        Product p = new Product();
        p.setName("Test product");
        p.setStockQuantity(stock);
        p.setUnit("cai");
        p.setCurrentUnitPrice(BigDecimal.valueOf(1_000_000));
        p = productRepository.save(p);
        productIds.add(p.getProductId());
        return p;
    }

    private String newPendingInvoice(Product product, int quantity) {
        Map<Product, Integer> items = new LinkedHashMap<>();
        items.put(product, quantity);
        return newPendingInvoice(items);
    }

    /** Tạo thẳng một hóa đơn PENDING trong DB (bỏ qua createInvoice để kiểm soát chính xác dữ liệu). */
    private String newPendingInvoice(Map<Product, Integer> items) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceCode("TEST" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        invoice.setSeller(seller);
        invoice.setBuyerName("Test buyer");
        invoice.setBuyerPhone("0900000001");
        invoice.setStatus("PENDING");

        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Product, Integer> entry : items.entrySet()) {
            Product p = entry.getKey();
            int qty = entry.getValue();

            InvoiceDetail detail = new InvoiceDetail();
            detail.setInvoice(invoice);
            detail.setProduct(p);
            detail.setQuantity(qty);
            detail.setUnitPrice(p.getCurrentUnitPrice());
            BigDecimal lineTotal = p.getCurrentUnitPrice().multiply(BigDecimal.valueOf(qty));
            detail.setLineTotal(lineTotal);
            total = total.add(lineTotal);
            invoice.getInvoiceDetails().add(detail);
        }
        invoice.setTotalAmount(total);

        Invoice saved = invoiceRepository.save(invoice);
        invoiceIds.add(saved.getInvoiceId());
        return saved.getInvoiceCode();
    }

    private int stockOf(Product p) {
        return productRepository.findById(p.getProductId()).orElseThrow().getStockQuantity();
    }

    private String statusOf(String invoiceCode) {
        return invoiceRepository.findByInvoiceCode(invoiceCode).orElseThrow().getStatus();
    }

    private long countStatus(List<String> codes, String status) {
        return codes.stream().filter(c -> status.equals(statusOf(c))).count();
    }

    /** Cho tất cả task chờ ở vạch xuất phát rồi chạy cùng một lúc. */
    private void runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    task.run();
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS); // ném lại exception nếu task lỗi (vd deadlock)
            }
        } finally {
            pool.shutdownNow();
        }
    }
}