package com.example.backend.dao;

import com.example.backend.model.Invoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    // ===== Khoá dòng invoice (SELECT ... FOR UPDATE) cho webhook =====
    // Webhook trùng (G-Pay gửi lại) hoặc webhook đua với nhau sẽ xếp hàng ở đây,
    // lần sau vào sẽ thấy status đã chốt và bỏ qua -> không trừ kho hai lần.

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.invoiceCode = :code")
    Optional<Invoice> findByInvoiceCodeForUpdate(@Param("code") String code);

    // Nên đánh index cho cột virtual_account_number, nếu không FOR UPDATE phải quét cả bảng
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.virtualAccountNumber = :va")
    Optional<Invoice> findByVirtualAccountNumberForUpdate(@Param("va") String va);

    // UPDATE có điều kiện chạy nguyên tử trong DB: nếu webhook vừa chuyển đơn sang PAID
    // thì điều kiện status = 'PENDING' không còn đúng và dòng đó không bị ghi đè.
    @Modifying
    @Query("UPDATE Invoice i SET i.status = 'EXPIRED' WHERE i.status = 'PENDING' AND i.createdAt < :limit")
    int expirePendingBefore(@Param("limit") LocalDateTime limit);

    // ===== Dành cho trang quản trị (AdminInvoiceService) =====
    // Nạp luôn user trong cùng một câu truy vấn để mỗi dòng của danh sách không phát sinh thêm một truy vấn riêng.

    @Override
    @EntityGraph(attributePaths = "user")
    Page<Invoice> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<Invoice> findByStatus(String status, Pageable pageable);
}