package com.example.backend.controller;

import com.example.backend.dto.OrderRequest;
import com.example.backend.model.Invoice;
import com.example.backend.security.AppUserDetails;
import com.example.backend.service.InvoiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    @Autowired
    private InvoiceService invoiceService;

    // 1. API tạo hóa đơn (trả về thông tin để khách xem trước)
    @PostMapping
    public ResponseEntity<?> createInvoice(@RequestBody OrderRequest request,
                                           @AuthenticationPrincipal AppUserDetails principal) { // 👉 BỔ SUNG (Auth)
        try {
            Invoice newInvoice = invoiceService.createInvoice(request, principal);
            return ResponseEntity.ok(newInvoice);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 2. API lấy thông tin hóa đơn theo mã để xem lại trước khi thanh toán
    @GetMapping("/{invoiceCode}")
    public ResponseEntity<?> getInvoiceByCode(@PathVariable String invoiceCode,
                                              @AuthenticationPrincipal AppUserDetails principal) { // 👉 BỔ SUNG (Auth)
        try {
            // Chỉ chủ hóa đơn hoặc ADMIN được xem
            Invoice invoice = invoiceService.getInvoiceForUser(invoiceCode, principal);
            return ResponseEntity.ok(invoice);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
