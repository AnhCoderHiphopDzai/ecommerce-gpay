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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class InvoiceServiceOversellingIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private SellerRepository sellerRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long testProductId;
    private List<String> invoiceCodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 1. Tạo dữ liệu giả lập Seller
        Seller seller = new Seller();
        seller.setName("Test Seller");
        seller.setPhone("0123456789");
        seller = sellerRepository.save(seller);

        // 2. Tạo 1 sản phẩm có tồn kho là 5
        Product product = new Product();
        product.setName("Vé sự kiện HOT");
        product.setStockQuantity(5);
        product.setUnit("Vé");
        product.setCurrentUnitPrice(new BigDecimal("100000"));
        product = productRepository.save(product);
        testProductId = product.getProductId();

        // Đồng bộ tồn kho lên Redis
        redisTemplate.opsForValue().set("product:" + testProductId + ":stock", "5");

        // 3. Tạo 10 đơn hàng PENDING (mỗi đơn mua 1 vé, tổng cầu = 10 > tổng cung = 5)
        for (int i = 0; i < 10; i++) {
            Invoice invoice = new Invoice();
            invoice.setInvoiceCode("TEST_GPAY_" + System.nanoTime());
            invoice.setSeller(seller);
            invoice.setBuyerName("Buyer " + i);
            invoice.setBuyerPhone("0987654321");
            invoice.setStatus("PENDING");
            invoice.setTotalAmount(new BigDecimal("100000"));

            InvoiceDetail detail = new InvoiceDetail();
            detail.setInvoice(invoice);
            detail.setProduct(product);
            detail.setQuantity(1);
            detail.setUnitPrice(new BigDecimal("100000"));
            detail.setLineTotal(new BigDecimal("100000"));
            invoice.getInvoiceDetails().add(detail);

            invoice = invoiceRepository.save(invoice);
            invoiceCodes.add(invoice.getInvoiceCode());
        }
    }

    @AfterEach
    void tearDown() {
        invoiceRepository.deleteAll();
        productRepository.deleteAll();
        sellerRepository.deleteAll();
        redisTemplate.delete("product:" + testProductId + ":stock");
    }

    @Test
    void testAntiOversellingWithRedisLuaScript() throws InterruptedException {
        int numberOfThreads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

        // CountDownLatch giúp các thread chờ nhau để "xuất phát" cùng 1 mili-giây
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        for (String invoiceCode : invoiceCodes) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await(); // Chờ cờ lệnh xuất phát

                    // GỌI HÀM XỬ LÝ WEBHOOK ĐỒNG THỜI
                    invoiceService.handlePaymentSuccess(invoiceCode, "TRANS_" + System.nanoTime());
                } catch (Exception e) {
                    System.err.println("Thread lỗi: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(); // Chờ tất cả thread sẵn sàng
        startLatch.countDown(); // Phất cờ cho 10 thread chạy cùng lúc
        doneLatch.await(); // Chờ tất cả thread  chạy xong

        executorService.shutdown();

        // KIỂM TRA KẾT QUẢ
        Product dbProduct = productRepository.findById(testProductId).orElseThrow();
        String redisStock = redisTemplate.opsForValue().get("product:" + testProductId + ":stock");

        // 1. Kho DB và Redis phải bằng 0 (không bị âm)
        assertEquals(0, dbProduct.getStockQuantity(), "Tồn kho trong MySQL phải bằng 0");
        assertEquals("0", redisStock, "Tồn kho trong Redis phải bằng 0");

        // 👉 SỬA LẠI: Chỉ truy vấn và đếm trên danh sách 10 đơn hàng vừa tạo trong bài test này
        long paidInvoices = invoiceCodes.stream()
                .map(code -> invoiceRepository.findByInvoiceCode(code).orElseThrow())
                .filter(inv -> "PAID".equals(inv.getStatus()))
                .count();

        long outOfStockInvoices = invoiceCodes.stream()
                .map(code -> invoiceRepository.findByInvoiceCode(code).orElseThrow())
                .filter(inv -> "CANCELED_OUT_OF_STOCK".equals(inv.getStatus()))
                .count();

        // 2. Chỉ có đúng 5 đơn thành công, 5 đơn còn lại phải bị hủy do hết hàng
        assertEquals(5, paidInvoices, "Phải có đúng 5 đơn hàng được thanh toán thành công (PAID)");
        assertEquals(5, outOfStockInvoices, "Phải có đúng 5 đơn hàng bị hủy do hết hàng (CANCELED)");
    }
}