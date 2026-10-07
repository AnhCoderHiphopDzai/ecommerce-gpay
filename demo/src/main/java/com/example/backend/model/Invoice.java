package com.example.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "invoice_code", nullable = false, unique = true, length = 50)
    private String invoiceCode;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "buyer_name", nullable = false, length = 100)
    private String buyerName;

    @Column(name = "buyer_phone", nullable = false, length = 15)
    private String buyerPhone;

    @Column(name = "status", length = 20)
    private String status = "PENDING"; // PENDING, ORDER_SUCCESS, FAILED

    // 👉 BỔ SUNG: Lưu mã bill_id do G-Pay trả về sau khi gọi init-order thành công
    @Column(name = "gpay_bill_id", length = 100)
    private String gpayBillId;

    // 👉 BỔ SUNG: Lưu request_id (chính là mã giao dịch phía merchant gửi đi)
    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "gpay_trans_id")
    private String gpayTransId;

    @Column(name = "virtual_account_number", length = 50)
    private String virtualAccountNumber;

    @Column(name = "virtual_account_bank", length = 20)
    private String virtualAccountBank;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Seller seller;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonManagedReference
    private List<InvoiceDetail> invoiceDetails = new ArrayList<>();
}