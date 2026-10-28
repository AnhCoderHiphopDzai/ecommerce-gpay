package com.example.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lỗi của nhóm /api/admin, luôn là JSON {"message": "...", "errors": {...}} giống /api/auth.
 * Cố ý KHÔNG bắt AccessDeniedException: lỗi thiếu quyền phải đi tiếp tới accessDeniedHandler
 * trong SecurityConfig để trả 403 thống nhất.
 */
@RestControllerAdvice(assignableTypes = {AdminProductController.class, AdminInvoiceController.class})
public class AdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidationError(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of(
                "message", "Dữ liệu chưa hợp lệ, vui lòng kiểm tra lại",
                "errors", errors));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatusError(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : "Yêu cầu không hợp lệ";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", message));
    }

    /**
     * Vi phạm ràng buộc của DB (cột bắt buộc, trùng khóa, khóa ngoại...).
     * Client chỉ nhận thông báo chung; chi tiết (tên cột, câu SQL) chỉ ghi vào log máy chủ.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> onDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Admin API bị DB từ chối do vi phạm ràng buộc: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "message", "Dữ liệu bị cơ sở dữ liệu từ chối. Kiểm tra lại các trường hoặc xem nhật ký máy chủ."));
    }
}