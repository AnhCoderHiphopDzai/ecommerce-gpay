package com.example.backend.controller;

import com.example.backend.dto.ProductRequest;
import com.example.backend.model.Product;
import com.example.backend.security.AppUserDetails;
import com.example.backend.service.AdminProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Quản lý sản phẩm, chỉ ADMIN.
 * Phân quyền hai lớp (phòng thủ nhiều tầng): SecurityConfig chặn theo đường dẫn /api/admin/** và
 * @PreAuthorize ở đây chặn theo phương thức. Quên một lớp thì lớp còn lại vẫn giữ.
 * Để xem sản phẩm, dùng GET /api/products công khai có sẵn.
 */
@RestController
@RequestMapping("/api/admin/products")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminProductController {

    private final AdminProductService productService;

    @PostMapping
    public ResponseEntity<Product> create(@Valid @RequestBody ProductRequest body,
                                          @AuthenticationPrincipal AppUserDetails admin) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(body, admin));
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable Long id,
                          @Valid @RequestBody ProductRequest body,
                          @AuthenticationPrincipal AppUserDetails admin) {
        return productService.update(id, body, admin);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal AppUserDetails admin) {
        productService.delete(id, admin);
        return ResponseEntity.noContent().build();
    }
}