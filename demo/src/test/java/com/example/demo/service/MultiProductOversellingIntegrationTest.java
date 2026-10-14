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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class MultiProductOversellingIntegrationTest {
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
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

    private Long testProductAId;
    private Long testProductBId;
    private List<String> invoiceCodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 1. Tạo Seller
        Seller seller = new Seller();
        seller.setName("Test Seller Multi");
        seller.setPhone("0123456789");
        seller = sellerRepository.save(seller);

        // 2. Tạo Sản phẩm A (Tồn kho 5)
        Product productA = new Product();
        productA.setName("Vé VIP");
        productA.setStockQuantity(5);
        productA.setUnit("Vé");
        productA.setCurrentUnitPrice(new BigDecimal("500000"));
        productA = productRepository.save(productA);
        testProductAId = productA.getProductId();

        // 3. Tạo Sản phẩm B (Tồn kho 3)
        Product productB = new Product();
        productB.setName("Vé Thường");
        productB.setStockQuantity(3);
        productB.setUnit("Vé");
        productB.setCurrentUnitPrice(new BigDecimal("200000"));
        productB = productRepository.save(productB);
        testProductBId = productB.getProductId();

        // Đồng bộ lên Redis
        redisTemplate.opsForValue().set("product:" + testProductAId + ":stock", "5");
        redisTemplate.opsForValue().set("product:" + testProductBId + ":stock", "3");

        // 4. Tạo 10 đơn hàng, mỗi đơn mua cả 1 vé VIP và 1 vé Thường
        for (int i = 0; i < 10; i++) {
            Invoice invoice = new Invoice();
            invoice.setInvoiceCode("MULTI_GPAY_" + System.nanoTime());
            invoice.setSeller(seller);
            invoice.setBuyerName("Buyer " + i);
            invoice.setBuyerPhone("0987654321");
            invoice.setStatus("PENDING");
            invoice.setTotalAmount(new BigDecimal("700000"));

            // Thêm Product A vào đơn
            InvoiceDetail detailA = new InvoiceDetail();
            detailA.setInvoice(invoice);
            detailA.setProduct(productA);
            detailA.setQuantity(1);
            detailA.setUnitPrice(new BigDecimal("500000"));
            detailA.setLineTotal(new BigDecimal("500000"));
            invoice.getInvoiceDetails().add(detailA);

            // Thêm Product B vào đơn
            InvoiceDetail detailB = new InvoiceDetail();
            detailB.setInvoice(invoice);
            detailB.setProduct(productB);
            detailB.setQuantity(1);
            detailB.setUnitPrice(new BigDecimal("200000"));
            detailB.setLineTotal(new BigDecimal("200000"));
            invoice.getInvoiceDetails().add(detailB);

            invoice = invoiceRepository.save(invoice);
            invoiceCodes.add(invoice.getInvoiceCode());
        }
    }

    @AfterEach
    void tearDown() {
        invoiceRepository.deleteAll();
        productRepository.deleteAll();
        sellerRepository.deleteAll();
        redisTemplate.delete("product:" + testProductAId + ":stock");
        redisTemplate.delete("product:" + testProductBId + ":stock");
    }

    @Test
    void testPartialFulfillmentWithMultipleProducts() throws InterruptedException {
        int numberOfThreads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        for (String invoiceCode : invoiceCodes) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await(); // Đợi tất cả thread vào vị trí

                    // GỌI WEBHOOK
                    invoiceService.handlePaymentSuccess(invoiceCode, "TRANS_" + System.nanoTime());
                } catch (Exception e) {
                    System.err.println("Thread lỗi: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // Phất cờ chạy đồng thời
        doneLatch.await();
        executorService.shutdown();

        // ==========================================
        // KIỂM TRA KẾT QUẢ ĐẦU RA
        // ==========================================

        // 1. Kiểm tra tồn kho DB và Redis không bị âm
        Product dbProductA = productRepository.findById(testProductAId).orElseThrow();
        Product dbProductB = productRepository.findById(testProductBId).orElseThrow();
        String redisStockA = redisTemplate.opsForValue().get("product:" + testProductAId + ":stock");
        String redisStockB = redisTemplate.opsForValue().get("product:" + testProductBId + ":stock");

        assertEquals(0, dbProductA.getStockQuantity(), "Kho Product A MySQL phải là 0");
        assertEquals(0, dbProductB.getStockQuantity(), "Kho Product B MySQL phải là 0");
        assertEquals("0", redisStockA, "Kho Product A Redis phải là 0");
        assertEquals("0", redisStockB, "Kho Product B Redis phải là 0");

        // 2 & 3. Đếm số lượng TỪNG DÒNG SẢN PHẨM giao thành công
        // Dùng TransactionTemplate để ép Hibernate tải invoiceDetails mà không bị lỗi Lazy Load
        long fulfilledProductA = transactionTemplate.execute(status -> {
            return invoiceCodes.stream()
                    .map(code -> invoiceRepository.findByInvoiceCode(code).orElseThrow())
                    .flatMap(inv -> inv.getInvoiceDetails().stream())
                    .filter(detail -> detail.getProduct().getProductId().equals(testProductAId))
                    .filter(detail -> "FULFILLED".equals(detail.getStatus()))
                    .count();
        });

        long fulfilledProductB = transactionTemplate.execute(status -> {
            return invoiceCodes.stream()
                    .map(code -> invoiceRepository.findByInvoiceCode(code).orElseThrow())
                    .flatMap(inv -> inv.getInvoiceDetails().stream())
                    .filter(detail -> detail.getProduct().getProductId().equals(testProductBId))
                    .filter(detail -> "FULFILLED".equals(detail.getStatus()))
                    .count();
        });

        // QUAN TRỌNG: Phải bán được chính xác 5 vé VIP và 3 vé Thường, không hơn không kém
        assertEquals(5, fulfilledProductA, "Tổng số dòng Product A bán thành công phải đúng bằng tồn kho ban đầu (5)");
        assertEquals(3, fulfilledProductB, "Tổng số dòng Product B bán thành công phải đúng bằng tồn kho ban đầu (3)");

        // Tính log
        long[] stats = transactionTemplate.execute(status -> {
            List<Invoice> processedInvoices = invoiceCodes.stream()
                    .map(code -> invoiceRepository.findByInvoiceCode(code).orElseThrow())
                    .collect(java.util.stream.Collectors.toList());
            return new long[] {
                    processedInvoices.stream().filter(i -> "PAID".equals(i.getStatus())).count(),
                    processedInvoices.stream().filter(i -> "PARTIALLY_PAID".equals(i.getStatus())).count(),
                    processedInvoices.stream().filter(i -> "CANCELED_OUT_OF_STOCK".equals(i.getStatus())).count()
            };
        });

        System.out.println("====== KẾT QUẢ TEST ĐA LUỒNG MULTI-PRODUCT ======");
        System.out.println("Số đơn hoàn hảo (PAID): " + stats[0]);
        System.out.println("Số đơn mua được một phần (PARTIALLY_PAID): " + stats[1]);
        System.out.println("Số đơn xịt hoàn toàn (CANCELED): " + stats[2]);
        System.out.println("=================================================");
    }
}