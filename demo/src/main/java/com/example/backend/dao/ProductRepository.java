package com.example.backend.dao;

import com.example.backend.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Trừ kho tương đối trực tiếp trong DB.
     * Tránh hoàn toàn lỗi Lost Update khi nhiều thread cùng cập nhật MySQL.
     */
    @Modifying
    @Query("UPDATE Product p SET p.stockQuantity = p.stockQuantity - :amount WHERE p.productId = :id AND p.stockQuantity >= :amount")
    int decreaseStockAtomic(@Param("id") Long id, @Param("amount") int amount);
}