package com.example.backend.dao;

import com.example.backend.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    //tìm kiếm hóa đơn theo mã để phục vụ cho việc xử lý Webhook
    Optional<Invoice> findByInvoiceCode(String invoiceCode);
    //tìm kiếm theo số tài khoản ảo để xử lý webhook
    Optional<Invoice> findByVirtualAccountNumber(String virtualAccountNumber);
    List<Invoice> findByStatusAndCreatedAtBefore(String status, LocalDateTime timeLimit);
}