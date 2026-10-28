package com.example.backend.controller;

import com.example.backend.dto.AdminInvoiceResponse;
import com.example.backend.dto.PageResponse;
import com.example.backend.security.AppUserDetails;
import com.example.backend.service.AdminInvoiceService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Xem hóa đơn của mọi khách hàng, chỉ ADMIN (hai lớp kiểm tra như AdminProductController). */
@RestController
@RequestMapping("/api/admin/invoices")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminInvoiceController {

    private static final Logger AUDIT = LoggerFactory.getLogger("security.audit");

    private final AdminInvoiceService invoiceService;

    /** GET /api/admin/invoices?status=PAID&page=0&size=20 (status để trống = tất cả) */
    @GetMapping
    public PageResponse<AdminInvoiceResponse> list(@RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return invoiceService.list(status, page, size);
    }

    @GetMapping("/{invoiceCode}")
    public AdminInvoiceResponse detail(@PathVariable String invoiceCode,
                                       @AuthenticationPrincipal AppUserDetails admin) {
        AdminInvoiceResponse invoice = invoiceService.get(invoiceCode);
        // Ghi nhật ký xem dữ liệu của khách (log mã lấy từ DB, không log chuỗi do client gửi)
        AUDIT.info("event=ADMIN_INVOICE_VIEWED adminId={} invoiceCode={}", admin.getId(), invoice.invoiceCode());
        return invoice;
    }
}