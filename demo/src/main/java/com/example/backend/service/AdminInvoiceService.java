package com.example.backend.service;

import com.example.backend.dao.InvoiceRepository;
import com.example.backend.dto.AdminInvoiceResponse;
import com.example.backend.dto.PageResponse;
import com.example.backend.model.Invoice;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Xem hóa đơn của mọi khách hàng (chỉ ADMIN gọi được, xem AdminInvoiceController). */
@Service
@RequiredArgsConstructor
public class AdminInvoiceService {

    private static final int MAX_PAGE_SIZE = 100;

    private final InvoiceRepository invoiceRepository;

    @Transactional(readOnly = true)
    public PageResponse<AdminInvoiceResponse> list(String status, int page, int size) {
        // Giới hạn kích thước trang để một request không kéo cả bảng hóa đơn lên
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Invoice> result = StringUtils.hasText(status)
                ? invoiceRepository.findByStatus(status.trim(), pageable)
                : invoiceRepository.findAll(pageable);
        return PageResponse.of(result.map(i -> AdminInvoiceResponse.from(i, false)));
    }

    @Transactional(readOnly = true)
    public AdminInvoiceResponse get(String invoiceCode) {
        Invoice invoice = invoiceRepository.findByInvoiceCode(invoiceCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hóa đơn"));
        return AdminInvoiceResponse.from(invoice, true);
    }
}