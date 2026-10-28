package com.example.backend.dao;

import com.example.backend.model.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Trừ kho tương đối trực tiếp trong DB.
     * Tránh hoàn toàn lỗi Lost Update khi nhiều thread cùng cập nhật MySQL.
     */
    @Modifying
    @Query("UPDATE Product p SET p.stockQuantity = p.stockQuantity - :amount WHERE p.productId = :id AND p.stockQuantity >= :amount")
    int decreaseStockAtomic(@Param("id") Long id, @Param("amount") int amount);

    // ===== Dành cho trang quản trị (AdminProductService) =====

    // SELECT ... FOR UPDATE: admin sửa/xóa sản phẩm thì các lệnh trừ kho ở trên phải chờ, không ghi đè lẫn nhau
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.productId = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    // Số dòng hóa đơn đang tham chiếu sản phẩm này (> 0 thì không được xóa)
    @Query("SELECT COUNT(d) FROM InvoiceDetail d WHERE d.product.productId = :id")
    long countInvoiceLines(@Param("id") Long id);
}