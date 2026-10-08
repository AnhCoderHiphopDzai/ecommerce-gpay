package com.example.backend.service;

import com.example.backend.dao.InvoiceRepository;
import com.example.backend.dao.ProductRepository;
import com.example.backend.dao.SellerRepository;
import com.example.backend.dto.OrderRequest;
import com.example.backend.model.Invoice;
import com.example.backend.model.InvoiceDetail;
import com.example.backend.model.Product;
import com.example.backend.model.Seller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class InvoiceService {

    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SellerRepository sellerRepository;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // =========================================
    // 1. TẠO ĐƠN & TRỪ KHO CỨNG NGAY LẬP TỨC
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

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderRequest.OrderItem item : request.getItems()) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy sản phẩm"));

            // Kiểm tra kho thực tế
            if (product.getStockQuantity() < item.getQuantity()) {
                throw new RuntimeException("Sản phẩm " + product.getName() + " đã hết hàng!");
            }

            //  TRỪ KHO CỨNG NGAY LÚC LÊN ĐƠN
            product.setStockQuantity(product.getStockQuantity() - item.getQuantity());
            productRepository.save(product);

            //  PHÁT WEBSOCKET CHO MỌI NGƯỜI BIẾT KHO VỪA TỤT
            Map<String, Object> payload = new HashMap<>();
            payload.put("productId", product.getProductId());
            payload.put("stockQuantity", product.getStockQuantity());
            messagingTemplate.convertAndSend("/topic/stock-updates", (Object) payload);

            if (product.getStockQuantity() == 0) {
                messagingTemplate.convertAndSend("/topic/sold-out", String.valueOf(product.getProductId()));
            }

            InvoiceDetail detail = new InvoiceDetail();
            detail.setInvoice(invoice);
            detail.setProduct(product);
            detail.setQuantity(item.getQuantity());
            detail.setUnitPrice(product.getCurrentUnitPrice());

            BigDecimal lineTotal = product.getCurrentUnitPrice().multiply(new BigDecimal(item.getQuantity()));
            detail.setLineTotal(lineTotal);
            totalAmount = totalAmount.add(lineTotal);
            invoice.getInvoiceDetails().add(detail);
        }

        invoice.setTotalAmount(totalAmount);
        return invoiceRepository.save(invoice);
    }

    // =========================================
    // 2. KIỂM TRA TRƯỚC THANH TOÁN
    // =========================================
    public void validateStockBeforePayment(String invoiceCode) {
        Invoice invoice = getInvoiceByCode(invoiceCode);

        // Chỉ cần check xem đơn còn PENDING không (chưa bị CronJob hủy)
        if ("CANCELLED".equals(invoice.getStatus())) {
            throw new RuntimeException("Đơn hàng đã bị hủy tự động do quá 3 phút không thanh toán. Vui lòng đặt lại!");
        }
        if (!"PENDING".equals(invoice.getStatus())) {
            throw new RuntimeException("Hóa đơn không ở trạng thái hợp lệ để thanh toán.");
        }
    }

    // =========================================
    // 3. XỬ LÝ KHI CÓ TIỀN VÀO (Chỉ đổi status, không trừ kho nữa)
    // =========================================
    @Transactional
    public void handleVAPaymentSuccess(String vaNumber, String gpayTransId, BigDecimal amountPaid) {
        Invoice invoice = invoiceRepository.findByVirtualAccountNumber(vaNumber)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn"));

        if (!"PENDING".equals(invoice.getStatus())) return;

        // Chỉ việc cập nhật trạng thái PAID
        invoice.setStatus("PAID");
        invoice.setGpayTransId(gpayTransId);
        invoiceRepository.save(invoice);

        // Phát loa chúc mừng
        messagingTemplate.convertAndSend("/topic/payment-success", invoice.getInvoiceCode());
    }

    @Transactional
    public void handlePaymentSuccess(String invoiceCode, String gpayTransId) {
        Invoice invoice = invoiceRepository.findByInvoiceCode(invoiceCode)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn"));

        if (!"PENDING".equals(invoice.getStatus())) return;

        invoice.setStatus("PAID");
        invoice.setGpayTransId(gpayTransId);
        invoiceRepository.save(invoice);

        messagingTemplate.convertAndSend("/topic/payment-success", invoice.getInvoiceCode());
    }

    // =========================================
    // 4. CRONJOB: TỰ ĐỘNG HỦY ĐƠN VÀ HOÀN KHO SAU 3 PHÚT
    // =========================================
    @Scheduled(fixedRate = 60000) // Chạy ngầm định kỳ mỗi 60 giây
    @Transactional
    public void autoCancelExpiredInvoices() {
        LocalDateTime threeMinsAgo = LocalDateTime.now().minusMinutes(3);

        // Quét tìm tất cả các đơn PENDING mà được tạo từ trước thời điểm 3 phút trước
        List<Invoice> expiredInvoices = invoiceRepository.findByStatusAndCreatedAtBefore("PENDING", threeMinsAgo);

        for (Invoice invoice : expiredInvoices) {
            invoice.setStatus("CANCELLED"); // Đánh dấu hủy

            for (InvoiceDetail detail : invoice.getInvoiceDetails()) {
                Product product = detail.getProduct();

                //  CỘNG TRẢ LẠI KHO (HOÀN KHO)
                product.setStockQuantity(product.getStockQuantity() + detail.getQuantity());
                productRepository.save(product);

                //  BẮN WEBSOCKET BÁO CHO MỌI NGƯỜI: "CÓ HÀNG LẠI RỒI!"
                Map<String, Object> payload = new HashMap<>();
                payload.put("productId", product.getProductId());
                payload.put("stockQuantity", product.getStockQuantity());
                messagingTemplate.convertAndSend("/topic/stock-updates", (Object) payload);
            }
            invoiceRepository.save(invoice);
            System.out.println("⏳ CRONJOB: Đã tự động hủy đơn " + invoice.getInvoiceCode() + " và hoàn trả kho.");
        }
    }

    // Các hàm phụ trợ giữ nguyên
    @Transactional
    public void updateInvoiceVirtualAccount(String invoiceCode, String vaNumber, String vaBank) {
        Invoice invoice = getInvoiceByCode(invoiceCode);
        invoice.setVirtualAccountNumber(vaNumber);
        invoice.setVirtualAccountBank(vaBank);
        invoiceRepository.save(invoice);
    }

    @Transactional
    public void handlePaymentFailed(String invoiceCode) {
        Invoice invoice = invoiceRepository.findByInvoiceCode(invoiceCode).orElse(null);
        if (invoice != null && "PENDING".equals(invoice.getStatus())) {
            invoice.setStatus("FAILED");
            // Nên gọi logic hoàn kho ở đây nếu thanh toán Failed
        }
    }

    public Invoice getInvoiceByCode(String invoiceCode) {
        return invoiceRepository.findByInvoiceCode(invoiceCode)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn: " + invoiceCode));
    }
}