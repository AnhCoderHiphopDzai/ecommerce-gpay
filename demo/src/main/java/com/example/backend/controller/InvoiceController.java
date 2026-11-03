package com.example.backend.controller;

import com.example.backend.dto.OrderRequest;
import com.example.backend.model.Invoice;
import com.example.backend.security.AppUserDetails;
import com.example.backend.service.InvoiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    // 1. API tạo hóa đơn (trả về thông tin để khách xem trước)
    @PostMapping
    public ResponseEntity<Invoice> createInvoice(@Valid @RequestBody OrderRequest request,
                                                 @AuthenticationPrincipal AppUserDetails principal) {
        try {
            Invoice newInvoice = invoiceService.createInvoice(request, principal);
            return ResponseEntity.ok(newInvoice);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Lỗi nghiệp vụ (sản phẩm hết hàng, seller/sản phẩm không còn) là lỗi dữ liệu client có thể sửa.
            // Không trả stack trace hoặc chi tiết SQL ra trình duyệt.
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Không thể tạo hóa đơn. Kiểm tra lại giỏ hàng và thử lại.", ex);
        }
    }

    // 2. API lấy thông tin hóa đơn theo mã để xem lại trước khi thanh toán
    @GetMapping("/{invoiceCode}")
    public Invoice getInvoiceByCode(@PathVariable String invoiceCode,
                                    @AuthenticationPrincipal AppUserDetails principal) {
        // Chỉ chủ hóa đơn hoặc ADMIN được xem. Không phải chủ cũng nhận 404 như mã không tồn tại.
        return invoiceService.getInvoiceForUser(invoiceCode, principal);
    }
}
