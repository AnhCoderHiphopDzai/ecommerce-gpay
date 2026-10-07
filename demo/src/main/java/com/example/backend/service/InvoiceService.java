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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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

    public void validateStockBeforePayment(String invoiceCode) {
        Invoice invoice = getInvoiceByCode(invoiceCode);
        LocalDateTime threeMinsAgo = LocalDateTime.now().minusMinutes(3);

        for (InvoiceDetail detail : invoice.getInvoiceDetails()) {
            int lockedQty = invoiceRepository.getLockedQuantity(
                    detail.getProduct().getProductId(),
                    threeMinsAgo,
                    invoice.getInvoiceId()
            );

            int availableStock = detail.getProduct().getStockQuantity() - lockedQty;

            if (availableStock < detail.getQuantity()) {
                throw new RuntimeException("Sản phẩm '" + detail.getProduct().getName() +
                        "' đang được khách khác thanh toán. Vui lòng thử lại sau 3 phút!");
            }
        }
    }

    @Transactional
    public Invoice createInvoice(OrderRequest request) {
        Seller seller = sellerRepository.findById(request.getSellerId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Seller"));

        Invoice invoice = new Invoice();


        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int randomDigits = 1000 + new Random().nextInt(9000); // Random từ 1000 đến 9999
        invoice.setInvoiceCode("GPAY" + datePart + randomDigits);


        invoice.setSeller(seller);
        invoice.setBuyerName(request.getBuyerName());
        invoice.setBuyerPhone(request.getBuyerPhone());
        invoice.setStatus("PENDING");

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderRequest.OrderItem item : request.getItems()) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy sản phẩm"));

            if (product.getStockQuantity() < item.getQuantity()) {
                throw new RuntimeException("Sản phẩm " + product.getName() + " không đủ số lượng!");
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

    // Bổ sung tham số gpayTransId để lưu mã đối soát
    @Transactional
    public void handlePaymentSuccess(String invoiceCode, String gpayTransId) {
        Invoice invoice = invoiceRepository.findByInvoiceCode(invoiceCode)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn: " + invoiceCode));

        // Idempotency: Tránh xử lý trùng lặp nếu Webhook từ G-Pay gọi về nhiều lần
        if ("PAID".equals(invoice.getStatus())) {
            return;
        }

        // Cập nhật trạng thái đơn hàng thành công
        invoice.setStatus("PAID");
        invoice.setGpayTransId(gpayTransId);

        // TIẾN HÀNH TRỪ KHO VÀ BẮN WEBSOCKET
        for (InvoiceDetail detail : invoice.getInvoiceDetails()) {
            Product product = detail.getProduct();

            // Chặn trường hợp xấu nhất (Safety check)
            if (product.getStockQuantity() < detail.getQuantity()) {
                throw new RuntimeException("Sản phẩm " + product.getName() + " không đủ số lượng trong kho!");
            }

            // Thực hiện trừ kho
            product.setStockQuantity(product.getStockQuantity() - detail.getQuantity());
            productRepository.save(product);

            // ==========================================
            // BẮN WEBSOCKET THÔNG BÁO LÊN FRONTEND
            // ==========================================
            Map<String, Object> payload = new HashMap<>();
            payload.put("productId", product.getProductId());
            payload.put("stockQuantity", product.getStockQuantity());

            // 1. Cập nhật số tồn kho realtime cho toàn bộ user đang mở trang web
            messagingTemplate.convertAndSend("/topic/stock-updates", (Object) payload);

            if (product.getStockQuantity() == 0) {
                // SỬA LỖI Ở ĐÂY: Đổi ID thành dạng String để tránh lỗi nạp chồng hàm
                messagingTemplate.convertAndSend("/topic/sold-out", String.valueOf(product.getProductId()));
            }
        }

        invoiceRepository.save(invoice);
    }
    @Transactional
    public void updateInvoiceVirtualAccount(String invoiceCode, String vaNumber, String vaBank) {
        Invoice invoice = invoiceRepository.findByInvoiceCode(invoiceCode)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn"));
        invoice.setVirtualAccountNumber(vaNumber);
        invoice.setVirtualAccountBank(vaBank);
        invoiceRepository.save(invoice);
    }

        @Transactional
        public void handleVAPaymentSuccess(String vaNumber, String gpayTransId, BigDecimal amountPaid) {
            Invoice invoice = invoiceRepository.findByVirtualAccountNumber(vaNumber)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn"));

            if ("PAID".equals(invoice.getStatus())) return;

            invoice.setStatus("PAID");
            invoice.setGpayTransId(gpayTransId);

            for (InvoiceDetail detail : invoice.getInvoiceDetails()) {
                Product product = detail.getProduct();
                if (product.getStockQuantity() < detail.getQuantity()) {
                    throw new RuntimeException("Sản phẩm không đủ kho!"); // Chặn trường hợp xấu nhất
                }

                // Trừ kho
                product.setStockQuantity(product.getStockQuantity() - detail.getQuantity());
                productRepository.save(product);

                // BẮN WEBSOCKET CẬP NHẬT KHO LÊN FRONTEND
                Map<String, Object> payload = new HashMap<>();
                payload.put("productId", product.getProductId());
                payload.put("stockQuantity", product.getStockQuantity());

                // Bắn tồn kho mới
                messagingTemplate.convertAndSend("/topic/stock-updates", (Object) payload);

                if (product.getStockQuantity() == 0) {
                    // SỬA LỖI Ở ĐÂY: Đổi ID thành dạng String để tránh lỗi nạp chồng hàm
                    messagingTemplate.convertAndSend("/topic/sold-out", String.valueOf(product.getProductId()));
                }
            }
            invoiceRepository.save(invoice);
            messagingTemplate.convertAndSend("/topic/payment-success", invoice.getInvoiceCode());
        }

    @Transactional
    public void handlePaymentFailed(String invoiceCode) {
        Invoice invoice = invoiceRepository.findByInvoiceCode(invoiceCode).orElse(null);
        if (invoice != null && "PENDING".equals(invoice.getStatus())) {
            invoice.setStatus("FAILED");
            invoiceRepository.save(invoice);
        }
    }


    public Invoice getInvoiceByCode(String invoiceCode) {
        return invoiceRepository.findByInvoiceCode(invoiceCode)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn với mã: " + invoiceCode));
    }

}