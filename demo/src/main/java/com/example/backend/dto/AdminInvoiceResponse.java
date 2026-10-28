package com.example.backend.dto;

import com.example.backend.model.Invoice;
import com.example.backend.model.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Hóa đơn hiển thị cho trang quản trị.
 * Dùng DTO thay vì trả thẳng entity để không lộ dữ liệu nhạy cảm (mật khẩu băm của user...)
 * và tránh lỗi serialize quan hệ lazy.
 */
public record AdminInvoiceResponse(
        String invoiceCode,
        String status,
        String buyerName,
        String buyerPhone,
        BigDecimal totalAmount,
        LocalDateTime createdAt,
        Long userId,
        String userEmail,
        String gpayTransId,
        String virtualAccountNumber,
        List<Item> items) {

    public record Item(Long productId, String productName, Integer quantity,
                       BigDecimal unitPrice, BigDecimal lineTotal, String status) {
    }

    /** withItems=false cho danh sách (không nạp chi tiết từng hóa đơn), true cho màn chi tiết. */
    public static AdminInvoiceResponse from(Invoice i, boolean withItems) {
        User u = i.getUser();
        List<Item> items = withItems
                ? i.getInvoiceDetails().stream()
                .map(d -> new Item(d.getProduct().getProductId(), d.getProduct().getName(),
                        d.getQuantity(), d.getUnitPrice(), d.getLineTotal(), d.getStatus()))
                .toList()
                : null;
        return new AdminInvoiceResponse(
                i.getInvoiceCode(), i.getStatus(), i.getBuyerName(), i.getBuyerPhone(),
                i.getTotalAmount(), i.getCreatedAt(),
                u == null ? null : u.getUserId(), u == null ? null : u.getEmail(),
                i.getGpayTransId(), i.getVirtualAccountNumber(), items);
    }
}